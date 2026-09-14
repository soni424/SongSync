package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.SpotifyLyricsNotFoundException
import pl.lambada.songsync.util.SpotifyFailureKind
import pl.lambada.songsync.util.SpotifyOperation
import pl.lambada.songsync.util.SpotifyRateLimitException
import pl.lambada.songsync.util.SpotifyServiceException
import pl.lambada.songsync.util.SpotifySessionExpiredException
import pl.lambada.songsync.util.networking.Ktor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

class SpotifyAPITest {
    @Test
    fun `authenticated Spotify lyrics are returned as LRC without using a proxy`() = runTest {
        val fixture = fixture()

        assertEquals("[00:01.23]First line", fixture.api.getSyncedLyrics("track-id"))
        assertFalse(fixture.requests.any { it.url.host.contains("alwaysdata") })
    }

    @Test
    fun `anonymous token rejects cookie without saving it`() = runTest {
        val fixture = fixture(isAnonymous = true, initialCookie = null)

        expectFailure<SpotifySessionExpiredException> {
            fixture.api.verifyAndSaveCookie("sp_dc=new-cookie; Path=/")
        }
        assertNull(fixture.store.read())
    }

    @Test
    fun `save and verify accepts a full sp_dc assignment but stores only its value`() = runTest {
        val fixture = fixture(initialCookie = null)

        fixture.api.verifyAndSaveCookie("sp_dc=new-cookie; Path=/")

        assertEquals("new-cookie", fixture.store.read())
        assertEquals(
            "Cookie verification must not depend on Spotify's separate client-token service",
            0,
            fixture.requests.count { it.url.host == "clienttoken.spotify.com" },
        )
    }

    @Test
    fun `genuine lyrics 404 is not confused with provider failure`() = runTest {
        val fixture = fixture(lyricsStatuses = mutableListOf(HttpStatusCode.NotFound))

        expectFailure<SpotifyLyricsNotFoundException> { fixture.api.getSyncedLyrics("track-id") }
    }

    @Test
    fun `rate limit has its own failure`() = runTest {
        val fixture = fixture(lyricsStatuses = mutableListOf(HttpStatusCode.TooManyRequests))

        expectFailure<SpotifyRateLimitException> { fixture.api.getSyncedLyrics("track-id") }
    }

    @Test
    fun `malformed successful payload is provider unavailable not no lyrics`() = runTest {
        val fixture = fixture(lyricsBody = "{not-json")

        expectFailure<SpotifyServiceException> { fixture.api.getSyncedLyrics("track-id") }
    }

    @Test
    fun `invalid lyrics session refreshes authenticated access once`() = runTest {
        val fixture = fixture(
            lyricsStatuses = mutableListOf(HttpStatusCode.BadRequest, HttpStatusCode.OK)
        )

        assertEquals("[00:01.23]First line", fixture.api.getSyncedLyrics("track-id"))
        assertEquals(0, fixture.requests.count { it.url.host == "clienttoken.spotify.com" })
        assertEquals(2, fixture.requests.count { it.url.encodedPath == "/api/token" })
        assertEquals(2, fixture.requests.count { it.url.host == "spclient.wg.spotify.com" })
    }

    @Test
    fun `expired access token is refreshed from the in-memory cache`() = runTest {
        var now = 1_700_000_000_000L
        val fixture = fixture(
            accessExpiresAt = now + 60_000,
            clock = { now },
        )

        fixture.api.getSyncedLyrics("track-id")
        now += 31_000
        fixture.api.getSyncedLyrics("track-id")

        assertEquals(2, fixture.requests.count { it.url.encodedPath == "/api/token" })
    }

    @Test
    fun `private cookie is sent only to Spotify token endpoint and never exposed in errors`() = runTest {
        val fixture = fixture(lyricsStatuses = mutableListOf(HttpStatusCode.NotFound))
        val error = expectFailure<SpotifyLyricsNotFoundException> { fixture.api.getSyncedLyrics("track-id") }

        val cookieRequests = fixture.requests.filter { it.headers[HttpHeaders.Cookie]?.contains("private-cookie") == true }
        assertEquals(1, cookieRequests.size)
        assertEquals("/api/token", cookieRequests.single().url.encodedPath)
        assertFalse(error.toString().contains("private-cookie"))
        assertTrue(fixture.requests.filterNot { it.url.encodedPath == "/api/token" }
            .none { it.headers.toString().contains("private-cookie") })
    }

    @Test
    fun `bundled secret keeps authentication available when remote secret sources are offline`() = runTest {
        val fixture = fixture(secretSourcesUnavailable = true)

        assertEquals("[00:01.23]First line", fixture.api.getSyncedLyrics("track-id"))
    }

    @Test
    fun `Spotify search accepts minimal track data and sends plain search text`() = runTest {
        val fixture = fixture(
            searchBody = """{"data":{"searchV2":{"tracksV2":{"items":[{"item":{"data":{"id":"spotify-track","name":"Bling-Bang-Bang-Born","albumOfTrack":{"coverArt":{"sources":[{"url":"https://image.test/cover.jpg"}]}},"artists":{"items":[{"profile":{"name":"Creepy Nuts"}}]}}}}]}}}}""",
        )

        val result = fixture.api.getSongInfo(SongInfo("Bling-Bang-Bang-Born", "Creepy Nuts"))

        assertEquals("spotify-track", result.spotifyID)
        assertEquals("Creepy Nuts", result.artistName)
        val searchRequest = fixture.requests.single { it.url.host == "api-partner.spotify.com" }
        assertTrue(searchRequest.url.parameters["variables"]!!.contains("Bling-Bang-Bang-Born Creepy Nuts"))
        assertFalse(searchRequest.url.parameters["variables"]!!.contains("Bling-Bang-Bang-Born+Creepy+Nuts"))
    }

    @Test
    fun `client token HTTP failure exposes a safe stage and status code`() = runTest {
        val fixture = fixture(clientTokenStatus = HttpStatusCode.ServiceUnavailable)

        val error = expectFailure<SpotifyServiceException> {
            fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))
        }

        assertEquals(SpotifyOperation.CLIENT_TOKEN, error.diagnostic.operation)
        assertEquals(SpotifyFailureKind.HTTP, error.diagnostic.kind)
        assertEquals("SPOTIFY-CLIENT-503", error.diagnostic.code)
    }

    @Test
    fun `malformed search response is identified as search parsing`() = runTest {
        val fixture = fixture(searchBody = "{not-json")

        val error = expectFailure<SpotifyServiceException> {
            fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))
        }

        assertEquals("SPOTIFY-SEARCH-PARSE", error.diagnostic.code)
        assertEquals("RESPONSE_PARSE", error.diagnostic.stage)
    }

    @Test
    fun `search HTTP failure preserves its status without exposing secrets`() = runTest {
        val fixture = fixture(searchStatus = HttpStatusCode.BadRequest)

        val error = expectFailure<SpotifyServiceException> {
            fixture.api.getSongInfo(SongInfo("private-song", "private-artist"))
        }
        val report = error.diagnostic.toReport("4.3.3-diagnostic")

        assertEquals("SPOTIFY-SEARCH-400", error.diagnostic.code)
        assertFalse(report.contains("private-cookie"))
        assertFalse(report.contains("access-token"))
        assertFalse(report.contains("client-token"))
        assertFalse(report.contains("private-song"))
        assertFalse(report.contains("private-artist"))
        assertFalse(report.contains("Authorization", ignoreCase = true))
    }

    @Test
    fun `lyrics server failure identifies the lyrics stage`() = runTest {
        val fixture = fixture(lyricsStatuses = mutableListOf(HttpStatusCode.InternalServerError))

        val error = expectFailure<SpotifyServiceException> { fixture.api.getSyncedLyrics("track-id") }

        assertEquals("SPOTIFY-LYRICS-500", error.diagnostic.code)
    }

    @Test
    fun `connection test reports each completed Spotify operation`() = runTest {
        val fixture = fixture(
            searchBody = """{"data":{"searchV2":{"tracksV2":{"items":[{"item":{"data":{"id":"spotify-track","name":"Diagnostic Track","albumOfTrack":{"coverArt":{"sources":[]}},"artists":{"items":[{"profile":{"name":"Diagnostic Artist"}}]}}}}]}}}}""",
        )

        val report = fixture.api.runConnectionTest()

        assertNull(report.failure)
        assertEquals(
            listOf(
                SpotifyOperation.BOOTSTRAP,
                SpotifyOperation.SERVER_TIME,
                SpotifyOperation.ACCESS_TOKEN,
                SpotifyOperation.CLIENT_TOKEN,
                SpotifyOperation.TRACK_SEARCH,
                SpotifyOperation.LYRICS_REQUEST,
            ),
            report.passed,
        )
    }

    @Test
    fun `network failure identifies the first unreachable Spotify stage`() = runTest {
        val fixture = fixture(networkFailureHost = "open.spotify.com")

        val error = expectFailure<SpotifyServiceException> {
            fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))
        }

        assertEquals("SPOTIFY-BOOTSTRAP-NETWORK", error.diagnostic.code)
    }

    @Test
    fun `bootstrap server time and access HTTP failures retain their boundaries`() = runTest {
        val bootstrapError = expectFailure<SpotifyServiceException> {
            fixture(bootstrapStatus = HttpStatusCode.InternalServerError).api.ensureAuthenticated()
        }
        val timeError = expectFailure<SpotifyServiceException> {
            fixture(serverTimeStatus = HttpStatusCode.ServiceUnavailable).api.ensureAuthenticated()
        }
        val accessError = expectFailure<SpotifySessionExpiredException> {
            fixture(accessStatus = HttpStatusCode.Forbidden).api.ensureAuthenticated()
        }

        assertEquals("SPOTIFY-BOOTSTRAP-500", bootstrapError.diagnostic.code)
        assertEquals("SPOTIFY-TIME-503", timeError.diagnostic.code)
        assertEquals("SPOTIFY-ACCESS-403", accessError.diagnostic.code)
    }

    @Test
    fun `persistent invalid client token is safe and distinct`() = runTest {
        val fixture = fixture(
            lyricsStatuses = mutableListOf(HttpStatusCode.BadRequest, HttpStatusCode.BadRequest),
        )

        val error = expectFailure<SpotifyServiceException> { fixture.api.getSyncedLyrics("track-id") }

        assertEquals("SPOTIFY-LYRICS-400-INVALID-CLIENT", error.diagnostic.code)
        assertEquals(SpotifyFailureKind.INVALID_CLIENT_TOKEN, error.diagnostic.kind)
    }

    @Test
    fun `client token parser accepts omitted refresh time and string expiry`() = runTest {
        val fixture = fixture(
            clientTokenBodies = mutableListOf(
                """{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"client-token","expires_after_seconds":"3600"}}"""
            ),
            searchBody = """{"data":{"searchV2":{"tracksV2":{"items":[{"item":{"data":{"id":"spotify-track","name":"B-Side Blues","albumOfTrack":{"coverArt":{"sources":[]}},"artists":{"items":[{"profile":{"name":"OFFICIAL HIGE DANDISM"}}]}}}}]}}}}""",
        )

        val song = fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))

        assertEquals("spotify-track", song.spotifyID)
    }

    @Test
    fun `hash cash client token challenge is solved and exchanged for a token`() = runTest {
        val fixture = fixture(
            clientTokenBodies = mutableListOf(
                """{"response_type":"RESPONSE_CHALLENGES_RESPONSE","challenges":{"state":"challenge-state","challenges":[{"type":"CHALLENGE_HASH_CASH","evaluate_hashcash_parameters":{"length":4,"prefix":"00112233445566778899AABBCCDDEEFF"}}]}}""",
                GRANTED_CLIENT_TOKEN,
            ),
            searchBody = """{"data":{"searchV2":{"tracksV2":{"items":[{"item":{"data":{"id":"spotify-track","name":"B-Side Blues","albumOfTrack":{"coverArt":{"sources":[]}},"artists":{"items":[{"profile":{"name":"OFFICIAL HIGE DANDISM"}}]}}}}]}}}}""",
        )

        val song = fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))

        assertEquals("spotify-track", song.spotifyID)
        assertEquals(2, fixture.requests.count { it.url.host == "clienttoken.spotify.com" })
        val answer = fixture.requests
            .filter { it.url.host == "clienttoken.spotify.com" }
            .last().body as TextContent
        assertTrue(answer.text.contains("REQUEST_CHALLENGE_ANSWERS_REQUEST"))
        assertTrue(answer.text.contains("challenge-state"))
        assertTrue(answer.text.contains("CHALLENGE_HASH_CASH"))
        assertFalse(answer.text.contains("private-cookie"))
        assertFalse(answer.text.contains("access-token"))
    }

    @Test
    fun `hash cash solver returns an uppercase suffix satisfying Spotify proof of work`() {
        val prefixHex = "00112233445566778899AABBCCDDEEFF"
        val suffixHex = solveSpotifyHashCash(prefixHex, 8)
        val prefix = prefixHex.hexBytes()
        val suffix = suffixHex.hexBytes()
        val digest = MessageDigest.getInstance("SHA-1").digest(prefix + suffix)
        val proof = ByteBuffer.wrap(digest, 12, Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long

        assertEquals(32, suffixHex.length)
        assertEquals(suffixHex.uppercase(), suffixHex)
        assertTrue(java.lang.Long.numberOfTrailingZeros(proof) >= 8)
    }

    @Test
    fun `unrecognized client token response falls back to authenticated Spotify search`() = runTest {
        val fixture = fixture(
            clientTokenBodies = mutableListOf("""{"unexpected":"safe-shape"}"""),
            officialSearchBody = """{"tracks":{"items":[{"id":"spotify-track","name":"B-Side Blues","artists":[{"name":"OFFICIAL HIGE DANDISM"}],"album":{"images":[{"url":"https://image.test/cover.jpg"}]}}]}}""",
        )

        val song = fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))

        assertEquals("spotify-track", song.spotifyID)
        val officialRequest = fixture.requests.single { it.url.host == "api.spotify.com" }
        assertEquals("track", officialRequest.url.parameters["type"])
        assertEquals("Bearer access-token", officialRequest.headers[HttpHeaders.Authorization])
        assertNull(officialRequest.headers["Client-Token"])
    }

    @Test
    fun `search 404 remains provider failure rather than no lyrics`() = runTest {
        val fixture = fixture(searchStatus = HttpStatusCode.NotFound)

        val error = expectFailure<SpotifyServiceException> {
            fixture.api.getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))
        }

        assertEquals("SPOTIFY-SEARCH-404", error.diagnostic.code)
    }

    private fun fixture(
        lyricsStatuses: MutableList<HttpStatusCode> = mutableListOf(HttpStatusCode.OK),
        lyricsBody: String = """{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"1230","words":"First line"}]}}""",
        isAnonymous: Boolean = false,
        initialCookie: String? = "private-cookie",
        accessExpiresAt: Long = 4_102_444_800_000,
        clock: () -> Long = { 1_700_000_000_000L },
        secretSourcesUnavailable: Boolean = false,
        searchBody: String = """{"data":{"searchV2":{"tracksV2":{"items":[]}}}}""",
        searchStatus: HttpStatusCode = HttpStatusCode.OK,
        clientTokenStatus: HttpStatusCode = HttpStatusCode.OK,
        clientTokenBodies: MutableList<String> = mutableListOf(GRANTED_CLIENT_TOKEN),
        officialSearchBody: String = """{"tracks":{"items":[]}}""",
        bootstrapStatus: HttpStatusCode = HttpStatusCode.OK,
        serverTimeStatus: HttpStatusCode = HttpStatusCode.OK,
        accessStatus: HttpStatusCode = HttpStatusCode.OK,
        networkFailureHost: String? = null,
    ): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val webConfig = Base64.getEncoder().encodeToString("""{"clientVersion":"1.3.2.test"}""".toByteArray())
        val engine = MockEngine { request ->
            requests += request
            if (request.url.host == networkFailureHost) {
                throw IOException("Simulated private-cookie access-token client-token failure")
            }
            when {
                request.url.host == "raw.githubusercontent.com" -> if (secretSourcesUnavailable) {
                    respond("", HttpStatusCode.ServiceUnavailable)
                } else jsonResponse("""[{"version":61,"secret":[44,55,47,42]}]""")
                request.url.host == "code.thetadev.de" -> respond("", HttpStatusCode.ServiceUnavailable)
                request.url.host == "open.spotify.com" && request.url.encodedPath == "/" -> respond(
                    content = if (bootstrapStatus == HttpStatusCode.OK) {
                        """<script id="appServerConfig" type="text/plain">$webConfig</script>"""
                    } else "{}",
                    status = bootstrapStatus,
                    headers = headersOf(HttpHeaders.SetCookie, "sp_t=device-id; Path=/")
                )
                request.url.encodedPath == "/api/server-time" -> respond(
                    content = if (serverTimeStatus == HttpStatusCode.OK) """{"serverTime":1700000000}""" else "{}",
                    status = serverTimeStatus,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath == "/api/token" -> respond(
                    content = if (accessStatus == HttpStatusCode.OK) {
                        """{"clientId":"client-id","accessToken":"access-token","accessTokenExpirationTimestampMs":$accessExpiresAt,"isAnonymous":$isAnonymous}"""
                    } else "{}",
                    status = accessStatus,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.host == "clienttoken.spotify.com" -> respond(
                    content = if (clientTokenStatus == HttpStatusCode.OK) {
                        if (clientTokenBodies.size > 1) clientTokenBodies.removeAt(0) else clientTokenBodies.first()
                    } else "{}",
                    status = clientTokenStatus,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.host == "api-partner.spotify.com" -> respond(
                    content = if (searchStatus == HttpStatusCode.OK) searchBody else "{}",
                    status = searchStatus,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.host == "api.spotify.com" -> jsonResponse(officialSearchBody)
                request.url.host == "spclient.wg.spotify.com" -> {
                    val status = if (lyricsStatuses.size > 1) lyricsStatuses.removeAt(0) else lyricsStatuses.first()
                    respond(
                        content = if (status == HttpStatusCode.OK) lyricsBody else "{}",
                        status = status,
                        headers = if (status == HttpStatusCode.BadRequest) {
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "client-token-error" to listOf("INVALID_CLIENTTOKEN"),
                            )
                        } else headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Ktor.json) }
        }
        val store = FakeSpotifyCredentialStore(initialCookie)
        return Fixture(SpotifyAPI(store, client, clock), store, requests)
    }

    private fun MockRequestHandleScope.jsonResponse(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private companion object {
        const val GRANTED_CLIENT_TOKEN =
            """{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"client-token","expires_after_seconds":3600,"refresh_after_seconds":1800}}"""
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private data class Fixture(
    val api: SpotifyAPI,
    val store: FakeSpotifyCredentialStore,
    val requests: List<HttpRequestData>,
)

private class FakeSpotifyCredentialStore(initialValue: String?) : SpotifyCredentialStore {
    private var value = initialValue
    override fun read(): String? = value
    override fun save(value: String) { this.value = value }
    override fun clear() { value = null }
}

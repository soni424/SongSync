package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.ServerTimeResponse
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyClientTokenResponse
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyLyricsResponse
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyWebPlayerConfig
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.TrackSearchResult
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.WebPlayerTokenResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.SpotifyAuthenticationRequiredException
import pl.lambada.songsync.util.SpotifyConnectionTestReport
import pl.lambada.songsync.util.SpotifyDiagnostic
import pl.lambada.songsync.util.SpotifyFailureKind
import pl.lambada.songsync.util.SpotifyLyricsNotFoundException
import pl.lambada.songsync.util.SpotifyOperation
import pl.lambada.songsync.util.SpotifyProviderException
import pl.lambada.songsync.util.SpotifyRateLimitException
import pl.lambada.songsync.util.SpotifyServiceException
import pl.lambada.songsync.util.SpotifySessionExpiredException
import pl.lambada.songsync.util.networking.Ktor
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class SecretData(val secret: List<Int>, val version: Int)

/** A single authenticated, in-memory Spotify web-player session. */
class SpotifyAPI(
    private val credentialStore: SpotifyCredentialStore,
    private val client: HttpClient = Ktor.client,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val webPlayerUrl = "https://open.spotify.com/"
    private val searchUrl = "https://api-partner.spotify.com/pathfinder/v1/query"
    private val requestHeaders = mapOf(
        HttpHeaders.UserAgent to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        HttpHeaders.Origin to "https://open.spotify.com",
        HttpHeaders.Referrer to "https://open.spotify.com/",
        HttpHeaders.Accept to "application/json",
        HttpHeaders.AcceptLanguage to "en",
        "App-Platform" to "WebPlayer",
    )

    private var totpGenerator: TimeBasedOneTimePasswordGenerator? = null
    private var totpVersion = 0
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private var clientToken: String? = null
    private var clientTokenRefreshAt = 0L
    private var clientId: String? = null
    private var clientVersion: String? = null
    private var deviceId: String? = null

    fun hasCookie(): Boolean = !credentialStore.read().isNullOrBlank()

    suspend fun verifyAndSaveCookie(input: String) {
        val cookie = normalizeCookie(input)
        invalidateSession()
        createAccessSession(cookie)
        credentialStore.save(cookie)
    }

    fun clearCookie() {
        credentialStore.clear()
        invalidateSession()
    }

    suspend fun ensureAuthenticated() {
        val cookie = storedCookie()
        ensureAccessSession(cookie)
    }

    suspend fun runConnectionTest(): SpotifyConnectionTestReport {
        invalidateSession()
        return try {
            getSongInfo(SongInfo("B-Side Blues", "OFFICIAL HIGE DANDISM"))
            getSyncedLyrics(CONNECTION_TEST_LYRICS_TRACK_ID)
            SpotifyConnectionTestReport(CONNECTION_TEST_OPERATIONS)
        } catch (error: SpotifyProviderException) {
            val passed = if (error is SpotifyAuthenticationRequiredException) {
                emptyList()
            } else {
                CONNECTION_TEST_OPERATIONS.takeWhile { it != error.diagnostic.operation }
            }
            SpotifyConnectionTestReport(passed, error)
        } catch (_: Exception) {
            val error = SpotifyServiceException(
                SpotifyDiagnostic(SpotifyOperation.BOOTSTRAP, SpotifyFailureKind.UNEXPECTED)
            )
            SpotifyConnectionTestReport(emptyList(), error)
        }
    }

    suspend fun getSyncedLyrics(trackId: String): String {
        var response = lyricsRequest(trackId)
        if ((response.status == HttpStatusCode.BadRequest &&
                response.headers["client-token-error"] == "INVALID_CLIENTTOKEN") ||
            response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
        ) {
            invalidateSession()
            response = lyricsRequest(trackId)
        }
        return when (response.status) {
            HttpStatusCode.OK -> formatSpotifyLyrics(
                decodeOrServiceFailure(response, SpotifyOperation.LYRICS_REQUEST)
            )
            HttpStatusCode.NotFound -> throw SpotifyLyricsNotFoundException(diagnosticFor(response, SpotifyOperation.LYRICS_REQUEST, SpotifyFailureKind.NO_LYRICS))
            HttpStatusCode.TooManyRequests -> throw SpotifyRateLimitException(diagnosticFor(response, SpotifyOperation.LYRICS_REQUEST, SpotifyFailureKind.RATE_LIMITED))
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw SpotifySessionExpiredException(diagnosticFor(response, SpotifyOperation.LYRICS_REQUEST, SpotifyFailureKind.AUTHENTICATION))
            else -> throw SpotifyServiceException(diagnosticFor(response, SpotifyOperation.LYRICS_REQUEST))
        }
    }

    suspend fun getSongInfo(query: SongInfo, offset: Int? = 0): SongInfo {
        val searchTerm = listOfNotNull(query.songName, query.artistName)
            .joinToString(" ")
            .trim()
        if (searchTerm.isBlank()) throw EmptyQueryException()
        val variables = buildJsonObject {
            put("searchTerm", searchTerm)
            put("offset", offset ?: 0)
            put("limit", 1)
            put("numberOfTopResults", 20)
            put("includeAudiobooks", false)
        }.toString()
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"1d021289df50166c61630e02f002ec91182b518e56bcd681ac6b0640390c0245"}}"""
        var activeSession = session()
        var response = searchRequest(activeSession, variables, extensions)
        if ((response.status == HttpStatusCode.BadRequest &&
                response.headers["client-token-error"] == "INVALID_CLIENTTOKEN") ||
            response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
        ) {
            invalidateSession()
            activeSession = session()
            response = searchRequest(activeSession, variables, extensions)
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw SpotifyRateLimitException(diagnosticFor(response, SpotifyOperation.TRACK_SEARCH, SpotifyFailureKind.RATE_LIMITED))
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            invalidateSession()
            throw SpotifySessionExpiredException(diagnosticFor(response, SpotifyOperation.TRACK_SEARCH, SpotifyFailureKind.AUTHENTICATION))
        }
        if (response.status != HttpStatusCode.OK) {
            throw SpotifyServiceException(diagnosticFor(response, SpotifyOperation.TRACK_SEARCH))
        }
        val result: TrackSearchResult = decodeOrServiceFailure(response, SpotifyOperation.TRACK_SEARCH)
        val track = result.data.searchV2.tracksV2.items.firstOrNull()?.item?.data ?: throw NoTrackFoundException()
        return SongInfo(
            songName = track.name,
            artistName = track.artists.items.joinToString(", ") { it.profile.name },
            songLink = "https://open.spotify.com/track/${track.id}",
            albumCoverLink = track.albumOfTrack.coverArt.sources.firstOrNull()?.url,
            spotifyID = track.id,
        )
    }

    private suspend fun searchRequest(
        activeSession: Session,
        variables: String,
        extensions: String,
    ): HttpResponse = spotifyRequest(SpotifyOperation.TRACK_SEARCH) {
        client.get(searchUrl) {
            parameter("operationName", "searchTracks")
            parameter("variables", variables)
            parameter("extensions", extensions)
            header(HttpHeaders.Authorization, "Bearer ${activeSession.accessToken}")
            header("Client-Token", activeSession.clientToken)
            header("Spotify-App-Version", activeSession.clientVersion)
            commonHeaders()
        }
    }

    private suspend fun lyricsRequest(trackId: String): HttpResponse {
        val activeSession = session()
        return spotifyRequest(SpotifyOperation.LYRICS_REQUEST) {
            client.get("https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId") {
            parameter("format", "json")
            parameter("vocalRemoval", "false")
            parameter("market", "from_token")
            header(HttpHeaders.Authorization, "Bearer ${activeSession.accessToken}")
            header("Client-Token", activeSession.clientToken)
            header("Spotify-App-Version", activeSession.clientVersion)
            commonHeaders()
        }
        }
    }

    private suspend fun session(): Session {
        val cookie = storedCookie()
        ensureAccessSession(cookie)
        if (clientToken == null || clock() >= clientTokenRefreshAt) createClientToken()
        return Session(accessToken!!, clientToken!!, clientVersion!!)
    }

    private fun storedCookie(): String = credentialStore.read()?.takeIf { it.isNotBlank() }
        ?: throw SpotifyAuthenticationRequiredException()

    private suspend fun ensureAccessSession(cookie: String) {
        if (accessToken == null || clock() >= accessTokenExpiresAt ||
            clientId == null || clientVersion == null || deviceId == null
        ) createAccessSession(cookie)
    }

    private suspend fun createAccessSession(cookie: String) {
        val bootstrap = spotifyRequest(SpotifyOperation.BOOTSTRAP) {
            client.get(webPlayerUrl) { commonHeaders() }
        }
        if (bootstrap.status == HttpStatusCode.TooManyRequests) {
            throw SpotifyRateLimitException(diagnosticFor(bootstrap, SpotifyOperation.BOOTSTRAP, SpotifyFailureKind.RATE_LIMITED))
        }
        if (bootstrap.status != HttpStatusCode.OK) {
            throw SpotifyServiceException(diagnosticFor(bootstrap, SpotifyOperation.BOOTSTRAP))
        }
        val html = bootstrap.bodyAsText()
        val encodedConfig = Regex("id=[\\\"']appServerConfig[\\\"'][^>]*>([^<]+)")
            .find(html)?.groupValues?.get(1) ?: throw parseFailure(SpotifyOperation.BOOTSTRAP)
        val configJson = try {
            encodedConfig.trim().decodeBase64Bytes().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw parseFailure(SpotifyOperation.BOOTSTRAP)
        }
        val config = try {
            Ktor.json.decodeFromString<SpotifyWebPlayerConfig>(configJson)
        } catch (_: Exception) {
            throw parseFailure(SpotifyOperation.BOOTSTRAP)
        }
        val spotifyDevice = bootstrap.headers.getAll(HttpHeaders.SetCookie)
            ?.asSequence()
            ?.mapNotNull { Regex("(?:^|;\\s*)sp_t=([^;]+)").find(it)?.groupValues?.get(1) }
            ?.firstOrNull()
            ?: UUID.randomUUID().toString().replace("-", "")

        val (timestamp, totp) = getTimestampAndTotp()
        val tokenResponse = spotifyRequest(SpotifyOperation.ACCESS_TOKEN) {
            client.get(webPlayerUrl + "api/token") {
                commonHeaders()
                header(HttpHeaders.Cookie, "sp_dc=$cookie; sp_t=$spotifyDevice")
                parameter("reason", "init")
                parameter("productType", "web-player")
                parameter("totp", totp)
                parameter("totpServer", totp)
                parameter("totpVer", totpVersion)
                parameter("ts", timestamp)
            }
        }
        if (tokenResponse.status == HttpStatusCode.Unauthorized || tokenResponse.status == HttpStatusCode.Forbidden) {
            throw SpotifySessionExpiredException(diagnosticFor(tokenResponse, SpotifyOperation.ACCESS_TOKEN, SpotifyFailureKind.AUTHENTICATION))
        }
        if (tokenResponse.status == HttpStatusCode.TooManyRequests) {
            throw SpotifyRateLimitException(diagnosticFor(tokenResponse, SpotifyOperation.ACCESS_TOKEN, SpotifyFailureKind.RATE_LIMITED))
        }
        if (tokenResponse.status != HttpStatusCode.OK) {
            throw SpotifyServiceException(diagnosticFor(tokenResponse, SpotifyOperation.ACCESS_TOKEN))
        }
        val webToken = try {
            Ktor.json.decodeFromString<WebPlayerTokenResponse>(tokenResponse.bodyAsText())
        } catch (_: Exception) {
            throw SpotifySessionExpiredException(parseDiagnostic(SpotifyOperation.ACCESS_TOKEN))
        }
        if (webToken.isAnonymous) {
            throw SpotifySessionExpiredException(
                SpotifyDiagnostic(SpotifyOperation.ACCESS_TOKEN, SpotifyFailureKind.AUTHENTICATION)
            )
        }

        accessToken = webToken.accessToken
        accessTokenExpiresAt = webToken.accessTokenExpirationTimestampMs - 30_000
        clientId = webToken.clientId
        clientVersion = config.clientVersion
        deviceId = spotifyDevice
        clientToken = null
        clientTokenRefreshAt = 0
    }

    private suspend fun createClientToken() {
        val tokenResponseForClient = spotifyRequest(SpotifyOperation.CLIENT_TOKEN) {
            client.post("https://clienttoken.spotify.com/v1/clienttoken") {
                commonHeaders()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("client_data", buildJsonObject {
                        put("client_version", clientVersion!!)
                        put("client_id", clientId!!)
                        put("js_sdk_data", buildJsonObject {
                            put("device_brand", "unknown")
                            put("device_model", "unknown")
                            put("os", "windows")
                            put("os_version", "NT 10.0")
                            put("device_id", deviceId!!)
                            put("device_type", "computer")
                        })
                    })
                }.toString())
            }
        }
        if (tokenResponseForClient.status == HttpStatusCode.TooManyRequests) {
            throw SpotifyRateLimitException(diagnosticFor(tokenResponseForClient, SpotifyOperation.CLIENT_TOKEN, SpotifyFailureKind.RATE_LIMITED))
        }
        if (tokenResponseForClient.status == HttpStatusCode.Unauthorized ||
            tokenResponseForClient.status == HttpStatusCode.Forbidden
        ) throw SpotifySessionExpiredException(diagnosticFor(tokenResponseForClient, SpotifyOperation.CLIENT_TOKEN, SpotifyFailureKind.AUTHENTICATION))
        if (tokenResponseForClient.status != HttpStatusCode.OK) {
            throw SpotifyServiceException(diagnosticFor(tokenResponseForClient, SpotifyOperation.CLIENT_TOKEN))
        }
        val clientTokenPayload = decodeOrServiceFailure<SpotifyClientTokenResponse>(tokenResponseForClient, SpotifyOperation.CLIENT_TOKEN)
        if (clientTokenPayload.responseType != "RESPONSE_GRANTED_TOKEN_RESPONSE") throw parseFailure(SpotifyOperation.CLIENT_TOKEN)
        val granted = clientTokenPayload.grantedToken ?: throw parseFailure(SpotifyOperation.CLIENT_TOKEN)

        clientToken = granted.token
        clientTokenRefreshAt = clock() + granted.refreshAfterSeconds.coerceAtLeast(30) * 1_000
    }

    private suspend fun getTimestampAndTotp(): Pair<Long, String> {
        if (totpGenerator == null) {
            val latest = loadLatestSecret()
            val secret = latest.secret.mapIndexed { index, value -> value xor ((index % 33) + 9) }
                .joinToString("")
                .toByteArray(StandardCharsets.UTF_8)
                .joinToString("") { "%02x".format(it) }
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            totpVersion = latest.version
            totpGenerator = TimeBasedOneTimePasswordGenerator(
                secret,
                TimeBasedOneTimePasswordConfig(30L, TimeUnit.SECONDS, 6, HmacAlgorithm.SHA1)
            )
        }
        val response = spotifyRequest(SpotifyOperation.SERVER_TIME) {
            client.get(webPlayerUrl + "api/server-time") { commonHeaders() }
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw SpotifyRateLimitException(diagnosticFor(response, SpotifyOperation.SERVER_TIME, SpotifyFailureKind.RATE_LIMITED))
        }
        if (response.status != HttpStatusCode.OK) {
            throw SpotifyServiceException(diagnosticFor(response, SpotifyOperation.SERVER_TIME))
        }
        val serverTime = decodeOrServiceFailure<ServerTimeResponse>(response, SpotifyOperation.SERVER_TIME).serverTime * 1_000
        return serverTime to totpGenerator!!.generate(serverTime)
    }

    private suspend fun loadLatestSecret(): SecretData {
        try {
            val response = client.get(PRIMARY_SECRET_URL)
            if (response.status == HttpStatusCode.OK) {
                Ktor.json.decodeFromString<List<SecretData>>(response.bodyAsText())
                    .maxByOrNull { it.version }
                    ?.let { return it }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Try the independent mirror below.
        }

        try {
            val response = client.get(MIRROR_SECRET_URL)
            if (response.status == HttpStatusCode.OK) {
                val secrets = Ktor.json.decodeFromString<Map<String, List<Int>>>(response.bodyAsText())
                secrets.maxByOrNull { it.key.toIntOrNull() ?: -1 }?.let { (version, secret) ->
                    return SecretData(secret = secret, version = version.toInt())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The bundled value keeps verification working during a temporary source outage.
        }

        return BUNDLED_SECRET
    }

    private fun normalizeCookie(input: String): String {
        val trimmed = input.trim()
        if ('\n' in trimmed || '\r' in trimmed) throw SpotifySessionExpiredException()
        val value = if (trimmed.startsWith("sp_dc=", ignoreCase = true)) {
            trimmed.substringAfter('=').substringBefore(';').trim()
        } else trimmed
        if (value.isBlank() || value.any { it.isWhitespace() }) throw SpotifySessionExpiredException()
        return value
    }

    private fun invalidateSession() {
        accessToken = null
        accessTokenExpiresAt = 0
        clientToken = null
        clientTokenRefreshAt = 0
        clientId = null
        clientVersion = null
        deviceId = null
    }

    private fun HttpRequestBuilder.commonHeaders() {
        requestHeaders.forEach { (key, value) -> header(key, value) }
    }

    private suspend inline fun <reified T> decodeOrServiceFailure(
        response: HttpResponse,
        operation: SpotifyOperation,
    ): T = try {
        Ktor.json.decodeFromString(response.bodyAsText())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw parseFailure(operation)
    }

    private suspend fun spotifyRequest(
        operation: SpotifyOperation,
        request: suspend () -> HttpResponse,
    ): HttpResponse = try {
        request()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw SpotifyServiceException(SpotifyDiagnostic(operation, SpotifyFailureKind.NETWORK))
    }

    private fun diagnosticFor(
        response: HttpResponse,
        operation: SpotifyOperation,
        kind: SpotifyFailureKind = if (
            response.headers["client-token-error"] == "INVALID_CLIENTTOKEN"
        ) SpotifyFailureKind.INVALID_CLIENT_TOKEN else SpotifyFailureKind.HTTP,
    ) = SpotifyDiagnostic(operation, kind, response.status.value)

    private fun parseDiagnostic(operation: SpotifyOperation) =
        SpotifyDiagnostic(operation, SpotifyFailureKind.PARSE)

    private fun parseFailure(operation: SpotifyOperation) =
        SpotifyServiceException(parseDiagnostic(operation))

    private data class Session(val accessToken: String, val clientToken: String, val clientVersion: String)

    private companion object {
        const val PRIMARY_SECRET_URL =
            "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretBytes.json"
        const val MIRROR_SECRET_URL =
            "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json"
        val BUNDLED_SECRET = SecretData(
            version = 61,
            secret = listOf(
                44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120,
                97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78,
            ),
        )
        val CONNECTION_TEST_OPERATIONS = listOf(
            SpotifyOperation.BOOTSTRAP,
            SpotifyOperation.SERVER_TIME,
            SpotifyOperation.ACCESS_TOKEN,
            SpotifyOperation.CLIENT_TOKEN,
            SpotifyOperation.TRACK_SEARCH,
            SpotifyOperation.LYRICS_REQUEST,
        )
        const val CONNECTION_TEST_LYRICS_TRACK_ID = "4Q0qVhFQa7j6jRKzo3HDmP"
    }
}

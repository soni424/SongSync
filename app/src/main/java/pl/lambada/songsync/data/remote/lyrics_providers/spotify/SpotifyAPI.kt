package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import android.util.Log
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pl.lambada.songsync.data.remote.lyrics_providers.requireProviderSuccess
import pl.lambada.songsync.data.remote.lyrics_providers.isLikelyTrackMatch
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.ServerTimeResponse
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.TrackSearchResult
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.WebPlayerTokenResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import java.io.FileNotFoundException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.Locale

@Serializable
data class SecretData(
    val secret: List<Int>,
    val version: Int
)

class SpotifyAPI {
    private val webPlayerURL = "https://open.spotify.com/"
    private val baseURL = "https://api-partner.spotify.com/pathfinder/v1/query"

    // TOTP variables
    private var totpSecret: ByteArray? = null
    private var totpVer: Int = 0
    private var totpGenerator: TimeBasedOneTimePasswordGenerator? = null

    // Request headers
    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Origin" to "https://open.spotify.com",
        "Referer" to "https://open.spotify.com/",
    )

    // Token data
    private var spotifyToken = ""
    private var tokenExpiresAt: Long = 0

    /**
     * Fetches secret data from GitHub and initializes TOTP
     */
    private suspend fun initializeTOTP() {
        if (totpGenerator != null) return

        try {
            val response = client.get("https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretBytes.json")
            response.requireProviderSuccess(Providers.SPOTIFY)
            val responseBody = response.bodyAsText(Charsets.UTF_8)
            val secretDataList = json.decodeFromString<List<SecretData>>(responseBody)
            
            val lastSecretData = secretDataList.last()
            
            totpSecret = toSecret(lastSecretData.secret)
            totpVer = lastSecretData.version
            
            totpGenerator = TimeBasedOneTimePasswordGenerator(
                totpSecret!!,
                TimeBasedOneTimePasswordConfig(
                    30L,
                    TimeUnit.SECONDS,
                    6,
                    HmacAlgorithm.SHA1
                )
            )
            
            Log.d("SpotifyAPI", "TOTP initialized with version: $totpVer")
        } catch (e: Exception) {
            Log.e("SpotifyAPI", "Failed to initialize TOTP", e)
            throw e
        }
    }

    /**
     * Converts secret data to ByteArray
     */
    private fun toSecret(data: List<Int>): ByteArray {
        val mappedData = data.mapIndexed { index, value -> 
            value xor ((index % 33) + 9)
        }
        
        val dataString = mappedData.joinToString("")
        val hexData = dataString.toByteArray(StandardCharsets.UTF_8)
            .joinToString("") { "%02x".format(it) }
        
        return hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Gets the server time from the Spotify API.
     * @return The server time in milliseconds.
     */
    private suspend fun getServerTime(): Long {
        val response = client.get(
            webPlayerURL + "api/server-time"
        ) {
            reqHeaders.forEach { (key, value) -> header(key, value) }
        }
        response.requireProviderSuccess(Providers.SPOTIFY)
        val body = response.bodyAsText(Charsets.UTF_8)
        val httpDateMillis = response.headers["Date"]?.let { date ->
            runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(date)?.time
            }.getOrNull()
        }
        return resolveSpotifyServerTimeMillis(body, httpDateMillis, System.currentTimeMillis())
    }

    /**
     * Generates a TOTP code using the server time.
     * @return A Pair containing the timestamp and the TOTP code.
     */
    private suspend fun getTsAndTOTP(): Pair<Long, String> {
        if (totpGenerator == null) {
            initializeTOTP()
        }
        
        val serverTime = getServerTime()
        return Pair(serverTime, totpGenerator!!.generate(serverTime))
    }

    /**
     * Refreshes the access token by sending a request to the Spotify API.
     * @param force If true, forces a token refresh even if the current token is still valid.
     */
    suspend fun refreshToken(force: Boolean = false) {
        if (force || spotifyToken.isBlank() || System.currentTimeMillis() >= tokenExpiresAt - 60_000) {
            val totp = getTsAndTOTP()
            val response = client.get(
                webPlayerURL + "api/token"
            ) {
                reqHeaders.forEach { (key, value) -> header(key, value) }
                parameter("reason", "init")
                parameter("productType", "mobile-web-player")
                parameter("ts", totp.first)
                parameter("totp", totp.second)
                parameter("totpVer", totpVer)
            }
            response.requireProviderSuccess(Providers.SPOTIFY)
            val responseBody = response.bodyAsText(Charsets.UTF_8)
            val json = json.decodeFromString<WebPlayerTokenResponse>(responseBody)

            this.spotifyToken = json.accessToken
            this.tokenExpiresAt = json.accessTokenExpirationTimestampMs
        }
    }

    /**
     * Gets song information from the Spotify API.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @param offset (optional) The offset used for trying to find a better match or searching again.
     * @return The SongInfo object containing the song information.
     */
    @Throws(UnknownHostException::class, FileNotFoundException::class, NoTrackFoundException::class)
    suspend fun getSongInfo(query: SongInfo, offset: Int? = 0): SongInfo {
        if (spotifyToken.isBlank() || System.currentTimeMillis() >= tokenExpiresAt - 60_000)
            refreshToken()

        val searchTerm = "${query.songName.orEmpty()} ${query.artistName.orEmpty()}".trim()

        if (searchTerm.isBlank())
            throw EmptyQueryException()

        val variables = json.encodeToString(buildJsonObject {
            put("searchTerm", searchTerm)
            put("offset", offset ?: 0)
            put("limit", 10)
            put("numberOfTopResults", 20)
            put("includeAudiobooks", false)
        })
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"1d021289df50166c61630e02f002ec91182b518e56bcd681ac6b0640390c0245"}}"""

        suspend fun search() = client.get(baseURL) {
            header("Authorization", "Bearer $spotifyToken")
            parameter("operationName", "searchTracks")
            parameter("variables", variables)
            parameter("extensions", extensions)
        }

        var response = search()
        if (response.status == HttpStatusCode.Unauthorized) {
            refreshToken(force = true)
            response = search()
        }
        response.requireProviderSuccess(Providers.SPOTIFY)
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        val json = json.decodeFromString<TrackSearchResult>(responseBody)
        if (json.data.searchV2.tracksV2.items.isEmpty())
           throw NoTrackFoundException()

        val trackItem = json.data.searchV2.tracksV2.items.firstOrNull { item ->
            val artists = item.item.data.artists.items.joinToString(", ") { it.profile.name }
            isLikelyTrackMatch(query.songName, query.artistName, item.item.data.name, artists)
        } ?: throw NoTrackFoundException()
        val track = trackItem.item.data

        val artists = track.artists.items.joinToString(", ") { it.profile.name }

        val albumArtURL = track.albumOfTrack.coverArt.sources[0].url

        val spotifyURL = "https://open.spotify.com/track/${track.id}"

        return SongInfo(
           track.name,
           artists,
           spotifyURL,
           albumArtURL
        )
    }
}

internal fun resolveSpotifyServerTimeMillis(
    body: String,
    httpDateMillis: Long?,
    deviceTimeMillis: Long,
): Long {
    val spotifyTime = runCatching {
        json.decodeFromString<ServerTimeResponse>(body).serverTime
    }.getOrNull()
    return spotifyTime
        ?.takeIf { it > 0 }
        ?.times(1000)
        ?: httpDateMillis
        ?: deviceTimeMillis
}

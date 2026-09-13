package pl.lambada.songsync.domain.model.lyrics_providers.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyLyricsResponse(
    val lyrics: SpotifyLyrics
)

@Serializable
data class SpotifyLyrics(
    val syncType: String,
    val lines: List<SpotifyLyricsLine>
)

@Serializable
data class SpotifyLyricsLine(
    val startTimeMs: String,
    val words: String
)

@Serializable
data class SpotifyClientTokenResponse(
    @SerialName("response_type")
    val responseType: String? = null,
    @SerialName("granted_token")
    val grantedToken: SpotifyGrantedToken? = null
)

@Serializable
data class SpotifyGrantedToken(
    val token: String,
    @SerialName("expires_after_seconds")
    val expiresAfterSeconds: Long,
    @SerialName("refresh_after_seconds")
    val refreshAfterSeconds: Long
)

@Serializable
data class SpotifyWebPlayerConfig(
    val clientVersion: String
)

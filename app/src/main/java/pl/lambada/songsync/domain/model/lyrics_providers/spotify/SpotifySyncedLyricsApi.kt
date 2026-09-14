package pl.lambada.songsync.domain.model.lyrics_providers.spotify

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
data class SpotifyWebPlayerConfig(
    val clientVersion: String
)

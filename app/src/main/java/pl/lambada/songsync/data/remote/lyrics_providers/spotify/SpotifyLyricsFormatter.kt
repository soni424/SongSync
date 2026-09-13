package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyLyricsResponse
import pl.lambada.songsync.util.SpotifyLyricsNotFoundException
import pl.lambada.songsync.util.SpotifyUnsyncedLyricsException

fun formatSpotifyLyrics(response: SpotifyLyricsResponse): String {
    val lines = response.lyrics.lines
    if (lines.none { it.words.isNotBlank() }) throw SpotifyLyricsNotFoundException()
    if (response.lyrics.syncType != "LINE_SYNCED") throw SpotifyUnsyncedLyricsException()
    val timedLines = lines.mapNotNull { line ->
        line.startTimeMs.toLongOrNull()?.takeIf { it >= 0 }?.let { it to line.words }
    }
    if (timedLines.isEmpty()) throw SpotifyUnsyncedLyricsException()
    return timedLines.joinToString("\n") { (milliseconds, words) ->
        val minutes = milliseconds / 60_000
        val seconds = (milliseconds % 60_000) / 1_000
        val centiseconds = (milliseconds % 1_000) / 10
        "[%02d:%02d.%02d]%s".format(minutes, seconds, centiseconds, words)
    }
}

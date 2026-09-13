package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyLyrics
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyLyricsLine
import pl.lambada.songsync.domain.model.lyrics_providers.spotify.SpotifyLyricsResponse
import pl.lambada.songsync.util.SpotifyLyricsNotFoundException
import pl.lambada.songsync.util.SpotifyUnsyncedLyricsException

class SpotifyLyricsFormatterTest {
    @Test
    fun `line-synced Spotify lyrics become LRC`() {
        val response = SpotifyLyricsResponse(
            lyrics = SpotifyLyrics(
                syncType = "LINE_SYNCED",
                lines = listOf(
                    SpotifyLyricsLine(startTimeMs = "1230", words = "First line"),
                    SpotifyLyricsLine(startTimeMs = "62000", words = "Second line")
                )
            )
        )

        assertEquals(
            "[00:01.23]First line\n[01:02.00]Second line",
            formatSpotifyLyrics(response)
        )
    }

    @Test(expected = SpotifyUnsyncedLyricsException::class)
    fun `text without usable timings is rejected as unsynchronized`() {
        formatSpotifyLyrics(
            SpotifyLyricsResponse(
                SpotifyLyrics(
                    syncType = "UNSYNCED",
                    lines = listOf(SpotifyLyricsLine(startTimeMs = "0", words = "Words without timing"))
                )
            )
        )
    }

    @Test(expected = SpotifyLyricsNotFoundException::class)
    fun `empty lyrics payload is reported as unavailable`() {
        formatSpotifyLyrics(SpotifyLyricsResponse(SpotifyLyrics("LINE_SYNCED", emptyList())))
    }
}

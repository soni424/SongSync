package pl.lambada.songsync.data.remote.lyrics_providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    @Test
    fun `matches normalized title and artist`() {
        assertTrue(isLikelyTrackMatch("Clock Hands", "Billyrrom", "clock hands", "BILLYRROM"))
        assertTrue(isLikelyTrackMatch("Song (feat. Guest)", "Artist feat. Guest", "Song", "Artist, Guest"))
        assertTrue(isLikelyTrackMatch("Song", "Artist", "Song", "Artist (feat. Guest)"))
    }

    @Test
    fun `rejects a different artist or title`() {
        assertFalse(isLikelyTrackMatch("Clock Hands", "Billyrrom", "Clock Hands", "Another Artist"))
        assertFalse(isLikelyTrackMatch("Clock Hands", "Billyrrom", "Different Song", "Billyrrom"))
    }
}

package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyServerTimeTest {
    @Test
    fun `uses Spotify server time when present`() {
        assertEquals(
            1_725_000_000_000L,
            resolveSpotifyServerTimeMillis(
                body = "{\"serverTime\":1725000000}",
                httpDateMillis = 1_700_000_000_000L,
                deviceTimeMillis = 1_600_000_000_000L
            )
        )
    }

    @Test
    fun `uses HTTP date when server time is missing`() {
        assertEquals(
            1_700_000_000_000L,
            resolveSpotifyServerTimeMillis(
                body = "{}",
                httpDateMillis = 1_700_000_000_000L,
                deviceTimeMillis = 1_600_000_000_000L
            )
        )
    }

    @Test
    fun `uses device time when response cannot be parsed`() {
        assertEquals(
            1_600_000_000_000L,
            resolveSpotifyServerTimeMillis(
                body = "not json",
                httpDateMillis = null,
                deviceTimeMillis = 1_600_000_000_000L
            )
        )
    }

    @Test
    fun `ignores an invalid zero server time`() {
        assertEquals(
            1_700_000_000_000L,
            resolveSpotifyServerTimeMillis(
                body = "{\"serverTime\":0}",
                httpDateMillis = 1_700_000_000_000L,
                deviceTimeMillis = 1_600_000_000_000L,
            )
        )
    }
}

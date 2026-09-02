package pl.lambada.songsync.data.remote.lyrics_providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lambada.songsync.util.Providers

class ProviderHttpTest {
    @Test
    fun `maps authentication responses`() {
        assertEquals(
            LyricsFailure.Authentication(Providers.SPOTIFY),
            providerFailureForStatus(401, null, Providers.SPOTIFY),
        )
    }

    @Test
    fun `preserves retry after for rate limits`() {
        assertEquals(
            LyricsFailure.RateLimited(Providers.LRCLIB, 12),
            providerFailureForStatus(429, "12", Providers.LRCLIB),
        )
    }

    @Test
    fun `parses date form retry after`() {
        assertEquals(
            LyricsFailure.RateLimited(Providers.LRCLIB, 30),
            providerFailureForStatus(
                429,
                "Thu, 01 Jan 1970 00:00:30 GMT",
                Providers.LRCLIB,
                nowMillis = 0,
            ),
        )
    }

    @Test
    fun `maps server errors to provider unavailable`() {
        assertEquals(
            LyricsFailure.ProviderUnavailable(Providers.QQMUSIC),
            providerFailureForStatus(503, null, Providers.QQMUSIC),
        )
    }

    @Test
    fun `accepts successful responses`() {
        assertNull(providerFailureForStatus(204, null, Providers.APPLE))
    }
}

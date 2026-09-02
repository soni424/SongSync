package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsUtilsTest {
    @Test
    fun `offset handles one two and three fractional timestamp digits`() {
        val lyrics = "[00:01.1]one\n[00:01.12]two\n[00:01.123]three"

        assertEquals(
            "[00:01.200]one\n[00:01.220]two\n[00:01.223]three",
            applyOffsetToLyrics(lyrics, 100)
        )
    }

    @Test
    fun `negative offsets clamp to zero`() {
        assertEquals("[00:00.000]line", applyOffsetToLyrics("[00:00.050]line", -100))
    }

    @Test
    fun `offset preserves timestamps longer than one hour`() {
        assertEquals(
            "[60:00.000]line",
            applyOffsetToLyrics("[59:59.000]line", 1000)
        )
    }
}

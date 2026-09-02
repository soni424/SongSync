package pl.lambada.songsync.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparisonTest {
    @Test
    fun `compares semantic version segments numerically`() {
        assertTrue(isNewerVersion("4.9", "4.10"))
        assertFalse(isNewerVersion("4.10", "4.9"))
        assertTrue(isNewerVersion("4.3.3", "v4.3.4"))
        assertTrue(isNewerVersion("4.3", "V4.3.1"))
        assertFalse(isNewerVersion("4.3.0", "4.3"))
        assertFalse(isNewerVersion("4.3.3", "4.3.3"))
    }
}

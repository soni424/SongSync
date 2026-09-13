package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSpotifyCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidSpotifyCredentialStore(context)

    @After
    fun cleanUp() = store.clear()

    @Test
    fun encryptedCookieCanBeSavedReadAndClearedWithoutPlaintextPreferences() {
        val plaintext = "test-secret-cookie-value"

        store.save(plaintext)

        assertEquals(plaintext, store.read())
        val storedValues = context.getSharedPreferences("spotify_credentials", 0).all.values
        assertFalse(storedValues.any { it.toString().contains(plaintext) })

        store.clear()
        assertNull(store.read())
        assertEquals(emptyMap<String, Any>(), context.getSharedPreferences("spotify_credentials", 0).all)
    }
}

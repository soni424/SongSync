package pl.lambada.songsync.ui.screens.home

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmbeddedLyricsTest {
    @Test fun detectsLyricsWrittenToAudioMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioFile = File(context.cacheDir, "lyrics-filter-test.mp3")
        try {
            // Three MPEG-1 Layer III frames give TagLib a small, self-contained MP3 fixture.
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFB.toByte()
            frame[2] = 0x90.toByte()
            frame[3] = 0x64.toByte()
            audioFile.writeBytes(frame + frame + frame)

            assertFalse(hasEmbeddedLyrics(audioFile.absolutePath))
            ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                val metadata = requireNotNull(TagLib.getMetadata(descriptor.dup().detachFd(), false))
                TagLib.savePropertyMap(
                    descriptor.dup().detachFd(),
                    metadata.propertyMap.apply { put("LYRICS", arrayOf("[00:01.00]Test lyric")) }
                )
            }
            assertTrue(hasEmbeddedLyrics(audioFile.absolutePath))
        } finally {
            audioFile.delete()
        }
    }
}

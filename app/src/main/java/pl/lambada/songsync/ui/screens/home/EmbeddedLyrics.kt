package pl.lambada.songsync.ui.screens.home

import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import java.io.File

/** Reads the same LYRICS property used when SongSync embeds lyrics. */
internal fun hasEmbeddedLyrics(filePath: String): Boolean = try {
    ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        // TagLib takes ownership of this descriptor; detach a duplicate so Android's
        // ParcelFileDescriptor does not try to close it a second time.
        val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), false)
        metadata?.propertyMap?.any { (key, values) ->
            key.equals("LYRICS", ignoreCase = true) && values.any(String::isNotBlank)
        } == true
    }
} catch (_: Exception) {
    false
}

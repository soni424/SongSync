package pl.lambada.songsync.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.lambada.songsync.domain.model.Song
import java.io.File

class SongListFilterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun embeddedLyricsAreExcluded() {
        val embedded = song("embedded.mp3")
        val missing = song("missing.mp3")

        assertEquals(
            listOf(missing),
            filterSongList(listOf(embedded, missing), true, emptyList()) {
                it == embedded.filePath
            }
        )
    }

    @Test fun sidecarLyricsAreExcluded() {
        val withSidecar = song("sidecar.mp3")
        File(temp.root, "sidecar.lrc").writeText("[00:01.00]lyrics")

        assertTrue(filterSongList(listOf(withSidecar), true, emptyList()) { false }.isEmpty())
    }

    @Test fun uppercaseSidecarLyricsAreExcluded() {
        val withSidecar = song("sidecar.mp3")
        File(temp.root, "sidecar.LRC").writeText("[00:01.00]lyrics")

        assertTrue(filterSongList(listOf(withSidecar), true, emptyList()) { false }.isEmpty())
    }

    @Test fun noFilterShowsAllSongs() {
        val song = song("song.mp3")
        assertEquals(listOf(song), displayedSongList(listOf(song), null, emptyList(), false))
    }

    @Test fun folderBlacklistCombinesWithMissingLyricsFilter() {
        val song = song("song.mp3")
        assertTrue(filterSongList(listOf(song), true, listOf(temp.root.absolutePath)) { false }.isEmpty())
    }

    @Test fun emptyFilteredResultDoesNotShowAllSongs() {
        val withLyrics = song("with-lyrics.mp3")
        assertTrue(displayedSongList(listOf(withLyrics), emptyList(), emptyList(), false).isEmpty())
    }

    private fun song(name: String) = Song(name, "Artist", null, File(temp.root, name).absolutePath)
}

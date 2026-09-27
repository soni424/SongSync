package pl.lambada.songsync.ui.screens.home

import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.util.ext.toLrcFile
import java.io.File

internal fun filterSongList(
    songs: List<Song>,
    hideLyrics: Boolean,
    blacklistedFolders: List<String>,
    hasEmbeddedLyrics: (String) -> Boolean,
): List<Song> = songs.filter { song ->
    val path = song.filePath ?: return@filter false
    val folderAllowed = File(path).parent?.let { it !in blacklistedFolders } ?: true
    val lyricsAllowed = !hideLyrics || (!hasSidecarLyrics(path) && !hasEmbeddedLyrics(path))
    folderAllowed && lyricsAllowed
}

private fun hasSidecarLyrics(path: String): Boolean {
    val lrcFile = path.toLrcFile() ?: return false
    return lrcFile.exists() || File(lrcFile.parentFile, "${lrcFile.nameWithoutExtension}.LRC").exists()
}

internal fun displayedSongList(
    allSongs: List<Song>,
    filteredSongs: List<Song>?,
    searchResults: List<Song>,
    searching: Boolean,
): List<Song> = when {
    searching -> searchResults
    else -> filteredSongs ?: allSongs
}

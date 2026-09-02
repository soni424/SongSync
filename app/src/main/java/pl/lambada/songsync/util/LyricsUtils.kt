package pl.lambada.songsync.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kyant.taglib.TagLib
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsFailure
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsLookupOutcome
import pl.lambada.songsync.data.remote.lyrics_providers.ProviderResult
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.util.ext.sanitize
import pl.lambada.songsync.util.ext.toLrcFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun generateLrcContent(
    song: SongInfo,
    lyrics: String,
    generatedUsingString: String,
    offset: Int = 0,
    directOffset: Boolean
): String {
    val offsetSign = if (offset >= 0) "+" else ""
    val offsetStr = if (!directOffset) "[offset:${offsetSign}${offset}]\n" else ""
    val lyrics = if (directOffset && offset != 0) applyOffsetToLyrics(lyrics, offset) else lyrics

    return "[ti:${song.songName}]\n" +
        "[ar:${song.artistName}]\n" +
        offsetStr +
        "[by:$generatedUsingString]\n" +
        lyrics
}

fun newLyricsFilePath(filePath: String?, song: SongInfo): File {
    return if (filePath == null || filePath.isEmpty()) {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SongSync/${song.songName} - ${song.artistName}.lrc"
        ).sanitize()
    } else {
        filePath.toLrcFile()!!
    }
}

fun writeLyricsToFile(
    file: File?,
    lrcContent: String,
    context: Context,
    song: Song,
    sdCardPath: String?
): Boolean {
    if (file == null) return false
    return try {
        file.writeText(lrcContent)
        file.isFile
    } catch (e: FileNotFoundException) {
        handleFileNotFoundException(context, song, file, lrcContent, sdCardPath)
    } catch (e: Exception) {
        false
    }
}

fun handleFileNotFoundException(
    context: Context,
    song: Song,
    file: File?,
    lrc: String,
    sdCardPath: String?
): Boolean {
    val songPath = song.filePath ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !songPath.contains("/storage/emulated/0")) {
        val externalCache = context.externalCacheDirs.getOrNull(1) ?: return false
        val sd = externalCache.absolutePath.substringBefore("/Android/data")
        val path = file?.absolutePath?.substringAfter(sd)?.split("/")?.dropLast(1) ?: return false
        var sdCardFiles = DocumentFile.fromTreeUri(context, Uri.parse(sdCardPath))
        for (element in path) {
            val currentDirectory = sdCardFiles ?: return false
            for (sdCardFile in currentDirectory.listFiles()) {
                if (sdCardFile.name == element) {
                    sdCardFiles = sdCardFile
                }
            }
        }
        sdCardFiles?.listFiles()?.firstOrNull { it.name == file.name }?.delete()
        val destination = sdCardFiles?.createFile("text/lrc", file.name) ?: return false
        context.contentResolver.openOutputStream(destination.uri)?.use { outputStream ->
            outputStream.write(lrc.toByteArray())
        } ?: return false
        return destination.exists()
    }
    return false
}

@SuppressLint("Range")
fun getFileDescriptorFromPath(
    context: Context, filePath: String, mode: String = "r"
): ParcelFileDescriptor? {
    val resolver = context.contentResolver
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(MediaStore.Files.FileColumns._ID)
    val selection = "${MediaStore.Files.FileColumns.DATA}=?"
    val selectionArgs = arrayOf(filePath)

    return resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val fileId = cursor.getInt(cursor.getColumnIndex(MediaStore.Files.FileColumns._ID))
            if (fileId != -1) {
                val fileUri = Uri.withAppendedPath(uri, fileId.toString())
                try {
                    resolver.openFileDescriptor(fileUri, mode)
                } catch (e: FileNotFoundException) {
                    Log.e("LyricsFetchViewModel", "File not found: ${e.message}")
                    null
                }
            } else null
        } else null
    }
}

fun embedLyricsInFile(
    context: Context,
    filePath: String,
    lyrics: String,
    securityExceptionHandler: (PendingIntent) -> Unit = {}
): Boolean {
    return try {
        val fd = getFileDescriptorFromPath(context, filePath, mode = "w")
            ?: throw IllegalStateException("File descriptor is null")

        val fileDescriptor = fd.dup().detachFd()
        val metadata = TagLib.getMetadata(fileDescriptor, false) ?: error("Metadata is null")

        TagLib.savePropertyMap(
            fd.dup().detachFd(),
            propertyMap = metadata.propertyMap.apply { put("LYRICS", arrayOf(lyrics)) }
        )

        true
    } catch (securityException: SecurityException) {
        handleSecurityException(securityException, securityExceptionHandler)
        false
    } catch (e: Exception) {
        Log.e("LyricsFetchViewModel", "Error embedding lyrics: ${e.message}")
        false
    }
}

fun handleSecurityException(
    securityException: SecurityException,
    intentPassthrough: (PendingIntent) -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val recoverableSecurityException =
            securityException as? RecoverableSecurityException
                ?: throw RuntimeException(securityException.message, securityException)

        intentPassthrough(recoverableSecurityException.userAction.actionIntent)
    } else {
        throw RuntimeException(securityException.message, securityException)
    }
}

/**
 * Defines possible provider choices
 */
enum class Providers(
    val displayName: String,
    val hasWordByWord: Boolean,
    val isAvailable: Boolean = true,
) {
    APPLE("Apple Music", true),
    LRCLIB("LRCLib", false),
    SPOTIFY("Spotify", false),
    MUSIXMATCH("Musixmatch", false, false),
    QQMUSIC("QQ Music", true),
    NETEASE("Netease", false, false),
}

// only for invoking the task and handling and reporting progress
suspend fun downloadLyrics(
    songs: List<Song>,
    viewModel: HomeViewModel,
    context: Context,
    onProgressUpdate: (successCount: Int, noLyricsCount: Int, failedCount: Int) -> Unit,
    onDownloadComplete: () -> Unit,
    onRateLimitReached: () -> Unit,
) {
    var successCount = 0
    var noLyricsCount = 0
    var failedCount = 0
    var rateLimitSeen = false

    if (songs.isEmpty()) {
        onDownloadComplete()
        return
    }

    songs.forEach { song ->
        currentCoroutineContext().ensureActive()
        val rateLimited = downloadLyricsForSong(
            song,
            viewModel,
            context,
            onNoLyrics = { noLyricsCount++ },
            onFailure = { failedCount++ },
            onLyricsSaved = { successCount++ },
        )

        onProgressUpdate(successCount, noLyricsCount, failedCount)
        if (rateLimited) rateLimitSeen = true
    }

    if (rateLimitSeen) onRateLimitReached()
    onDownloadComplete()
}

// only for retrieval, processing, and saving data
private suspend fun downloadLyricsForSong(
    song: Song,
    viewModel: HomeViewModel,
    context: Context,
    onNoLyrics: () -> Unit,
    onFailure: () -> Unit,
    onLyricsSaved: () -> Unit
): Boolean {
    return when (val outcome = viewModel.lookupLyrics(song.title, song.artist)) {
        is LyricsLookupOutcome.Failed -> {
            val rateLimited = outcome.attempts.any { attempt ->
                (attempt.result as? ProviderResult.Failure)?.reason is LyricsFailure.RateLimited
            }
            when {
                outcome.failure is LyricsFailure.NoLyrics -> onNoLyrics()
                rateLimited -> onFailure()
                else -> onFailure()
            }
            rateLimited
        }
        is LyricsLookupOutcome.Success -> {
            val lrcContent = formatLyrics(
                outcome.document.song,
                outcome.document.content,
                context,
                viewModel.userSettingsController.directlyModifyTimestamps,
            )

            val saved = if (viewModel.userSettingsController.embedLyricsIntoFiles) {
                val filePath = song.filePath
                filePath != null && embedLyricsInFile(context, filePath, lrcContent)
            } else {
                writeLyricsToFile(
                    song.filePath.toLrcFile(),
                    lrcContent,
                    context,
                    song,
                    viewModel.userSettingsController.sdCardPath,
                )
            }

            if (saved) onLyricsSaved() else onFailure()
            false
        }
    }
}

private fun formatLyrics(
    songInfo: SongInfo,
    lyrics: String,
    context: Context,
    directOffset: Boolean
): String {
    val lrcContent = generateLrcContent(
        songInfo,
        lyrics,
        context.getString(R.string.generated_using),
        directOffset = directOffset
    )

    return lrcContent
}

fun saveToExternalPath(
    context: Context,
    sourceFilePath: String?,
    lrc: String,
    fileName: String,
    newLyricsFilePath: String?
): Boolean {
    val sd = context.externalCacheDirs.getOrNull(1)?.absolutePath
        ?.substringBefore("/Android/data") ?: return false
    val path = sourceFilePath
        ?.toLrcFile()
        ?.absolutePath
        ?.substringAfter(sd)
        ?.split("/")
        ?.dropLast(1)
        ?: return false
    var sdCardFiles = DocumentFile.fromTreeUri(context, Uri.parse(newLyricsFilePath))
    path.forEach { element ->
        sdCardFiles = sdCardFiles?.listFiles()?.firstOrNull { it.name == element }
    }
    sdCardFiles?.listFiles()?.firstOrNull { it.name == fileName }?.delete()
    val destination = sdCardFiles?.createFile("text/lrc", fileName) ?: return false
    context.contentResolver.openOutputStream(destination.uri)?.use { outputStream ->
        outputStream.write(lrc.toByteArray())
    } ?: return false
    return destination.exists()
}

/**
 * "Legacy" way to apply an offset to lyrics, modifies the lyrics string directly
 * as most players do not support the offset tag in LRC files
 * @param lyrics the lyrics to apply the offset to
 * @param offset the offset to apply to the lyrics
 * @return the lyrics with the offset applied
 */
fun applyOffsetToLyrics(lyrics: String, offset: Int): String {
    val timestampRegex = Regex("""[\[<](\d+):(\d+)\.(\d+)[]>]""")

    fun applyOffset(minute: Int, second: Int, fraction: String): String {
        val millisecond = when (fraction.length) {
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.take(3).padEnd(3, '0').toInt()
        }
        val totalMilliseconds = (minute * 60 * 1000) + (second * 1000) + millisecond + offset
        if (totalMilliseconds < 0) return "00:00.000" // Prevent negative times

        val newMinutes = totalMilliseconds / 60000
        val newSeconds = (totalMilliseconds / 1000) % 60
        val newMilliseconds = (totalMilliseconds % 1000)

        return "${newMinutes.toString().padStart(2, '0')}:" +
                "${newSeconds.toString().padStart(2, '0')}." +
                newMilliseconds.toString().padStart(3, '0')
    }

    return lyrics.replace(timestampRegex) { matchResult ->
        val (minuteStr, secondStr, millisecondStr) = matchResult.destructured
        val minute = minuteStr.toInt()
        val second = secondStr.toInt()

        val startChar = matchResult.value[0]
        val endChar = if (startChar == '[') ']' else '>'

        "${startChar}${applyOffset(minute, second, millisecondStr)}$endChar"
    }
}

fun parseLyrics(lyrics: String): List<Pair<String, String>> {
    val timestampRegex = Regex("""[\[<](\d+):(\d+)\.(\d+)[]>]""")
    val lines = lyrics.lines()

    return lines.mapNotNull { line ->
        val match = timestampRegex.find(line) ?: return@mapNotNull null
        val (minute, second, millisecond) = match.destructured

        val startChar = line[0]
        val endChar = if (startChar == '[') ']' else '>'

        val normalizedFraction = when (millisecond.length) {
            1 -> millisecond + "00"
            2 -> millisecond + "0"
            else -> millisecond.take(3)
        }
        val timestamp = "${minute}:${second}.$normalizedFraction"
        val text = line.substringAfter(endChar).trim()

        timestamp to text
    }
}


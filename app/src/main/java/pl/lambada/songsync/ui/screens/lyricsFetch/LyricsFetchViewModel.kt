package pl.lambada.songsync.ui.screens.lyricsFetch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.lambada.songsync.R
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsLookupException
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsLookupOutcome
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsRequest
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsFailure
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsTiming
import pl.lambada.songsync.data.remote.lyrics_providers.ProviderRequestException
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.ui.LocalSong
import pl.lambada.songsync.util.embedLyricsInFile
import pl.lambada.songsync.util.generateLrcContent
import pl.lambada.songsync.util.isLegacyFileAccessRequired
import pl.lambada.songsync.util.newLyricsFilePath
import pl.lambada.songsync.util.saveToExternalPath
import pl.lambada.songsync.util.showToast

/**
 * ViewModel class for the main functionality of the app.
 */
class LyricsFetchViewModel(
    val source: LocalSong?,
    val userSettingsController: UserSettingsController,
    private val lyricsProviderService: LyricsProviderService
) : ViewModel() {
    var querySongName by mutableStateOf(source?.songName ?: "")
    var queryArtistName by mutableStateOf(source?.artists ?: "")

    // queryStatus: "Not submitted", "Pending", "Success", "Failed" - used to show different UI
    var queryState by mutableStateOf(
        if (source == null) QueryStatus.NotSubmitted else QueryStatus.Pending
    )
    private var queryOffset by mutableIntStateOf(0)
    var lrcOffset by mutableIntStateOf(0)

    var lyricsFetchState by mutableStateOf<LyricsFetchState>(LyricsFetchState.NotSubmitted)
    var resolvedProvider by mutableStateOf(userSettingsController.selectedProvider)
        private set
    var resolvedTiming by mutableStateOf<LyricsTiming?>(null)
        private set
    private var lookupJob: Job? = null

    fun loadSongInfo(context: Context, tryingAgain: Boolean = false) {
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                queryState = QueryStatus.Pending
                lyricsFetchState = LyricsFetchState.NotSubmitted
                queryOffset = if (tryingAgain) queryOffset + 1 else 0

                when (val outcome = lyricsProviderService.lookupLyrics(
                    LyricsRequest(
                        title = querySongName,
                        artist = queryArtistName,
                        offset = queryOffset,
                        includeTranslation = userSettingsController.includeTranslation,
                        includeRomanization = userSettingsController.includeRomanization,
                        multiPersonWordByWord = userSettingsController.multiPersonWordByWord,
                        allowUnsynced = userSettingsController.unsyncedFallbackMusixmatch,
                    ),
                    userSettingsController.selectedProvider,
                )) {
                    is LyricsLookupOutcome.Success -> {
                        resolvedProvider = outcome.document.provider
                        resolvedTiming = outcome.document.timing
                        queryState = QueryStatus.Success(outcome.document.song)
                        lyricsFetchState = LyricsFetchState.Success(outcome.document.content)
                    }
                    is LyricsLookupOutcome.Failed -> {
                        queryState = if (outcome.failure is LyricsFailure.Offline) {
                            QueryStatus.NoConnection
                        } else {
                            QueryStatus.Failed(LyricsLookupException(outcome.failure, outcome.attempts))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                queryState = QueryStatus.Failed(e)
            }
        }
    }

    fun saveLyricsToFile(
        lyrics: String,
        song: SongInfo,
        filePath: String?,
        context: Context,
        generatedUsingString: String
    ): Boolean {
        return runCatching {
            val lrcContent = generateLrcContent(
                song,
                lyrics,
                generatedUsingString,
                lrcOffset,
                userSettingsController.directlyModifyTimestamps,
            )
            val file = newLyricsFilePath(filePath, song)

            val saved = if (!isLegacyFileAccessRequired(filePath)) {
                file.parentFile?.mkdirs()
                file.writeText(lrcContent)
                true
            } else {
                saveToExternalPath(
                    context = context,
                    sourceFilePath = filePath,
                    lrc = lrcContent,
                    fileName = file.name,
                    newLyricsFilePath = userSettingsController.sdCardPath,
                )
            }

            if (!saved) throw IllegalStateException("Lyrics file could not be saved")
            showToast(context, R.string.file_saved_to, file.absolutePath)
            true
        }.getOrElse {
            showToast(context, R.string.storage_operation_failed)
            false
        }
    }

    fun embedLyrics(
        lyrics: String,
        filePath: String?,
        context: Context,
        song: SongInfo
    ) {
        val lrcContent = generateLrcContent(song, lyrics, context.getString(R.string.generated_using), lrcOffset, userSettingsController.directlyModifyTimestamps)

        runCatching {
            val embedded = embedLyricsInFile(
                context = context,
                filePath = if (filePath != null && filePath.isNotEmpty()) filePath else throw NullPointerException("File path is null"),
                lrcContent
            )
            if (!embedded) throw IllegalStateException("Embedding failed")
        }.onFailure { exception ->
            showToast(context, resolveEmbedErrorMessage(context, exception))
        }.onSuccess {
            showToast(context, R.string.embedded_lyrics_in_file)
        }
    }

    fun fetchLyricsInLanguage(songId: Long?, language: String) {
        if (songId == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            lyricsFetchState = LyricsFetchState.Pending
            
            try {
                val lyrics = lyricsProviderService.getLyricsInLanguage(songId, language)
                if (lyrics != null) {
                    lyricsFetchState = LyricsFetchState.Success(lyrics)
                    
                    // Update the currentLanguage in the song info
                    if (queryState is QueryStatus.Success) {
                        val currentSong = (queryState as QueryStatus.Success).song
                        queryState = QueryStatus.Success(
                            currentSong.copy(currentLanguage = language)
                        )
                    }
                } else {
                    lyricsFetchState = LyricsFetchState.Failed(
                        LyricsLookupException(LyricsFailure.NoLyrics(1))
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                lyricsFetchState = LyricsFetchState.Failed(
                    if (e is ProviderRequestException) {
                        LyricsLookupException(e.failure)
                    } else {
                        e
                    }
                )
            }
        }
    }
}

private fun resolveEmbedErrorMessage(context: Context, exception: Throwable): String {
    return when (exception) {
        is NullPointerException -> context.getString(R.string.embed_non_local_song_error)
        else -> context.getString(R.string.storage_operation_failed)
    }
}

sealed interface LyricsFetchState {
    data object NotSubmitted : LyricsFetchState
    data object Pending : LyricsFetchState
    data class Success(val lyrics: String) : LyricsFetchState
    data class Failed(val exception: Exception) : LyricsFetchState
}

sealed interface QueryStatus {
    data object NotSubmitted : QueryStatus
    data object Pending : QueryStatus
    data class Success(val song: SongInfo) : QueryStatus
    data class Failed(val exception: Exception) : QueryStatus
    data object NoConnection : QueryStatus
}

package pl.lambada.songsync.activities.quicksearch.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.lambada.songsync.R
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsLookupOutcome
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsRequest
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.ResourceState
import pl.lambada.songsync.util.ScreenState
import pl.lambada.songsync.util.parseLyrics
import pl.lambada.songsync.ui.userMessage

class QuickLyricsSearchViewModel(
    val userSettingsController: UserSettingsController,
    private val lyricsProviderService: LyricsProviderService
) : ViewModel() {
    private val mutableState = MutableStateFlow(QuickSearchViewState())
    val state = mutableState.asStateFlow()

    data class QuickSearchViewState(
        val song: Pair<String, String>? = null, // Pair of song title and artist's name
        val screenState: ScreenState<SongInfo> = ScreenState.Loading,
        val lyricsState: ResourceState<String> = ResourceState.Loading(),
        val parsedLyrics: List<Pair<String, String>> = emptyList()
    )

    private fun fetchSongData(song: Pair<String, String>, context: Context) {
        updateScreenState(ScreenState.Loading)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val outcome = lyricsProviderService.lookupLyrics(
                    LyricsRequest(
                        title = song.first,
                        artist = song.second,
                        includeTranslation = userSettingsController.includeTranslation,
                        includeRomanization = userSettingsController.includeRomanization,
                        multiPersonWordByWord = userSettingsController.multiPersonWordByWord,
                        allowUnsynced = userSettingsController.unsyncedFallbackMusixmatch,
                    ),
                    userSettingsController.selectedProvider,
                )) {
                    is LyricsLookupOutcome.Success -> {
                        updateScreenState(ScreenState.Success(outcome.document.song))
                        updateLyricsState(ResourceState.Success(outcome.document.content))
                        mutableState.update {
                            it.copy(parsedLyrics = parseLyrics(outcome.document.content))
                        }
                    }
                    is LyricsLookupOutcome.Failed -> {
                        val message = outcome.failure.userMessage(context)
                        updateScreenState(ScreenState.Error(Exception(message)))
                        updateLyricsState(ResourceState.Error(message))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val message = context.getString(R.string.unknown_error_occurred)
                updateScreenState(ScreenState.Error(Exception(message)))
                updateLyricsState(ResourceState.Error(message))
            }
        }
    }

    private fun updateScreenState(screenState: ScreenState<SongInfo>) {
        if (screenState != mutableState.value.screenState) {
            mutableState.update {
                it.copy(screenState = screenState)
            }
        }
    }

    private fun updateLyricsState(lyricsState: ResourceState<String>) {
        if (lyricsState != mutableState.value.lyricsState) {
            mutableState.update {
                it.copy(lyricsState = lyricsState)
            }
        }
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.Fetch -> {
                mutableState.update {
                    it.copy(song = event.song)
                }
                fetchSongData(event.song, event.context)
            }
        }
    }

    interface Event {
        data class Fetch(val song: Pair<String, String>, val context: Context) : Event
    }
}

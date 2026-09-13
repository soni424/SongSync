package pl.lambada.songsync.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.data.remote.UpdateService
import pl.lambada.songsync.data.remote.UpdateState
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.util.SpotifyProviderException
import pl.lambada.songsync.util.SpotifyAuthenticationRequiredException
import pl.lambada.songsync.util.SpotifySessionExpiredException
import pl.lambada.songsync.util.showToast

/**
 * ViewModel class for the main functionality of the app.
 */
class SettingsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    private val updateService: UpdateService = UpdateService()
) : ViewModel() {
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
    var spotifyState by mutableStateOf(
        if (lyricsProviderService.hasSpotifyCookie()) SpotifyConnectionState.CONNECTED
        else SpotifyConnectionState.NOT_CONFIGURED
    )
        private set

    var spotifyError by mutableStateOf<String?>(null)
        private set

    fun verifyAndSaveSpotifyCookie(value: String) {
        val previousState = if (lyricsProviderService.hasSpotifyCookie()) {
            SpotifyConnectionState.CONNECTED
        } else {
            SpotifyConnectionState.NOT_CONFIGURED
        }
        spotifyState = SpotifyConnectionState.VERIFYING
        spotifyError = null
        viewModelScope.launch {
            try {
                lyricsProviderService.verifyAndSaveSpotifyCookie(value)
                spotifyState = SpotifyConnectionState.CONNECTED
            } catch (error: SpotifyAuthenticationRequiredException) {
                spotifyState = SpotifyConnectionState.INVALID_OR_EXPIRED
                spotifyError = error.message
            } catch (error: SpotifySessionExpiredException) {
                spotifyState = SpotifyConnectionState.INVALID_OR_EXPIRED
                spotifyError = error.message
            } catch (error: SpotifyProviderException) {
                spotifyState = previousState
                spotifyError = error.message
            } catch (_: Exception) {
                spotifyState = previousState
                spotifyError = "Spotify Lyrics is temporarily unavailable."
            }
        }
    }

    fun clearSpotifyCookie() {
        lyricsProviderService.clearSpotifyCookie()
        spotifyState = SpotifyConnectionState.NOT_CONFIGURED
        spotifyError = null
    }

    fun dismissUpdate() { updateState = UpdateState.Idle }

    fun checkForUpdates(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { updateService.checkForUpdates(context) }.collect {
                updateState = it

                when (it) {
                    UpdateState.Checking -> showToast(
                        context,
                        context.getString(R.string.checking_for_updates),
                        long = false
                    )

                    is UpdateState.Error -> showToast(
                        context,
                        context.getString(R.string.error_checking_for_updates),
                        long = false
                    )

                    UpdateState.UpToDate -> showToast(
                        context,
                        context.getString(R.string.up_to_date),
                        long = false
                    )
                    else -> { }
                }
            }
        }
    }
}

enum class SpotifyConnectionState {
    NOT_CONFIGURED,
    VERIFYING,
    CONNECTED,
    INVALID_OR_EXPIRED,
}

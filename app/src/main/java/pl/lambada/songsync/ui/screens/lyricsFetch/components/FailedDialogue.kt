package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.SpotifyAuthenticationRequiredException
import pl.lambada.songsync.util.SpotifyLyricsNotFoundException
import pl.lambada.songsync.util.SpotifyProviderException
import pl.lambada.songsync.util.SpotifyRateLimitException
import pl.lambada.songsync.util.SpotifyServiceException
import pl.lambada.songsync.util.SpotifySessionExpiredException
import pl.lambada.songsync.util.SpotifyUnsyncedLyricsException
import pl.lambada.songsync.util.ext.getVersion
import pl.lambada.songsync.util.showToast

/**
 * Composable function to display a dialog for failed operations.
 *
 * @param onDismissRequest Callback to be invoked when the dialog is dismissed.
 * @param onOkRequest Callback to be invoked when the OK button is pressed.
 * @param exception The exception that caused the failure.
 */
@Composable
fun FailedDialogue(
    onDismissRequest: () -> Unit,
    onOkRequest: () -> Unit,
    exception: Exception,
    onOpenSettings: () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val spotifyError = exception as? SpotifyProviderException
    val needsSettings = exception is SpotifyAuthenticationRequiredException ||
        exception is SpotifySessionExpiredException
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (spotifyError != null) {
                    OutlinedButton(onClick = {
                        clipboardManager.setText(
                            AnnotatedString(spotifyError.diagnostic.toReport(context.getVersion()))
                        )
                        showToast(context, R.string.spotify_diagnostic_copied)
                    }) {
                        Text(stringResource(R.string.spotify_copy_diagnostic))
                    }
                }
                Button(onClick = if (needsSettings) onOpenSettings else onOkRequest) {
                    Text(stringResource(if (needsSettings) R.string.open_settings else R.string.ok))
                }
            }
        },
        title = { Text(text = stringResource(id = R.string.error)) },
        text = {
            when (exception) {
                is NoTrackFoundException -> Text(stringResource(R.string.no_results))
                is EmptyQueryException -> Text(stringResource(R.string.invalid_query))
                is SpotifyAuthenticationRequiredException -> Text(stringResource(R.string.spotify_auth_required))
                is SpotifySessionExpiredException -> Text(stringResource(R.string.spotify_session_expired))
                is SpotifyLyricsNotFoundException -> Text(stringResource(R.string.this_track_has_no_lyrics))
                is SpotifyUnsyncedLyricsException -> Text(stringResource(R.string.spotify_unsynchronized))
                is SpotifyRateLimitException -> Text(stringResource(R.string.spotify_rate_limited))
                is SpotifyServiceException -> Column {
                    Text(stringResource(R.string.spotify_provider_unavailable))
                    Text(stringResource(R.string.spotify_diagnostic_code, exception.diagnostic.code))
                }
                else -> Text(exception.message ?: stringResource(R.string.unknown_error_occurred))
            }
        }
    )
}

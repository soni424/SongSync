package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.lambada.songsync.R
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.SpotifyAuthenticationRequiredException
import pl.lambada.songsync.util.SpotifySessionExpiredException

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
    val needsSettings = exception is SpotifyAuthenticationRequiredException ||
        exception is SpotifySessionExpiredException
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(onClick = if (needsSettings) onOpenSettings else onOkRequest) {
                Text(stringResource(if (needsSettings) R.string.open_settings else R.string.ok))
            }
        },
        title = { Text(text = stringResource(id = R.string.error)) },
        text = {
            when (exception) {
                is NoTrackFoundException -> Text(stringResource(R.string.no_results))
                is EmptyQueryException -> Text(stringResource(R.string.invalid_query))
                is SpotifyAuthenticationRequiredException -> Text(stringResource(R.string.spotify_auth_required))
                is SpotifySessionExpiredException -> Text(stringResource(R.string.spotify_session_expired))
                else -> Text(exception.message ?: stringResource(R.string.unknown_error_occurred))
            }
        }
    )
}

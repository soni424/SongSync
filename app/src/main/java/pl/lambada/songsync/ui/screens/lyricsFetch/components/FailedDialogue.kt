package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.lambada.songsync.R
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsLookupException
import pl.lambada.songsync.ui.userMessage
import androidx.compose.ui.platform.LocalContext

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
    exception: Exception
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { Button(onClick = onOkRequest) { Text(stringResource(R.string.ok)) } },
        title = { Text(text = stringResource(id = R.string.error)) },
        text = {
            when (exception) {
                is NoTrackFoundException -> Text(stringResource(R.string.no_results))
                is EmptyQueryException -> Text(stringResource(R.string.invalid_query))
                is LyricsLookupException -> Text(exception.failure.userMessage(context))
                else -> Text(stringResource(R.string.unknown_error_occurred))
            }
        }
    )
}

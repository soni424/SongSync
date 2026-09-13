package pl.lambada.songsync.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.screens.settings.SpotifyConnectionState
import pl.lambada.songsync.ui.screens.settings.SpotifyConnectionTestState
import pl.lambada.songsync.util.ext.getVersion
import pl.lambada.songsync.util.showToast

@Composable
fun SpotifyCredentialsSection(
    state: SpotifyConnectionState,
    error: String?,
    testState: SpotifyConnectionTestState,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onRunTest: () -> Unit,
) {
    var cookie by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val verifying = state == SpotifyConnectionState.VERIFYING
    val testing = testState == SpotifyConnectionTestState.RUNNING
    val status = when (state) {
        SpotifyConnectionState.NOT_CONFIGURED -> stringResource(R.string.spotify_not_configured)
        SpotifyConnectionState.VERIFYING -> stringResource(R.string.spotify_verifying)
        SpotifyConnectionState.CONNECTED -> stringResource(R.string.spotify_connected)
        SpotifyConnectionState.INVALID_OR_EXPIRED -> stringResource(R.string.spotify_invalid_or_expired)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.spotify_cookie_instructions), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = status,
            color = when (state) {
                SpotifyConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
                SpotifyConnectionState.INVALID_OR_EXPIRED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !verifying,
            singleLine = true,
            label = { Text(stringResource(R.string.spotify_cookie_label)) },
            placeholder = { Text("sp_dc=…") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                cookie = ""
                onClear()
            }, enabled = !verifying) {
                Text(stringResource(R.string.spotify_clear))
            }
            Button(
                onClick = {
                    val value = cookie
                    cookie = ""
                    onSave(value)
                },
                enabled = cookie.isNotBlank() && !verifying,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.spotify_save_verify))
            }
        }
        OutlinedButton(
            onClick = onRunTest,
            enabled = state == SpotifyConnectionState.CONNECTED && !verifying && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (testing) R.string.spotify_connection_test_running
                    else R.string.spotify_run_connection_test
                )
            )
        }
        if (testState is SpotifyConnectionTestState.COMPLETE) {
            val report = testState.report
            val passed = report.passed.joinToString(", ") {
                it.name.lowercase().replace('_', ' ')
            }.ifEmpty { stringResource(R.string.spotify_no_stages_passed) }
            Text(
                stringResource(R.string.spotify_connection_test_passed, passed),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                report.failure?.let {
                    stringResource(R.string.spotify_connection_test_failed, it.diagnostic.code)
                } ?: stringResource(R.string.spotify_connection_test_success),
                color = if (report.failure == null) {
                    MaterialTheme.colorScheme.tertiary
                } else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(report.toReport(context.getVersion())))
                    showToast(context, R.string.spotify_diagnostic_copied)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.spotify_copy_diagnostic))
            }
        }
    }
}

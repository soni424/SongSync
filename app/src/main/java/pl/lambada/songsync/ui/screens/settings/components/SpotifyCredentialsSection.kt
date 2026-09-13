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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.screens.settings.SpotifyConnectionState

@Composable
fun SpotifyCredentialsSection(
    state: SpotifyConnectionState,
    error: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var cookie by remember { mutableStateOf("") }
    val verifying = state == SpotifyConnectionState.VERIFYING
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
    }
}

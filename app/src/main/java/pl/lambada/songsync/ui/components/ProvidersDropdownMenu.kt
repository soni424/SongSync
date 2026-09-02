package pl.lambada.songsync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.dropdown.AnimatedDropdownMenu
import pl.lambada.songsync.util.Providers

@Composable
fun ProvidersDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedProvider: Providers,
    onProviderSelectRequest: (Providers) -> Unit,
) {
    AnimatedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        ProvidersDropdownMenuContent(
            onDismissRequest = onDismissRequest,
            selectedProvider = selectedProvider,
            onProviderSelectRequest = onProviderSelectRequest,
        )
    }
}

@Composable
fun ProvidersDropdownMenuContent(
    onDismissRequest: () -> Unit,
    selectedProvider: Providers,
    onProviderSelectRequest: (Providers) -> Unit,
) = Column {
    val providers = Providers.entries.toTypedArray()
    Text(
        text = stringResource(id = R.string.provider),
        modifier = Modifier.padding(start = 18.dp, top = 8.dp),
        fontSize = 12.sp
    )
    providers.forEach {
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (it.isAvailable) {
                            it.displayName
                        } else {
                            "${it.displayName} (${stringResource(R.string.temporarily_unavailable)})"
                        },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    if (it.hasWordByWord)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant)
                                .size(14.dp)
                        ) {
                            Text(
                                text = "W",
                                color = MaterialTheme.colorScheme.background,
                                style = TextStyle(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                            )
                        }
                    Spacer(modifier = Modifier.weight(1f))
                    RadioButton(
                        selected = selectedProvider == it,
                        enabled = it.isAvailable,
                        onClick = {
                            onProviderSelectRequest(it)
                            onDismissRequest()
                        }
                    )
                }
            },
            enabled = it.isAvailable,
            onClick = {
                onProviderSelectRequest(it)
                onDismissRequest()
            }
        )
    }
}

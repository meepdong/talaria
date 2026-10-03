package io.github.meepdong.talaria.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.io.File

/** Desktop-only settings on the status screen: start at login and the log file. */
@Composable
fun DesktopSettings(loginItem: LoginItem, logFile: File, keepsRunning: Boolean) {
    var enabled by remember { mutableStateOf(runCatching { loginItem.isEnabled() }.getOrDefault(false)) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start Talaria when I log in", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = enabled,
                    // It can always be turned off, even when it can't be turned on from here.
                    enabled = enabled || loginItem.unavailableReason == null,
                    onCheckedChange = { on ->
                        error = runCatching { loginItem.setEnabled(on) }.exceptionOrNull()?.let {
                            "Couldn't change it: ${it.message}"
                        }
                        enabled = runCatching { loginItem.isEnabled() }.getOrDefault(false)
                    },
                    modifier = Modifier.testTag("start-at-login"),
                )
            }
            (error ?: loginItem.unavailableReason)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (keepsRunning) "Closing the window keeps Talaria connected in the tray. Quit from the tray icon."
                else "This desktop has no tray, so closing the window quits Talaria.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log: ${logFile.path}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    TextButton(onClick = {
                        logFile.parentFile?.let { dir -> Thread { runCatching { Desktop.getDesktop().open(dir) } }.start() }
                    }) { Text("Open folder") }
                }
            }
        }
    }
}

package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** UI.md §1, left: pair with a link, or with a short code plus the server's address. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(screen: Screen.Connect, actions: TalariaActions, extras: @Composable ColumnScope.() -> Unit = {}) {
    var useCode by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var name by remember(screen.deviceName) { mutableStateOf(screen.deviceName) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to your server", style = MaterialTheme.typography.headlineSmall)
        Text(
            "On the server, run  talaria pair  and copy the link it prints, " +
                "or the short code if you can't copy from there.",
            style = MaterialTheme.typography.bodyMedium,
        )

        extras()

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !useCode,
                onClick = { useCode = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("mode-link"),
            ) { Text("Paste a pairing link") }
            SegmentedButton(
                selected = useCode,
                onClick = { useCode = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("mode-code"),
            ) { Text("Enter a code") }
        }

        if (!useCode) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Pairing link") },
                placeholder = { Text("talaria://pair#…") },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().testTag("link"),
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Short code") },
                placeholder = { Text("ABCD-1234") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("code"),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Server address") },
                placeholder = { Text("my-server.tailnet.ts.net") },
                supportingText = { Text("A host name, or a wss:// address with its port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("address"),
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            label = { Text("This device's name") },
            supportingText = { Text("Shown on the server, for example in  talaria devices list") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("device-name"),
        )

        screen.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("connect-error"))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (useCode) actions.pairWithCode(code, address, name) else actions.pairWithLink(link, name)
                },
                enabled = !screen.busy && (if (useCode) code.isNotBlank() && address.isNotBlank() else link.isNotBlank()),
                modifier = Modifier.testTag("connect"),
            ) { Text("Connect") }
            if (screen.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Reaching the server…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            "ⓘ Needs your private network (Tailscale or WireGuard) on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

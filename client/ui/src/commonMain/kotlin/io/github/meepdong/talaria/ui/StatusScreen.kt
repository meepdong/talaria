package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * UI.md §2: Network, Bridge and agent rows, the failure and its fix, and the connection
 * log. [extras] holds platform settings, such as start at login on desktop.
 */
@Composable
fun StatusScreen(view: StatusView, actions: TalariaActions, extras: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (view.canGoBack) {
            TextButton(onClick = actions::showChats, modifier = Modifier.testTag("chats")) { Text("← Chats") }
        }
        Text("Connection", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                view.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    StatusRowItem(row)
                }
            }
        }

        view.balances.forEach { b ->
            val open = LocalUriHandler.current
            Card(Modifier.fillMaxWidth().testTag("balance")) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.name} balance", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (b.amount != null) {
                            Text(b.amount, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("balance-amount"))
                        } else {
                            Text(b.error ?: "Unknown", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Button(onClick = { runCatching { open.openUri(b.topUpUrl) } }, modifier = Modifier.testTag("top-up")) {
                        Text("Add credits")
                    }
                }
            }
        }

        view.failure?.let { failure ->
            Banner(failure, MaterialTheme.colorScheme.errorContainer, Modifier.testTag("failure")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (view.mustPairAgain) {
                        Button(onClick = actions::forgetServer, modifier = Modifier.testTag("pair-again")) {
                            Text("Pair again")
                        }
                        OutlinedButton(onClick = actions::reconnectNow) { Text("Try anyway") }
                    } else {
                        view.reconnectIn?.let { Text(it, modifier = Modifier.testTag("reconnect-in")) }
                        OutlinedButton(onClick = actions::reconnectNow, modifier = Modifier.testTag("reconnect")) {
                            Text("Reconnect now")
                        }
                    }
                }
            }
        }

        if (view.keyWarning) {
            Banner("⚠️ ${view.keyProtection}. Install a keyring (GNOME Keyring or KWallet) to protect it.",
                MaterialTheme.colorScheme.secondaryContainer, Modifier.testTag("key-warning"))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Last connected", view.lastConnected)
                Detail("Device", view.deviceName)
                Detail("Server", view.server)
                Detail("Device key", view.keyProtection)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = actions::testConnection,
                enabled = view.test?.running != true,
                modifier = Modifier.testTag("test"),
            ) { Text("Test connection") }
            val test = view.test
            when {
                test == null -> {}
                test.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Text(
                    (if (test.ok == true) "✓ " else "✗ ") + test.message.orEmpty(),
                    color = if (test.ok == true) Health.GOOD.color() else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("test-result"),
                )
            }
        }

        extras()

        Text("Connection log", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth().height(220.dp)) {
            if (view.log.isEmpty()) {
                Text("Nothing yet", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    LazyColumn(Modifier.padding(12.dp).testTag("log")) {
                        items(view.log) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        ForgetButton(actions)
    }
}

@Composable
private fun StatusRowItem(row: StatusRow) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(row.health.color(), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(row.value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End,
                    modifier = Modifier.testTag("row-${row.label}"))
            }
            row.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Banner(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

/** Two clicks, so the pairing isn't dropped by accident. */
@Composable
private fun ForgetButton(actions: TalariaActions) {
    var armed by remember { mutableStateOf(false) }
    TextButton(
        onClick = { if (armed) actions.forgetServer() else armed = true },
        modifier = Modifier.testTag("forget"),
    ) {
        Text(if (armed) "Click again to forget this server and pair from scratch" else "Forget this server")
    }
}

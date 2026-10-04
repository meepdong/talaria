package io.github.meepdong.talaria.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * A server operation waiting for approval (PROTOCOL §10.8). Allow is signed by this device; a tier 2
 * operation (reboot) asks again first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpsApprovalCard(a: OpsApprovalItem, actions: TalariaActions) {
    var confirming by remember(a.requestId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("ops-approval"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (a.from == "this device") "Approve on the server" else "${a.from.replaceFirstChar { it.uppercase() }} asks to",
                style = MaterialTheme.typography.titleSmall)
            Text(a.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ops-summary"))
            if (a.detail.isNotEmpty()) {
                Text(a.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { if (a.tier >= 2) confirming = true else actions.opsApprove(a.requestId, "once") },
                    enabled = !a.answering, modifier = Modifier.testTag("ops-allow")) { Text("Allow") }
                TextButton(onClick = { actions.opsApprove(a.requestId, "deny") }, enabled = !a.answering,
                    modifier = Modifier.testTag("ops-deny")) { Text("Deny", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Are you sure?") },
            text = { Text("${a.summary}. Everything on the server stops for a minute, including Hermes and this app's connection.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; actions.opsApprove(a.requestId, "once") },
                    modifier = Modifier.testTag("ops-confirm")) { Text(a.summary, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** An approved server operation that finished: what it did, and its output on demand. */
@Composable
fun OpsResultCard(r: OpsResultItem, actions: TalariaActions) {
    var open by remember(r.requestId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("ops-result"),
        colors = CardDefaults.cardColors(containerColor = if (r.ok) MaterialTheme.colorScheme.surfaceVariant
                                                          else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((if (r.ok) "✓ " else "✗ ") + r.summary, style = MaterialTheme.typography.bodyMedium)
            Text("Asked by ${r.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open && r.output.isNotBlank()) OutputBox(r.output)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.output.isNotBlank()) {
                    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("ops-output")) {
                        Text(if (open) "Hide output" else "Show output")
                    }
                }
                TextButton(onClick = { actions.opsDismiss(r.requestId) }, modifier = Modifier.testTag("ops-dismiss")) { Text("Dismiss") }
            }
        }
    }
}

/** Command output: monospace, selectable, scrolls both ways. */
@Composable
fun OutputBox(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()).testTag("ops-text"))
    }
}

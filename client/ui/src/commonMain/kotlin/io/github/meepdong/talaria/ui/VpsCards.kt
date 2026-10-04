package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.testTag

@Composable
fun VpsApprovalCard(
    payload: VpsApprovalPayload,
    onAllowOnce: () -> Unit,
    onAllowSession: () -> Unit,
    onDeny: () -> Unit,
) {
    val (command, args, cwd, timeout, turnId) = payload
    val argsString = if (args.isEmpty()) "" else " ${args.joinToString(" ")}"
    val fullCommand = "$command$argsString"

    Card(
        modifier = Modifier.fillMaxWidth().testTag("vps-approval"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        .testTag("vps-icon"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚡", fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("VPS Command Request", style = MaterialTheme.typography.titleMedium)
                    Text("Hermes wants to run a command on the VPS", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Command details
            Column(Modifier.fillMaxWidth().padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Command", fullCommand, monospace = true)
                DetailRow("Working dir", cwd, monospace = true)
                DetailRow("Timeout", "${timeout}s")
            }

            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f).testTag("vps-deny"),
                ) {
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onAllowOnce,
                    modifier = Modifier.weight(1f).testTag("vps-allow-once"),
                ) {
                    Text("Allow once")
                }
                Button(
                    onClick = onAllowSession,
                    modifier = Modifier.weight(1f).testTag("vps-allow-session"),
                ) {
                    Text("Allow session")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (monospace) FontFamily.Monospace else null),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
fun VpsRunningCard(
    payload: VpsApprovalPayload,
    output: List<String>,
    onStop: () -> Unit,
) {
    val (command, args, cwd, timeout, turnId) = payload
    val argsString = if (args.isEmpty()) "" else " ${args.joinToString(" ")}"
    val fullCommand = "$command$argsString"

    Card(
        modifier = Modifier.fillMaxWidth().testTag("vps-running"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header with spinner
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Running on VPS…", style = MaterialTheme.typography.titleMedium)
                    Text(fullCommand, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                FilledIconButton(onClick = onStop, modifier = Modifier.testTag("vps-stop")) {
                    Text("■", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Output
            if (output.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    output.takeLast(20).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (output.size > 20) {
                        Text("… (${output.size - 20} more lines)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun VpsResultCard(
    payload: VpsApprovalPayload,
    exitCode: Int,
    output: List<String>,
    onDismiss: () -> Unit,
) {
    val (command, args, cwd, timeout, turnId) = payload
    val argsString = if (args.isEmpty()) "" else " ${args.joinToString(" ")}"
    val fullCommand = "$command$argsString"
    val success = exitCode == 0

    Card(
        modifier = Modifier.fillMaxWidth().testTag("vps-result"),
        colors = CardDefaults.cardColors(
            containerColor = if (success) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .background(
                            if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (success) "✓" else "✗", fontSize = 20.sp, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (success) "Command completed" else "Command failed", style = MaterialTheme.typography.titleMedium)
                    Text(fullCommand, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    DetailRow("Exit code", exitCode.toString())
                    DetailRow("Working dir", cwd, monospace = true)
                }
            }

            if (output.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Output:", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        output.takeLast(30).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                        if (output.size > 30) {
                            Text("… (${output.size - 30} more lines)", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("vps-dismiss")) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun VpsApprovalDialog(
    payload: VpsApprovalPayload,
    onAllowOnce: () -> Unit,
    onAllowSession: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("VPS Command Request") },
        text = {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val argsString = if (payload.args.isEmpty()) "" else " ${payload.args.joinToString(" ")}"
                Text("Hermes wants to run:", style = MaterialTheme.typography.bodyMedium)
                Text("${payload.command}$argsString", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                Text("in ${payload.cwd} (timeout ${payload.timeout}s)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This requires your approval on this device.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDeny() }) { Text("Deny", color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { onAllowOnce() }) { Text("Allow once") }
                Button(onClick = { onAllowSession() }) { Text("Allow session") }
            }
        },
    )
}
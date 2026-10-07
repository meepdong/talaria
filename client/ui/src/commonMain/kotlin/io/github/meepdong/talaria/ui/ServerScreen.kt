package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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

/**
 * The Server page (PROTOCOL §10.8): the server's state, and what can be done to it. Reads run at once;
 * anything that changes the server shows an approval card here and on every device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerScreen(view: ServerView, actions: TalariaActions) {
    var confirmReboot by remember { mutableStateOf(false) }
    // Approvals and the newest result float above the page, so they show wherever it's scrolled to.
    val floating = view.approvals.isNotEmpty() || view.results.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
            .padding(bottom = if (floating) 220.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions::showChats, modifier = Modifier.testTag("chats")) { Text("← Back") }
            Spacer(Modifier.weight(1f))
            if (view.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = actions::refreshServer, modifier = Modifier.testTag("server-refresh")) { Text("Refresh") }
        }
        Text("Server", style = MaterialTheme.typography.headlineSmall)

        if (!view.available) {
            Text("This bridge offers no server operations. Install talaria-ops on the server (see bridge/README.md).",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("server-unavailable"))
            return@Column
        }
        view.error?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).testTag("server-error"))
                    TextButton(onClick = actions::dismissServerError) { Text("OK") }
                }
            }
        }
        if (view.results.size > 1) {
            Section("Earlier results") { view.results.drop(1).forEach { OpsResultCard(it, actions) } }
        }

        view.usage?.let { u -> Section("Hermes's usage") { UsageSection(u, actions) } }

        Section("Overview") {
            Text(view.overview.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("server-overview"))
            view.overviewRows.forEach { (k, v) ->
                Row { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) }
            }
        }

        Section("Services") {
            view.services.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(8.dp).background((if (s.state == "active") Health.GOOD else Health.BAD).color(), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name)
                        Text(s.state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.serverRun("service.logs", mapOf("service" to s.name, "lines" to "100")) },
                        modifier = Modifier.testTag("logs-${s.name}")) { Text("Logs") }
                    if (s.canRestart) {
                        TextButton(onClick = { actions.serverRun("service.restart", mapOf("service" to s.name)) },
                            modifier = Modifier.testTag("restart-${s.name}")) { Text("Restart") }
                    }
                }
            }
            view.logsTitle?.let { title ->
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                OutputBox(view.logs.orEmpty().ifEmpty { "(no lines)" })
            }
        }

        if (view.containers.isNotEmpty()) {
            Section("Docker") {
                view.containers.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            Text(c.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { actions.serverRun("docker.restart", mapOf("container" to c.name)) },
                            modifier = Modifier.testTag("restart-container-${c.name}")) { Text("Restart") }
                    }
                }
            }
        }

        if (view.hasSkills) {
            Section("Hermes's skills in Talaria") {
                Text(view.skillsSummary.ifEmpty { "…" } + ". Fewer skills make each reply quicker and cheaper; a change restarts Hermes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("skills-summary"))
                // switches only mark changes; Apply sends them as one operation: one approval, one restart (#52)
                var staged by remember { mutableStateOf(mapOf<String, Boolean>()) }
                val live = view.skills.associate { it.name to it.enabled }
                val changes = staged.filter { (name, on) -> live[name] != null && live[name] != on }  // not done yet
                val locked = view.skillsApplying != null
                view.skillsApplying?.let {
                    Text("⏳ $it Hermes restarts once when it's applied.", style = MaterialTheme.typography.bodySmall,
                        color = Brand.Brass, modifier = Modifier.testTag("skills-applying"))
                }
                if (changes.isNotEmpty() && !locked) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("skills-pending")) {
                        Text("${changes.size} change${if (changes.size != 1) "s" else ""} not applied yet",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { staged = emptyMap() }, modifier = Modifier.testTag("skills-undo")) { Text("Undo") }
                        Button(onClick = {
                            actions.serverRun("hermes.skills.set", mapOf("changes" to
                                changes.entries.sortedBy { it.key }.joinToString(",") { (n, on) -> "$n=${if (on) "on" else "off"}" }))
                        }, modifier = Modifier.testTag("skills-apply")) { Text("Apply") }
                    }
                }
                view.skills.forEachIndexed { i, k ->
                    if (i > 0) HorizontalDivider()
                    val shown = changes[k.name] ?: k.enabled
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(k.name + if (k.name in changes) " •" else "")
                            if (k.description.isNotEmpty()) {
                                Text(k.description, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = shown, enabled = !locked, modifier = Modifier.testTag("skill-${k.name}"), onCheckedChange = { on ->
                            if (view.skillsBatch) {
                                staged = if (on == k.enabled) staged - k.name else staged + (k.name to on)
                            } else {
                                actions.serverRun("hermes.skill.set", mapOf("skill" to k.name, "enabled" to if (on) "on" else "off"))
                            }
                        })
                    }
                }
            }
        }

        if (view.settings.isNotEmpty()) {
            Section("Hermes's settings") { HermesSettings(view, actions) }
        }
        view.memory?.let { mem -> Section("Hermes's memory") { MemorySection(mem, actions) } }
        if (view.canFindSkills) {
            Section("Find skills") { FindSkills(view, actions) }
        }

        Section("Maintenance") {
            Text("Talaria bridge: ${view.bridge.ifEmpty { "…" }}", modifier = Modifier.testTag("server-bridge"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { actions.serverRun("bridge.update") }, enabled = view.bridgeBehind,
                    modifier = Modifier.testTag("bridge-update")) { Text("Update bridge") }
                OutlinedButton(onClick = { actions.serverRun("apt.upgrade") }, modifier = Modifier.testTag("apt-upgrade")) {
                    Text("Upgrade packages")
                }
                OutlinedButton(onClick = { actions.serverRun("disk.cleanup") }, modifier = Modifier.testTag("disk-cleanup")) {
                    Text("Clean up disk")
                }
                OutlinedButton(onClick = { confirmReboot = true }, modifier = Modifier.testTag("reboot")) {
                    Text("Reboot", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("Each of these asks for approval on your devices first.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("History") {
            TextButton(onClick = { actions.serverRun("ops.history", mapOf("lines" to "50")) }, modifier = Modifier.testTag("history")) {
                Text("Show recent operations")
            }
            view.history?.let { OutputBox(it.ifEmpty { "(nothing yet)" }) }
        }
    }
    if (floating) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                .heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("ops-floating"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            view.approvals.forEach { Floating { OpsApprovalCard(it, actions) } }
            view.results.firstOrNull()?.let { Floating { OpsResultCard(it, actions) } }
        }
    }
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Reboot the server?") },
            text = { Text("You'll approve it once more on the card that appears.") },
            confirmButton = {
                TextButton(onClick = { confirmReboot = false; actions.serverRun("system.reboot") },
                    modifier = Modifier.testTag("reboot-ask")) { Text("Ask to reboot") }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Cancel") } },
        )
    }
}

/** A card lifted above the page. */
@Composable
private fun Floating(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 8.dp, tonalElevation = 2.dp) { content() }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

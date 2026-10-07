package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Hermes's settings (§16): each change is an approval card; Hermes restarts after it. */
@Composable
fun HermesSettings(view: ServerView, actions: TalariaActions) {
    Text("Each change asks for your approval, then Hermes restarts (a reply in progress stops).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    view.settingApplying?.let {
        Text("⏳ $it", style = MaterialTheme.typography.bodySmall, color = Brand.Brass, modifier = Modifier.testTag("setting-applying"))
    }
    val locked = view.settingApplying != null
    view.settings.forEachIndexed { i, s ->
        if (i > 0) HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).testTag("setting-${s.key}")) {
            Column(Modifier.weight(1f)) {
                Text(s.label)
                if (s.kind != "bool") {
                    Text(s.value.ifEmpty { s.empty ?: "not set" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (s.kind) {
                "bool" -> Switch(checked = s.value == "true", enabled = !locked, modifier = Modifier.testTag("setting-switch-${s.key}"),
                    onCheckedChange = { actions.serverRun("hermes.setting.set", mapOf("key" to s.key, "value" to it.toString())) })
                "choice" -> Pick(s.choices.map { it to it }, s.value, !locked, "setting-pick-${s.key}") {
                    actions.serverRun("hermes.setting.set", mapOf("key" to s.key, "value" to it))
                }
                "model" -> Pick((listOfNotNull(s.empty?.let { "" to it }) + view.settingModels.map { it to it }), s.value, !locked,
                    "setting-pick-${s.key}", searchable = true) {
                    actions.serverRun("hermes.setting.set", mapOf("key" to s.key, "value" to it))
                }
                "number" -> NumberEdit(s, !locked) { actions.serverRun("hermes.setting.set", mapOf("key" to s.key, "value" to it)) }
            }
        }
    }
}

@Composable
private fun Pick(options: List<Pair<String, String>>, current: String, enabled: Boolean, tag: String, searchable: Boolean = false,
                 onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).heightIn(max = 420.dp)) {
            if (searchable) {
                OutlinedTextField(query, { query = it.take(60) }, singleLine = true, placeholder = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("$tag-search"))
            }
            options.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }.take(60).forEach { (value, label) ->
                DropdownMenuItem(text = { Text((if (value == current) "✓ " else "") + label) }, enabled = value != current,
                    modifier = Modifier.testTag("$tag-$value"), onClick = { open = false; onPick(value) })
            }
        }
    }
}

@Composable
private fun NumberEdit(s: SettingRow, enabled: Boolean, onSet: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(s.value) { mutableStateOf(s.value) }
    if (!editing) {
        TextButton(onClick = { editing = true }, enabled = enabled, modifier = Modifier.testTag("setting-edit-${s.key}")) { Text("Change") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.take(6) }, singleLine = true, modifier = Modifier.widthIn(max = 90.dp).testTag("setting-number-${s.key}"))
        TextButton(onClick = { editing = false; onSet(text.trim()) }, enabled = text.toDoubleOrNull()?.let { it in 0.3..0.9 } == true,
            modifier = Modifier.testTag("setting-set-${s.key}")) { Text("Set") }
    }
}

/** Find skills in Hermes's registries and install one for Hermes or a bot (an approval card each). */
@Composable
fun FindSkills(view: ServerView, actions: TalariaActions) {
    var query by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it.take(100) }, singleLine = true, placeholder = { Text("e.g. pdf, notion, invoices") },
            modifier = Modifier.weight(1f).testTag("skill-query"))
        Button(onClick = { actions.serverRun("hermes.skill.search", mapOf("query" to query.trim())) },
            enabled = query.isNotBlank() && !view.searchingSkills, modifier = Modifier.testTag("skill-search")) {
            Text(if (view.searchingSkills) "Searching…" else "Search")
        }
    }
    view.skillsFoundSummary?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    view.skillsFound.forEachIndexed { i, k ->
        if (i > 0) HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).testTag("found-${k.identifier}")) {
            Column(Modifier.weight(1f)) {
                Text(k.name)
                Text(listOf(k.source, k.trust).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (k.description.isNotEmpty()) {
                    Text(k.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            var open by remember(k.identifier) { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("install-${k.identifier}")) { Text("Install ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    view.installFor.forEach { (profile, name) ->
                        DropdownMenuItem(text = { Text("For $name") }, modifier = Modifier.testTag("install-for-$profile"), onClick = {
                            open = false
                            actions.serverRun("hermes.skill.install", mapOf("identifier" to k.identifier, "profile" to profile))
                        })
                    }
                }
            }
        }
    }
}

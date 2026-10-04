package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The ☰ menu's default model: what new chats start on, on every device (§11). Each chat can still switch. */
@Composable
fun DefaultModelPicker(menu: MenuView, actions: TalariaActions) {
    var open by remember { mutableStateOf(false) }
    var query by remember(open) { mutableStateOf("") }
    val label = menu.defaultModel?.let { if (menu.defaultIsAgents) "$it (Hermes's default)" else it } ?: "Loading models…"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("default-model")) {
            Text("$label ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 340.dp).heightIn(max = 460.dp).testTag("default-model-picker")) {
            OutlinedTextField(query, { query = it.take(60) }, singleLine = true, placeholder = { Text("Search models") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("default-model-search"))
            DropdownMenuItem(
                text = { Text("Hermes's default", fontWeight = if (menu.defaultIsAgents) FontWeight.SemiBold else null) },
                trailingIcon = { if (menu.defaultIsAgents) Text("✓", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.testTag("default-model-agent"),
                onClick = { open = false; actions.setDefaultModel(null, null) },
            )
            HorizontalDivider()
            val shown = filterModels(menu.defaultModelGroups, query)
            if (menu.defaultModelGroups.isEmpty()) {
                Text("Loading models…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { g ->
                Text(g.name, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.models.forEach { m ->
                    val picked = m.selected && !menu.defaultIsAgents
                    DropdownMenuItem(
                        text = { Text(m.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (picked) FontWeight.SemiBold else null) },
                        trailingIcon = { if (picked) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.testTag("default-model-${m.model}"),
                        onClick = { open = false; actions.setDefaultModel(m.provider, m.model) },
                    )
                }
            }
        }
    }
}

package io.github.meepdong.talaria.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A switch in the bot editor: a skill, a set of tools or a connector (an MCP server). */
data class SwitchItem(val name: String, val label: String, val about: String, val on: Boolean)

/** The bot editor (spec §18.8): a bot's name, what it's for, personality, model, picture, skills, tools, connectors. */
data class BotEditorView(
    /** Null while making a new bot. */
    val botId: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val name: String = "",
    val about: String = "",
    val personality: String = "",
    /** Null: Hermes's default model. */
    val model: String? = null,
    /** Models the owner's guardrail allows. */
    val models: List<String> = emptyList(),
    val skills: List<SwitchItem> = emptyList(),
    val toolsets: List<SwitchItem> = emptyList(),
    val connectors: List<SwitchItem> = emptyList(),
    /** Hermes's warning about an expensive model, until Use it anyway. */
    val confirm: String? = null,
    val notice: String? = null,
    /** Changes when the settings were (re)loaded, so the form starts from them again. */
    val version: Int = 0,
    val picture: ImageBitmap? = null,
    val canPickPicture: Boolean = false,
)

/** What the form holds when Save is pressed. */
data class BotDraft(
    val name: String, val about: String, val personality: String, val model: String?,
    val skillsOn: Set<String>, val toolsetsOn: Set<String>, val connectorsOn: Set<String>,
)

@Composable
fun BotEditorScreen(view: BotEditorView, actions: TalariaActions) {
    val key = "${view.botId}:${view.version}"
    var name by remember(key) { mutableStateOf(view.name) }
    var about by remember(key) { mutableStateOf(view.about) }
    var personality by remember(key) { mutableStateOf(view.personality) }
    var model by remember(key) { mutableStateOf(view.model) }
    var skills by remember(key) { mutableStateOf(view.skills.filter { it.on }.map { it.name }.toSet()) }
    var toolsets by remember(key) { mutableStateOf(view.toolsets.filter { it.on }.map { it.name }.toSet()) }
    var connectors by remember(key) { mutableStateOf(view.connectors.filter { it.on }.map { it.name }.toSet()) }
    val draft = BotDraft(name, about, personality, model, skills, toolsets, connectors)
    val original = BotDraft(view.name, view.about, view.personality, view.model, view.skills.filter { it.on }.map { it.name }.toSet(),
        view.toolsets.filter { it.on }.map { it.name }.toSet(), view.connectors.filter { it.on }.map { it.name }.toSet())
    val isNew = view.botId == null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("bot-editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions::closeBotEditor, modifier = Modifier.testTag("bot-editor-back")) { Text("← Back") }
            Text(if (isNew) "New bot" else "Bot settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        view.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bot-editor-notice")) }
        if (view.loading) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (!isNew) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, modifier = Modifier.size(64.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    val pic = view.picture
                    if (pic != null) {
                        Image(pic, "Picture", Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        Box(contentAlignment = Alignment.Center) { Text(initialsOf(view.name), style = MaterialTheme.typography.titleMedium) }
                    }
                }
                Column {
                    if (view.canPickPicture) {
                        TextButton(onClick = actions::pickBotPicture, modifier = Modifier.testTag("bot-picture")) { Text("Change picture") }
                    }
                    if (view.picture != null) {
                        TextButton(onClick = actions::clearBotPicture, modifier = Modifier.testTag("bot-picture-clear")) { Text("Remove picture") }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().testTag("bot-name"))
        OutlinedTextField(about, { about = it.take(2000) }, label = { Text("What it's for") }, minLines = 3,
            supportingText = { Text("Hermes reads this when it picks a bot for a job.") },
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().testTag("bot-about"))
        OutlinedTextField(personality, { personality = it.take(40000) }, label = { Text("Personality and rules") }, minLines = 5,
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 420.dp).testTag("bot-personality"))
        ModelPick(model, view.models) { model = it }
        if (!isNew) {
            Toggles("Skills", view.skills, skills) { skills = it }
            Toggles("Tools", view.toolsets, toolsets) { toolsets = it }
            if (view.connectors.isNotEmpty()) Toggles("Connectors", view.connectors, connectors) { connectors = it }
        }
        view.confirm?.let { warning ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(warning, modifier = Modifier.testTag("bot-confirm"))
                    Button(onClick = { actions.saveBot(draft, confirm = true) }, modifier = Modifier.testTag("bot-confirm-yes")) { Text("Use it anyway") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { actions.saveBot(draft, confirm = false) },
                enabled = !view.saving && name.isNotBlank() && (isNew || draft != original),
                modifier = Modifier.testTag("bot-save")) { Text(if (view.saving) "Saving…" else if (isNew) "Make bot" else "Save") }
            if (!isNew) {
                Spacer(Modifier.weight(1f))
                var sure by remember(view.botId) { mutableStateOf(false) }
                if (sure) {
                    TextButton(onClick = { sure = false; actions.deleteBot() }, modifier = Modifier.testTag("bot-delete-sure")) {
                        Text("Delete it, with its chats and memory", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { sure = true }, modifier = Modifier.testTag("bot-delete")) {
                        Text("Delete bot", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (isNew) {
            Text("Skills, tools and a picture can be set once it's made.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelPick(model: String?, models: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("bot-model")) {
            Text("Model: " + (model ?: "Hermes's default"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = 420.dp)) {
            OutlinedTextField(query, { query = it.take(60) }, singleLine = true, placeholder = { Text("Search models") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("bot-model-search"))
            models.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.take(60).forEach { m ->
                DropdownMenuItem(text = { Text((if (m == model) "✓ " else "") + m) }, modifier = Modifier.testTag("bot-model-$m"),
                    onClick = { open = false; onPick(m) })
            }
        }
    }
}

@Composable
private fun Toggles(title: String, items: List<SwitchItem>, on: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp).testTag("bot-$title"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$title · ${items.count { it.name in on }} on of ${items.size}", Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (open) "▴" else "▾")
        }
        if (open) {
            items.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                        if (s.about.isNotBlank()) {
                            Text(s.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Switch(checked = s.name in on, onCheckedChange = { onChange(if (it) on + s.name else on - s.name) },
                        modifier = Modifier.testTag("switch-${s.name}"))
                }
            }
        }
    }
}

fun initialsOf(name: String): String = name.split(' ', '-', '_').filter { it.isNotEmpty() }.take(2)
    .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

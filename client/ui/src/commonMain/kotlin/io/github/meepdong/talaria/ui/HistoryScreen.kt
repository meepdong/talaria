package io.github.meepdong.talaria.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The History page (spec §18.9): Hermes's sessions from every surface, searched, read, carried on in Talaria. */
data class HistoryView(
    val query: String = "",
    /** Whose sessions: (id, name); null id is Hermes. */
    val who: Pair<String?, String> = null to "Hermes",
    val whoOptions: List<Pair<String?, String>> = listOf(null to "Hermes"),
    val sessions: List<HistoryItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val open: HistoryItem? = null,
    /** (from Hermes: false / the owner: true, text, "Mon 09:12") */
    val messages: List<Triple<Boolean, String, String>> = emptyList(),
    val moreMessages: Boolean = false,
    val reading: Boolean = false,
    /** Hermes's own sessions can be carried on in Talaria; a bot's are read only. */
    val canContinue: Boolean = true,
    val notice: String? = null,
)

data class HistoryItem(
    val id: String, val title: String,
    /** "Telegram", "Talaria", "Hermes Desktop", "Routine", … */
    val where: String,
    val time: String, val messages: Int, val snippet: String? = null,
    /** Already a Talaria conversation: open it instead. */
    val conversationId: String? = null,
)

@Composable
fun HistoryScreen(view: HistoryView, actions: TalariaActions) {
    var query by remember { mutableStateOf(view.query) }
    val open = view.open
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("history"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (open != null) actions.historyOpen(null) else actions.closeHistory() },
                modifier = Modifier.testTag("history-back")) { Text("← Back") }
            Text(if (open != null) open.title else "History", style = MaterialTheme.typography.titleLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        view.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (open != null) {
            Text("${open.where} · ${open.time} · ${open.messages} messages", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    open.conversationId != null -> Button(onClick = { actions.openConversation(open.conversationId) },
                        modifier = Modifier.testTag("history-open")) { Text("Open in Talaria") }
                    view.canContinue -> Button(onClick = { actions.historyContinue(open.id) },
                        modifier = Modifier.testTag("history-continue")) { Text("Continue in Talaria") }
                }
            }
            if (view.reading && view.messages.isEmpty()) Text("Loading…")
            view.messages.forEach { (mine, text, at) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text((if (mine) "You" else view.who.second) + " · " + at, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mine) Text(text) else MarkdownText(text)
                }
            }
            if (view.moreMessages) {
                TextButton(onClick = { actions.historyMoreMessages() }, modifier = Modifier.testTag("history-more-messages")) { Text("More") }
            }
            return@Column
        }
        Text("Every conversation Hermes has had: here, in Telegram, Hermes Desktop, the terminal and routines.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.whoOptions.size > 1) {
                var picking by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("history-who")) { Text(view.who.second + " ▾") }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        view.whoOptions.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, modifier = Modifier.testTag("history-who-${id ?: "hermes"}"),
                                onClick = { picking = false; actions.historyLoad(query.ifBlank { null }, id) })
                        }
                    }
                }
            }
            OutlinedTextField(query, { query = it.take(200) }, singleLine = true, placeholder = { Text("Search") },
                modifier = Modifier.weight(1f).testTag("history-query"))
            TextButton(onClick = { actions.historyLoad(query.ifBlank { null }, view.who.first) }, modifier = Modifier.testTag("history-search")) {
                Text("Go")
            }
        }
        if (view.loading && view.sessions.isEmpty()) Text("Loading…")
        if (!view.loading && view.sessions.isEmpty()) {
            Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("history-empty"))
        }
        view.sessions.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable { actions.historyOpen(s.id) }.padding(vertical = 6.dp).testTag("past-${s.id}")) {
                Text(s.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${s.where} · ${s.time} · ${s.messages} messages" + if (s.conversationId != null) " · in Talaria" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (view.hasMore) {
            TextButton(onClick = { actions.historyMore() }, modifier = Modifier.testTag("history-more")) { Text("Older") }
        }
    }
}

/** Hermes's memory (§16) on the Server page: two notebooks for Hermes or a bot, edited here, saved with an approval. */
data class MemoryView(
    val profile: String = "default",
    val whoOptions: List<Pair<String, String>> = listOf("default" to "Hermes"),
    val notes: List<String> = emptyList(),
    val aboutYou: List<String> = emptyList(),
    val notesLimit: Int = 2200,
    val aboutYouLimit: Int = 1375,
    val loaded: Boolean = false,
    /** "Waiting for your approval…" while a change waits or runs. */
    val applying: String? = null,
)

@Composable
fun MemorySection(view: MemoryView, actions: TalariaActions) {
    Text("What Hermes (or a bot) reads at the start of every chat. Saving asks for your approval.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("memory-who")) {
                Text((view.whoOptions.firstOrNull { it.first == view.profile }?.second ?: view.profile) + " ▾")
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                view.whoOptions.forEach { (profile, name) ->
                    DropdownMenuItem(text = { Text(name) }, modifier = Modifier.testTag("memory-who-$profile"),
                        onClick = { picking = false; actions.serverRun("hermes.memory", mapOf("profile" to profile)) })
                }
            }
        }
        if (!view.loaded) {
            TextButton(onClick = { actions.serverRun("hermes.memory", mapOf("profile" to view.profile)) },
                modifier = Modifier.testTag("memory-load")) { Text("Show") }
        }
    }
    view.applying?.let { Text("⏳ $it", style = MaterialTheme.typography.bodySmall, color = Brand.Brass) }
    if (!view.loaded) return
    Notebook("Notes", "memory", view.profile, view.notes, view.notesLimit, view.applying == null, actions)
    Notebook("About you", "user", view.profile, view.aboutYou, view.aboutYouLimit, view.applying == null, actions)
}

@Composable
private fun Notebook(title: String, target: String, profile: String, entries: List<String>, limit: Int, enabled: Boolean,
                     actions: TalariaActions) {
    var draft by remember(profile, entries) { mutableStateOf(entries) }
    var adding by remember(profile, entries) { mutableStateOf("") }
    val size = (draft + listOfNotNull(adding.trim().ifEmpty { null })).joinToString("\n§\n").length
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(top = 8.dp).testTag("notebook-$target"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · $size of $limit characters", style = MaterialTheme.typography.titleSmall,
            color = if (size > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        draft.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(e, { new -> draft = draft.toMutableList().also { it[i] = new.take(limit) } },
                    modifier = Modifier.weight(1f).testTag("entry-$target-$i"))
                TextButton(onClick = { draft = draft.filterIndexed { j, _ -> j != i } }, modifier = Modifier.testTag("entry-remove-$target-$i")) { Text("✕") }
            }
        }
        OutlinedTextField(adding, { adding = it.take(limit) }, placeholder = { Text("Add an entry") },
            modifier = Modifier.fillMaxWidth().testTag("entry-add-$target"))
        val now = draft.map { it.trim() }.filter { it.isNotEmpty() } + listOfNotNull(adding.trim().ifEmpty { null })
        if (now != entries) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.serverRun("hermes.memory.set", mapOf("profile" to profile, "target" to target,
                    "content" to now.joinToString("\n§\n"))) }, enabled = enabled && size <= limit,
                    modifier = Modifier.testTag("memory-save-$target")) { Text("Save") }
                TextButton(onClick = { draft = entries; adding = "" }) { Text("Undo") }
            }
        }
    }
}

/** A card shown in a bot's chat while the owner edits one of their messages (§18.10). */
@Composable
fun EditingBanner(actions: TalariaActions) {
    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("editing")) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Editing your message: Send replaces it and everything after it.", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions::cancelEdit, modifier = Modifier.testTag("editing-cancel")) { Text("Cancel") }
        }
    }
}

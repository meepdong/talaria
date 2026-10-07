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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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

/** The To-dos tab: the owner's own list, or (when the bridge has one) Hermes's board of bots' tasks (§18.4). */
@Composable
fun TodosTab(todos: TodosView, board: BoardView, actions: TalariaActions) {
    Column(Modifier.fillMaxSize()) {
        if (board.available) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !board.showing, onClick = { actions.showBoard(false) }, label = { Text("My list") },
                    modifier = Modifier.testTag("show-list"))
                FilterChip(selected = board.showing, onClick = { actions.showBoard(true) },
                    label = { Text(if (board.taskCount > 0) "Bots' board · ${board.taskCount}" else "Bots' board") },
                    modifier = Modifier.testTag("show-board"))
            }
        }
        Box(Modifier.weight(1f)) {
            if (board.available && board.showing) BoardScreen(board, actions) else TodosScreen(todos, actions)
        }
    }
}

@Composable
fun BoardScreen(view: BoardView, actions: TalariaActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("board"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Give a task to a bot and it starts on its own; it writes back here when it's done. Hermes Desktop shows the same board.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        view.notice?.let { notice ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f).testTag("board-notice"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions::boardDismiss) { Text("OK") }
                }
            }
        }
        AddTask(view.people, actions)
        if (view.columns.isEmpty()) {
            Text("Nothing on the board yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("board-empty"))
        }
        view.columns.forEach { col ->
            Text("${col.label} · ${col.tasks.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).testTag("column-${col.name}"))
            col.tasks.forEach { t -> TaskCard(t, view, actions) }
        }
    }
}

@Composable
private fun AddTask(people: List<Pair<String, String>>, actions: TalariaActions) {
    var title by remember { mutableStateOf("") }
    var who by remember { mutableStateOf<Pair<String, String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(title, { title = it.take(300) }, placeholder = { Text("New task, e.g. Summarise Monday's call") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("task-title"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("task-for")) {
                    Text("For: " + (who?.second ?: "nobody yet"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    DropdownMenuItem(text = { Text("Nobody yet") }, onClick = { who = null; picking = false })
                    people.forEach { p ->
                        DropdownMenuItem(text = { Text(p.second) }, modifier = Modifier.testTag("for-${p.first}"),
                            onClick = { who = p; picking = false })
                    }
                }
            }
            Button(onClick = { actions.boardAdd(title, who?.first); title = "" }, enabled = title.isNotBlank(),
                modifier = Modifier.testTag("task-add")) { Text(if (who == null) "Add" else "Add and start") }
        }
    }
}

@Composable
private fun TaskCard(t: BoardTaskItem, view: BoardView, actions: TalariaActions) {
    val open = view.openTask == t.id
    Card(Modifier.widthIn(max = 900.dp).fillMaxWidth().clickable { actions.boardOpen(if (open) null else t.id) }.testTag("task-${t.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.who?.let { "🤖 $it" } ?: "Nobody", t.age, if (t.comments > 0) "💬 ${t.comments}" else null)
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.error?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = if (open) 8 else 2) }
            t.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 20 else 3, overflow = TextOverflow.Ellipsis) }
            val d = view.detail
            if (open && d != null && d.id == t.id) TaskDetails(t, d, view.people, actions)
        }
    }
}

@Composable
private fun TaskDetails(t: BoardTaskItem, d: BoardDetail, people: List<Pair<String, String>>, actions: TalariaActions) {
    var moving by remember { mutableStateOf(false) }
    var giving by remember { mutableStateOf(false) }
    var comment by remember(t.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp).testTag("task-details")) {
        d.body?.let { MarkdownText(it) }
        d.result?.let { Text("Result", style = MaterialTheme.typography.labelLarge); MarkdownText(it) }
        d.comments.forEach { (who, text, at) ->
            Text("$who · $at", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { moving = true }, modifier = Modifier.testTag("task-move")) { Text("Move ▾") }
                DropdownMenu(expanded = moving, onDismissRequest = { moving = false }) {
                    d.moves.forEach { (status, label) ->
                        DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("move-$status"),
                            onClick = { moving = false; actions.boardMove(t.id, status) })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { giving = true }, modifier = Modifier.testTag("task-give")) { Text("Give to ▾") }
                DropdownMenu(expanded = giving, onDismissRequest = { giving = false }) {
                    DropdownMenuItem(text = { Text("Nobody") }, onClick = { giving = false; actions.boardGive(t.id, "") })
                    people.forEach { p ->
                        DropdownMenuItem(text = { Text(p.second) }, modifier = Modifier.testTag("give-${p.first}"),
                            onClick = { giving = false; actions.boardGive(t.id, p.first) })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(comment, { comment = it.take(8000) }, placeholder = { Text("Comment, or more details") },
                modifier = Modifier.weight(1f).testTag("task-comment"))
            TextButton(onClick = { actions.boardComment(t.id, comment); comment = "" }, enabled = comment.isNotBlank(),
                modifier = Modifier.testTag("task-comment-send")) { Text("Send") }
        }
    }
}

/** The Server page's usage section (§18.5). */
@Composable
fun UsageSection(u: UsageView, actions: TalariaActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("usage")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { n ->
                FilterChip(selected = u.days == n, onClick = { actions.loadUsage(n) }, label = { Text("$n days") },
                    modifier = Modifier.testTag("usage-$n"))
            }
        }
        if (u.loading && u.total.isEmpty()) Text("Loading…", style = MaterialTheme.typography.bodySmall)
        if (u.total.isNotEmpty()) {
            Text(u.total, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("usage-total"))
            Text(u.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        u.byDay.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { b.fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(b.cost, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (u.byModel.isNotEmpty()) Text("By model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        u.byModel.forEach { (model, what) ->
            Row {
                Text(model, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Bots' routines on the Schedule tab (§18.6): each with on/off, Run now and Delete; and a new one for a bot. */
@Composable
fun RoutinesCard(view: RoutinesView, actions: TalariaActions, modifier: Modifier = Modifier) {
    SectionCard("Bots' routines", modifier = modifier.testTag("routines")) {
        Text("Tasks your bots do on a schedule. Their results go to the bot's chat. Hermes Desktop shows the same ones.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        view.notice?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f).testTag("routines-notice"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions::boardDismiss) { Text("OK") }
            }
        }
        if (view.items.isEmpty()) {
            Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        view.items.forEach { r -> RoutineRow(r, actions) }
        NewRoutine(view.bots, actions)
    }
}

@Composable
private fun RoutineRow(r: RoutineItem, actions: TalariaActions) {
    var confirm by remember(r.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("routine-${r.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("🤖 ${r.who} · ${r.schedule}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Switch(checked = r.on, onCheckedChange = { actions.routineSet(r.botId, r.id, if (it) "resume" else "pause") },
                modifier = Modifier.testTag("routine-on-${r.id}"))
        }
        Text(r.task, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val facts = listOfNotNull(r.next?.let { "Next: $it" }, r.last)
        if (facts.isNotEmpty()) {
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = if (r.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            TextButton(onClick = { actions.routineSet(r.botId, r.id, "run") }, modifier = Modifier.testTag("routine-run-${r.id}")) { Text("Run now") }
            if (confirm) {
                TextButton(onClick = { confirm = false; actions.routineSet(r.botId, r.id, "remove") },
                    modifier = Modifier.testTag("routine-delete-sure-${r.id}")) { Text("Delete it", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirm = false }) { Text("Keep") }
            } else {
                TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("routine-delete-${r.id}")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NewRoutine(bots: List<Pair<String, String>>, actions: TalariaActions) {
    var open by remember { mutableStateOf(false) }
    var bot by remember { mutableStateOf<Pair<String, String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, enabled = bots.isNotEmpty(), modifier = Modifier.testTag("routine-new")) { Text("+ New routine") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("routine-form")) {
        Box {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("routine-bot")) { Text("Bot: " + (bot?.second ?: "pick one")) }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                bots.forEach { b ->
                    DropdownMenuItem(text = { Text(b.second) }, modifier = Modifier.testTag("routine-bot-${b.first}"),
                        onClick = { bot = b; picking = false })
                }
            }
        }
        OutlinedTextField(name, { name = it.take(200) }, singleLine = true, placeholder = { Text("Name, e.g. Monday inbox sweep") },
            modifier = Modifier.fillMaxWidth().testTag("routine-name"))
        OutlinedTextField(schedule, { schedule = it.take(200) }, singleLine = true,
            placeholder = { Text("When: weekdays at 9am, every 2h, every monday 8am") }, modifier = Modifier.fillMaxWidth().testTag("routine-when"))
        OutlinedTextField(task, { task = it.take(8000) }, placeholder = { Text("What to do each time") },
            modifier = Modifier.fillMaxWidth().testTag("routine-task"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = bot
            Button(onClick = { if (b != null) actions.routineAdd(b.first, name, schedule, task); open = false; name = ""; schedule = ""; task = "" },
                enabled = b != null && name.isNotBlank() && schedule.isNotBlank() && task.isNotBlank(),
                modifier = Modifier.testTag("routine-add")) { Text("Add routine") }
            TextButton(onClick = { open = false }) { Text("Cancel") }
        }
    }
}

/** A helper agent working for the bot (§18.7): what it's doing, a note for it, Stop. */
@Composable
fun HelperRow(h: HelperItem, actions: TalariaActions) {
    var noting by remember(h.id) { mutableStateOf(false) }
    var note by remember(h.id) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("helper-${h.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🧩 ${h.goal}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(h.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (h.canSteer) TextButton(onClick = { noting = !noting }, modifier = Modifier.testTag("helper-note-${h.id}")) { Text("Note") }
                TextButton(onClick = { actions.helperStop(h.id) }, modifier = Modifier.testTag("helper-stop-${h.id}")) { Text("Stop") }
            }
            if (noting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(note, { note = it.take(4000) }, singleLine = true, placeholder = { Text("A note for this helper") },
                        modifier = Modifier.weight(1f).testTag("helper-text-${h.id}"))
                    TextButton(onClick = { actions.helperSteer(h.id, note); note = ""; noting = false }, enabled = note.isNotBlank(),
                        modifier = Modifier.testTag("helper-send-${h.id}")) { Text("Send") }
                }
            }
        }
    }
}

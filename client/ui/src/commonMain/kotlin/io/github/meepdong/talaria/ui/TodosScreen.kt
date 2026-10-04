package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** The To-dos page: the whole list, grouped by Hermes, each to-do with its comments and what to do about it. */
@Composable
fun TodosScreen(view: TodosView, actions: TalariaActions) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val toggle = { id: String -> expanded = if (expanded == id) null else id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("todos-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("To-dos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(if (view.openCount == 1) "1 to do" else "${view.openCount} to do", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (view.available) {
                OutlinedButton(onClick = actions::regroupTodos, enabled = !view.regrouping && view.openCount > 0,
                    modifier = Modifier.testTag("todos-regroup")) {
                    Text(if (view.regrouping) "Sorting…" else "Sort again")
                }
            }
        }
        if (!view.available) {
            Text("This server keeps no to-dos yet. Update the bridge to use them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        AddTodo(actions, Modifier.widthIn(max = 900.dp).fillMaxWidth())
        view.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (view.openCount == 0) {
            Text("Nothing to do. Add something above, or ask Hermes to add it from any chat.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        view.groups.forEach { g ->
            SectionCard(
                g.name ?: "Not sorted yet",
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().testTag("todo-group-${g.name ?: "none"}"),
                trailing = { Text("${g.items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            ) {
                g.items.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TodoEntry(t, expanded == t.id, view.groupNames, actions, onToggle = { toggle(t.id) })
                }
            }
        }
        if (view.done.isNotEmpty()) {
            SectionCard(
                "Done",
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().testTag("todo-done-section"),
                trailing = {
                    TextButton(onClick = { showDone = !showDone }, modifier = Modifier.testTag("todos-show-done")) {
                        Text(if (showDone) "Hide" else "Show ${view.done.size}")
                    }
                },
            ) {
                if (showDone) {
                    view.done.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TodoEntry(t, expanded == t.id, view.groupNames, actions, onToggle = { toggle(t.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTodo(actions: TalariaActions, modifier: Modifier) {
    var draft by remember { mutableStateOf("") }
    val add = {
        if (draft.isNotBlank()) actions.addTodo(draft)
        draft = ""
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(500) },
            placeholder = { Text("Add a to-do; Hermes sorts it into a group") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("todos-input"),
        )
        TextButton(onClick = add, enabled = draft.isNotBlank(), modifier = Modifier.testTag("todos-add")) { Text("Add") }
    }
}

/** One to-do: tick it, or tap it to see its comments and decide what to do with it. */
@Composable
private fun TodoEntry(t: TodoItem, open: Boolean, groups: List<String>, actions: TalariaActions, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("todo-entry-${t.id}")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle).testTag("todo-row-${t.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = t.done, onCheckedChange = { actions.setTodoDone(t.id, it) },
                modifier = Modifier.testTag("todo-check-${t.id}"))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    t.text,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val details = listOfNotNull(
                    t.due,
                    if (t.withAgent) "With Hermes…" else null,
                    t.comments.size.takeIf { it > 0 }?.let { if (it == 1) "1 comment" else "$it comments" },
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (open) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (open) TodoDetails(t, groups, actions)
    }
}

@Composable
private fun TodoDetails(t: TodoItem, groups: List<String>, actions: TalariaActions) {
    var comment by remember(t.id) { mutableStateOf("") }
    var editing by remember(t.id) { mutableStateOf(false) }
    var newGroup by remember(t.id) { mutableStateOf(false) }
    val send = {
        if (comment.isNotBlank()) actions.commentOnTodo(t.id, comment)
        comment = ""
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(12.dp).testTag("todo-details-${t.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        t.comments.forEach { c ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("todo-comment-${c.id}")) {
                Column(Modifier.weight(1f)) {
                    Text("${if (c.byAgent) "Hermes" else "You"} · ${c.time}", style = MaterialTheme.typography.labelMedium,
                        color = if (c.byAgent) Brand.Busy else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { actions.deleteTodoComment(t.id, c.id) }, modifier = Modifier.testTag("todo-uncomment-${c.id}")) {
                    Icon(TalariaIcons.Close, contentDescription = "Delete comment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it.take(2000) },
                placeholder = { Text("Add a comment") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).testTag("todo-comment-input-${t.id}"),
            )
            TextButton(onClick = send, enabled = comment.isNotBlank(), modifier = Modifier.testTag("todo-comment-add-${t.id}")) {
                Text("Comment")
            }
        }
        // what to do with it
        FlowButtons {
            when {
                t.done -> {}
                t.withAgent || t.conversationId != null -> TextButton(onClick = { t.conversationId?.let(actions::openConversation) },
                    modifier = Modifier.testTag("todo-chat-${t.id}")) { Text("Open chat") }
                else -> TextButton(onClick = { actions.handTodoToAgent(t.id) }, modifier = Modifier.testTag("todo-ask-${t.id}")) {
                    Text("Ask Hermes")
                }
            }
            if (!t.done) {
                Choice("Due", "todo-due-${t.id}", listOf("Today" to "today", "Tomorrow" to "tomorrow", "Next week" to "next week",
                    "No date" to null)) { actions.setTodoDue(t.id, it) }
            }
            val groupChoices: List<Pair<String, String?>> = buildList {
                groups.filter { it != t.group }.forEach { add(it to it) }
                add("New group…" to NEW_GROUP)
                if (t.group != null) add("No group" to null)
            }
            Choice("Group", "todo-group-${t.id}", groupChoices) { g ->
                if (g == NEW_GROUP) {
                    newGroup = true
                } else {
                    actions.setTodoGroup(t.id, g)
                }
            }
            TextButton(onClick = { editing = true }, modifier = Modifier.testTag("todo-edit-${t.id}")) { Text("Edit") }
            TextButton(onClick = { actions.deleteTodo(t.id) }, modifier = Modifier.testTag("todo-remove-${t.id}")) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (editing) {
        TextDialog("Edit to-do", t.text, 500, "todo-edit-field") { text ->
            editing = false
            if (text != null && text.isNotBlank()) actions.editTodo(t.id, text)
        }
    }
    if (newGroup) {
        TextDialog("New group", "", 40, "todo-group-field") { name ->
            newGroup = false
            if (name != null && name.isNotBlank()) actions.setTodoGroup(t.id, name)
        }
    }
}

private const val NEW_GROUP = "\u0000new"

/** A button that drops down a list of choices; picking one calls [onPick] with its value. */
@Composable
private fun Choice(label: String, tag: String, options: List<Pair<String, String?>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text("$label ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, modifier = Modifier.testTag("$tag-${text.lowercase()}"),
                    onClick = { open = false; onPick(value) })
            }
        }
    }
}

/** Buttons that wrap onto more lines on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowButtons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun TextDialog(title: String, initial: String, max: Int, tag: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(max) }, singleLine = max <= 40, modifier = Modifier.fillMaxWidth().testTag(tag))
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("$tag-save")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

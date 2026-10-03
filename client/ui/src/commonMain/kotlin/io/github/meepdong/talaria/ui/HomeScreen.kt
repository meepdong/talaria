package io.github.meepdong.talaria.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Home: today at a glance. */
@Composable
fun HomeScreen(screen: Screen.Chat, actions: TalariaActions) {
    val home = screen.home
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("home"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            DayCards(home, actions, wide)
            if (home.todosAvailable) TodoCard(home, actions, Modifier.fillMaxWidth().widthIn(max = 1120.dp))
            SectionCard(
                "Recent chats",
                modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp),
                trailing = { TextButton(onClick = { actions.selectTab(Tab.CHATS) }) { Text("All chats") } },
            ) {
                if (home.recent.isEmpty()) {
                    Text("No chats yet. Start one with Chat with Hermes below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                home.recent.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.openConversation(c.id) }
                            .padding(vertical = 8.dp).testTag("recent-${c.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (c.preview.isNotEmpty()) {
                                Text(c.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(if (c.running) "Replying…" else c.time, style = MaterialTheme.typography.labelMedium,
                            color = if (c.running) Brand.Busy else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoCard(home: HomeView, actions: TalariaActions, modifier: Modifier) {
    var draft by remember { mutableStateOf("") }
    val add = {
        if (draft.isNotBlank()) actions.addTodo(draft)
        draft = ""
    }
    SectionCard("To do", modifier = modifier.testTag("todos")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(500) },
                placeholder = { Text("Add a to-do") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f).testTag("todo-input"),
            )
            TextButton(onClick = add, enabled = draft.isNotBlank(), modifier = Modifier.testTag("todo-add")) { Text("Add") }
        }
        if (home.todos.isEmpty()) {
            Text("Nothing to do. Add something above, or hand it to Hermes once it's here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        home.todos.forEach { t ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
            TodoRow(t, actions)
        }
        if (home.doneEarlier > 0) {
            Text("${home.doneEarlier} done earlier", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TodoRow(t: TodoItem, actions: TalariaActions) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("todo-${t.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = t.done,
            onCheckedChange = { actions.setTodoDone(t.id, it) },
            modifier = Modifier.testTag("todo-done-${t.id}"),
        )
        Column(Modifier.weight(1f)) {
            Text(
                t.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            t.due?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (t.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            t.done -> {}
            t.withAgent -> TextButton(onClick = { t.conversationId?.let(actions::openConversation) },
                modifier = Modifier.testTag("todo-open-${t.id}")) { Text("With Hermes…", color = Brand.Busy) }
            t.conversationId != null -> TextButton(onClick = { actions.openConversation(t.conversationId) },
                modifier = Modifier.testTag("todo-open-${t.id}")) { Text("Open chat") }
            else -> TextButton(onClick = { actions.handTodoToAgent(t.id) },
                modifier = Modifier.testTag("todo-hand-${t.id}")) { Text("Ask Hermes") }
        }
        IconButton(onClick = { actions.deleteTodo(t.id) }, modifier = Modifier.testTag("todo-delete-${t.id}")) {
            Icon(TalariaIcons.Close, contentDescription = "Delete to-do", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

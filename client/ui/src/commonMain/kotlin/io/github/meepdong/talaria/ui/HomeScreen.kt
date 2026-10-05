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
import androidx.compose.material3.Button
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Home: today at a glance. "Needs you" stays on top; the other tiles come in [HomeView.order]. */
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
            Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (home.arranging) {
                    Button(onClick = actions::doneArrangingHome, modifier = Modifier.testTag("arrange-done")) { Text("Done") }
                }
            }
            home.update?.let { UpdateCard(it, actions) }
            NeedsYouCard(home, actions)
            val tiles = home.order.filter { it.shown(home) }
            val full = Modifier.fillMaxWidth().widthIn(max = 1120.dp)
            var i = 0
            while (i < tiles.size) {
                val tile = tiles[i]
                val pair = tiles.getOrNull(i + 1)?.takeIf { wide && !home.arranging && setOf(tile, it) == SIDE_BY_SIDE }
                if (pair != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 1120.dp)) {
                        HomeTileCard(tile, home, actions, Modifier.weight(1f), tiles)
                        HomeTileCard(pair, home, actions, Modifier.weight(1f), tiles)
                    }
                    i += 2
                } else {
                    HomeTileCard(tile, home, actions, full, tiles)
                    i += 1
                }
            }
        }
    }
}

/** A newer release from the bridge (§17): one tap downloads, checks and installs it. */
@Composable
private fun UpdateCard(update: UpdateBanner, actions: TalariaActions) {
    SectionCard(
        "Update available",
        modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("update-banner"),
        trailing = {
            Button(onClick = actions::installUpdate, enabled = !update.busy, modifier = Modifier.testTag("update-install")) {
                Text(if (update.busy) "Installing…" else "Update")
            }
        },
    ) {
        Text("Talaria ${update.version} is ready to install.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        update.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        update.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("update-status")) }
    }
}

/** On a wide screen, Next up and Automations on share a row while they're neighbours. */
private val SIDE_BY_SIDE = setOf(HomeTile.NEXT, HomeTile.AUTOMATIONS)

private fun HomeTile.shown(home: HomeView) = when (this) {
    HomeTile.DAY, HomeTile.NEXT, HomeTile.AUTOMATIONS -> home.automationsAvailable
    HomeTile.TODOS -> home.todosAvailable
    HomeTile.RECENT -> true
}

/** What Home adds to a tile's card: a long-press on its title starts rearranging, then ↑ and ↓ replace its button. */
class TileChrome(val trailing: (@Composable () -> Unit)? = null, val onTitleLongClick: (() -> Unit)? = null)

@Composable
private fun HomeTileCard(tile: HomeTile, home: HomeView, actions: TalariaActions, m: Modifier, tiles: List<HomeTile>) {
    val chrome = if (!home.arranging) {
        TileChrome(onTitleLongClick = actions::startArrangingHome)
    } else {
        TileChrome(trailing = {
            val at = tiles.indexOf(tile)
            Row {
                IconButton(onClick = { actions.moveHomeTile(tile, up = true) }, enabled = at > 0,
                    modifier = Modifier.testTag("move-up-${tile.name}")) {
                    Text("↑", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { contentDescription = "Move ${tile.label} up" })
                }
                IconButton(onClick = { actions.moveHomeTile(tile, up = false) }, enabled = at < tiles.size - 1,
                    modifier = Modifier.testTag("move-down-${tile.name}")) {
                    Text("↓", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { contentDescription = "Move ${tile.label} down" })
                }
            }
        })
    }
    when (tile) {
        HomeTile.DAY -> YourDayCard(home, actions, m, chrome)
        HomeTile.NEXT -> NextUpCard(home, m, chrome)
        HomeTile.AUTOMATIONS -> AutomationsOnCard(home, actions, m, chrome)
        HomeTile.TODOS -> TodoCard(home, actions, m, chrome)
        HomeTile.RECENT -> RecentChatsCard(home, actions, m, chrome)
    }
}

@Composable
private fun RecentChatsCard(home: HomeView, actions: TalariaActions, modifier: Modifier, tile: TileChrome) {
    SectionCard(
        "Recent chats",
        modifier = modifier.testTag("recent-chats"),
        trailing = tile.trailing ?: { TextButton(onClick = { actions.selectTab(Tab.CHATS) }) { Text("All chats") } },
        onTitleLongClick = tile.onTitleLongClick,
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

@Composable
private fun TodoCard(home: HomeView, actions: TalariaActions, modifier: Modifier, tile: TileChrome) {
    var draft by remember { mutableStateOf("") }
    val add = {
        if (draft.isNotBlank()) actions.addTodo(draft)
        draft = ""
    }
    SectionCard(
        "To do",
        modifier = modifier.testTag("todos"),
        trailing = tile.trailing ?: {
            TextButton(onClick = { actions.selectTab(Tab.TODOS) }, modifier = Modifier.testTag("all-todos")) { Text("All to-dos") }
        },
        onTitleLongClick = tile.onTitleLongClick,
    ) {
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
        if (home.moreTodos > 0) {
            TextButton(onClick = { actions.selectTab(Tab.TODOS) }, modifier = Modifier.testTag("more-todos")) {
                Text(if (home.moreTodos == 1) "1 more to do" else "${home.moreTodos} more to do")
            }
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

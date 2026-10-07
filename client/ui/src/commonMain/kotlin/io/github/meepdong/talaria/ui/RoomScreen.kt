package io.github.meepdong.talaria.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A colour per member, so speakers are told apart at a glance. */
private val SPEAKER_COLORS = listOf(Color(0xFF2E7D6B), Color(0xFF8E5BB5), Color(0xFFB5652B), Color(0xFF3D6FB5), Color(0xFFB53D6F), Color(0xFF6B8E23))

internal fun speakerColor(name: String, members: List<Pair<String, String>>): Color {
    val i = members.indexOfFirst { it.first == name }
    return SPEAKER_COLORS[(if (i >= 0) i else name.hashCode().mod(SPEAKER_COLORS.size)) % SPEAKER_COLORS.size]
}

/** Handles an "@…" being typed could mean, for the room composer: members, then @all. */
internal fun roomMentions(text: String, members: List<Pair<String, String>>): List<Pair<String, String>> {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return emptyList()
    val typed = text.substring(at + 1)
    if (typed.any { it.isWhitespace() }) return emptyList()
    return (members + ("Everyone" to "all")).filter { (name, handle) ->
        handle.startsWith(typed, ignoreCase = true) || name.replace(" ", "").startsWith(typed, ignoreCase = true)
    }
}

/** A group chat (§18.2): who said what, what's going on, approvals, and the owner's composer. */
@Composable
fun RoomPane(room: RoomView, actions: TalariaActions, showBack: Boolean, menu: @Composable () -> Unit, modifier: Modifier = Modifier) {
    var thread by remember(room.id) { mutableStateOf<RoomMessageItem?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) TextButton(onClick = actions::closeRoom, modifier = Modifier.testTag("room-back")) { Text("←") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("👥 " + room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("room-title"))
                Text(room.members.joinToString(", ") { it.first }, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RoomMenu(room, actions)
            menu()
        }
        HorizontalDivider()
        if (room.stuck > 0) {
            Card(Modifier.fillMaxWidth().padding(8.dp).testTag("room-stuck")) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (room.stuck == 1) "A member's turn didn't finish." else "${room.stuck} members' turns didn't finish.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions::retryRoom, modifier = Modifier.testTag("room-retry")) { Text("Retry") }
                }
            }
        }
        room.notice?.let { notice ->
            Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f).testTag("room-notice"))
                    TextButton(onClick = actions::dismissRoomNotice) { Text("OK") }
                }
            }
        }
        val list = rememberLazyListState()
        LaunchedEffect(room.id, room.messages.size, room.approvals.size) {
            if (room.messages.isNotEmpty()) list.scrollToItem(room.messages.size + room.approvals.size)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("room-messages"), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (room.messages.isEmpty()) {
                item(key = "empty") {
                    Text(if (room.loading) "Loading…" else "Say something to the group. @mention members to ask them; @all for everyone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
            }
            items(room.messages, key = { it.key }) { msg -> RoomMessageRow(msg, room.members, onReply = { thread = msg }) }
            items(room.approvals, key = { "approval:" + it.id }) { a -> RoomApprovalCard(a, actions) }
            if (room.working) {
                item(key = "working") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("room-working")) {
                        Text("Members are taking turns…", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = actions::stopRoom, modifier = Modifier.testTag("room-stop")) { Text("Stop") }
                    }
                }
            }
        }
        RoomComposer(room, thread, onCancelThread = { thread = null }, onSend = { text ->
            actions.sendRoom(text, thread?.threadId)
            thread = null
        })
    }
}

@Composable
private fun RoomMessageRow(m: RoomMessageItem, members: List<Pair<String, String>>, onReply: () -> Unit) {
    when (m.kind) {
        "note" -> Text("— ${m.text} —", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().testTag("room-note-${m.key}"))
        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(start = 48.dp).clickable(onClick = onReply).testTag("room-msg-${m.key}")) {
                Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        else -> Column(Modifier.fillMaxWidth().clickable(onClick = onReply).testTag("room-msg-${m.key}")) {
            Text(m.speaker, style = MaterialTheme.typography.labelMedium, color = speakerColor(m.speaker, members))
            MarkdownText(m.text)
            m.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun RoomApprovalCard(a: RoomApprovalItem, actions: TalariaActions) {
    Card(Modifier.fillMaxWidth().testTag("room-approval-${a.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${a.member} asks to run:", style = MaterialTheme.typography.labelMedium)
            a.command?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            a.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.approveRoom(a.id, "once") }, modifier = Modifier.testTag("room-allow")) { Text("Allow once") }
                OutlinedButton(onClick = { actions.approveRoom(a.id, "deny") }, modifier = Modifier.testTag("room-deny")) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun RoomComposer(room: RoomView, thread: RoomMessageItem?, onCancelThread: () -> Unit, onSend: (String) -> Unit) {
    var field by remember(room.id) { mutableStateOf(TextFieldValue("")) }
    fun set(t: String) { field = TextFieldValue(t, TextRange(t.length)) }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        thread?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("room-thread")) {
                Text("↳ Replying to ${t.speaker}: ${t.text.replace('\n', ' ')}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelThread, modifier = Modifier.testTag("room-thread-cancel")) { Text("✕") }
            }
        }
        val mentions = roomMentions(field.text, room.members)
        if (mentions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("room-mentions")) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    mentions.forEach { (name, handle) ->
                        Text("@$handle  $name", Modifier.fillMaxWidth().clickable {
                            set(field.text.substring(0, field.text.lastIndexOf('@')) + "@$handle ")
                        }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("room-mention-$handle"))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = field, onValueChange = { field = it }, maxLines = 6,
                placeholder = { Text("Message the group, @ for a member") }, modifier = Modifier.weight(1f).testTag("room-composer"))
            Button(onClick = {
                val text = field.text
                if (text.isNotBlank()) {
                    onSend(text)
                    set("")
                }
            }, enabled = field.text.isNotBlank(), modifier = Modifier.testTag("room-send")) { Text("Send") }
        }
    }
}

/** A new group chat: a name and two to six members. */
@Composable
fun NewRoomDialog(candidates: List<Pair<String, String>>, onDone: (String, List<String>) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("new-room"),
        title = { Text("New group chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().testTag("new-room-name"))
                Text("Members (2 to 6)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                candidates.forEach { (id, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        picked = if (id in picked) picked - id else if (picked.size < 6) picked + id else picked
                    }.testTag("new-room-member-$id")) {
                        Checkbox(checked = id in picked, onCheckedChange = null)
                        Text(label, Modifier.width(220.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDone(name, picked) }, enabled = name.isNotBlank() && picked.size in 2..6,
                modifier = Modifier.testTag("new-room-create")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A group chat's ⋮ (§18.2): rename it, or end it for good (a second tap confirms). */
@Composable
private fun RoomMenu(room: RoomView, actions: TalariaActions) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember(room.id) { mutableStateOf(false) }
    var sure by remember(room.id) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true; sure = false }, modifier = Modifier.testTag("room-menu")) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.testTag("room-rename"), onClick = { open = false; renaming = true })
            DropdownMenuItem(text = { Text(if (sure) "Tap again: end it for good, everywhere" else "End this group chat",
                color = MaterialTheme.colorScheme.error) }, modifier = Modifier.testTag("room-disband"),
                onClick = { if (sure) { open = false; actions.disbandRoom() } else sure = true })
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(room.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename the group chat") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, modifier = Modifier.testTag("room-name")) },
            confirmButton = { TextButton(onClick = { renaming = false; actions.renameRoom(name) }, enabled = name.isNotBlank(),
                modifier = Modifier.testTag("room-name-save")) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
}

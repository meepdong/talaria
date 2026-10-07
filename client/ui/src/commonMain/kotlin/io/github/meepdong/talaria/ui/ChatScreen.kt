package io.github.meepdong.talaria.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Two panes on a wide window (desktop), list then conversation on a phone (UI.md §3–4).
 * [menu] is the ☰ button, shown at the top of the list, and of a conversation on a phone.
 */
@Composable
fun ChatHome(view: ChatView, actions: TalariaActions, menu: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                ConversationList(view, actions, menu, Modifier.width(320.dp).fillMaxHeight())
                VerticalDivider()
                val room = view.room
                if (room != null) {
                    RoomPane(room, actions, showBack = false, menu = {}, modifier = Modifier.weight(1f).fillMaxHeight())
                } else {
                    Conversation(view, actions, showBack = false, menu = {}, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else if (view.room != null) {
            RoomPane(view.room, actions, showBack = true, menu = menu, modifier = Modifier.fillMaxSize())
        } else if (view.conversationOpen) {
            Conversation(view, actions, showBack = true, menu = menu, modifier = Modifier.fillMaxSize())
        } else {
            ConversationList(view, actions, menu, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ConnectionDot(view: ChatView, actions: TalariaActions, dotOnly: Boolean = false) {
    Row(
        Modifier.clickable(onClick = actions::showStatus).padding(8.dp).testTag("connection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(view.connection.color(), CircleShape))
        if (!dotOnly) {
            Spacer(Modifier.width(6.dp))
            Text(view.connectionSummary, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationList(view: ChatView, actions: TalariaActions, menu: @Composable () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            ConnectionDot(view, actions)
            menu()
        }
        Button(onClick = actions::newConversation, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("new-chat")) {
            Text("✎ New chat")
        }
        if (view.bots.isNotEmpty()) BotStrip(view.bots, view.openBot?.id, actions)
        view.listMessage?.let {
            Text(it, Modifier.padding(16.dp).testTag("list-message"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var showArchived by remember { mutableStateOf(false) }
        var newRoom by remember { mutableStateOf(false) }
        if (newRoom) {
            NewRoomDialog(view.roomCandidates, onDone = { name, members -> newRoom = false; actions.createRoom(name, members) },
                onCancel = { newRoom = false })
        }
        LazyColumn(Modifier.weight(1f).testTag("conversations")) {
            if (view.rooms.isNotEmpty() || view.roomCandidates.size >= 2) {
                item(key = "rooms-head") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Group chats", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (view.roomCandidates.size >= 2) {
                            TextButton(onClick = { newRoom = true }, modifier = Modifier.testTag("new-room-button")) { Text("+ New group") }
                        }
                    }
                }
                items(view.rooms, key = { "room-" + it.id }) { r ->
                    Column(Modifier.fillMaxWidth().background(if (view.room?.id == r.id) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface).clickable { actions.openRoom(r.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp).testTag("room-${r.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👥 " + r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (r.needsYou) {
                                Text("needs you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(end = 6.dp).testTag("room-needs-you-${r.id}"))
                            }
                            Text(if (r.working) "⏳" else r.time, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(r.preview.ifEmpty { r.members }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                item(key = "chats-head") {
                    Text("Chats", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
            }
            items(view.conversations, key = { it.id }) { c ->
                ConversationRow(c, selected = c.id == view.openId, actions)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            if (view.archived.isNotEmpty()) {
                item(key = "archived-toggle") {
                    TextButton(onClick = { showArchived = !showArchived },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("archived-chats")) {
                        Text((if (showArchived) "Hide archived" else "Archived") + " (${view.archived.size})")
                    }
                }
                if (showArchived) {
                    items(view.archived, key = { "archived-${it.id}" }) { c ->
                        ConversationRow(c, selected = c.id == view.openId, actions, archived = true)
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/** Hermes's bots (§18.1), a tap away: each opens its one chat. */
@Composable
private fun BotStrip(bots: List<BotItem>, open: String?, actions: TalariaActions) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp)
        .testTag("bots"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        bots.forEach { b ->
            Column(Modifier.width(64.dp).clickable { actions.openBot(b.id) }.padding(vertical = 4.dp).testTag("bot-${b.handle}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // a ring while the bot is at work (its chat, a group chat, a board task, a routine)
                val ring = if (b.busy.isNotEmpty()) Modifier.border(2.dp, Brand.Busy, CircleShape).padding(3.dp).testTag("bot-busy-${b.handle}")
                           else Modifier
                Surface(shape = CircleShape, modifier = ring.size(44.dp),
                    color = if (b.id == open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (b.id == open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) {
                    val pic = b.image
                    if (pic != null) {
                        androidx.compose.foundation.Image(pic, b.name, Modifier.size(44.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    } else {
                        Box(contentAlignment = Alignment.Center) { Text(b.initials, style = MaterialTheme.typography.titleSmall) }
                    }
                }
                Text(if (b.busy.isNotEmpty()) "working…" else b.name, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    color = if (b.busy.isNotEmpty()) Brand.Busy else androidx.compose.ui.graphics.Color.Unspecified)
            }
        }
        // a new bot (§18.8)
        Column(Modifier.width(64.dp).clickable { actions.newBot() }.padding(vertical = 4.dp).testTag("new-bot"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, modifier = Modifier.size(44.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.titleMedium) }
            }
            Text("New bot", style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * A chat in the list: swipe left to archive it (right on an archived one brings it back), right to pin it;
 * long press (or right click) to rename, pin, archive or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(c: ConversationItem, selected: Boolean, actions: TalariaActions, archived: Boolean = false) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    SwipeRow(
        key = c.id,
        left = if (archived) null else SwipeAction("Archive", SwipeColors.Archive) { actions.archiveConversation(c.id, true) },
        right = if (archived) SwipeAction("Unarchive", SwipeColors.Read) { actions.archiveConversation(c.id, false) }
                else SwipeAction(if (c.pinned) "Unpin" else "Pin", SwipeColors.Read) { actions.pinConversation(c.id, !c.pinned) },
        modifier = Modifier.testTag("swipe-chat-${c.id}"),
    ) { Box {
        Column(
            Modifier.fillMaxWidth().background(bg)
                .combinedClickable(onLongClick = { menu = true }) { actions.openConversation(c.id) }
                .onSecondaryClick { menu = true }
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("conversation-${c.id}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (c.pinned) "📌 " else "") + (if (c.bot) "🤖 " else "") + c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (c.running) "⏳" else c.time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.preview.isNotEmpty()) {
                Text(c.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; confirmDelete = false }) {
            DropdownMenuItem(text = { Text("Rename") }, modifier = Modifier.testTag("chat-rename"),
                onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin to top") }, modifier = Modifier.testTag("chat-pin"),
                onClick = { menu = false; actions.pinConversation(c.id, !c.pinned) })
            DropdownMenuItem(text = { Text(if (archived) "Unarchive" else "Archive") }, modifier = Modifier.testTag("chat-archive"),
                onClick = { menu = false; actions.archiveConversation(c.id, !archived) })
            DropdownMenuItem(
                text = { Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.testTag("chat-delete"),
                onClick = {
                    if (confirmDelete) {
                        menu = false
                        confirmDelete = false
                        actions.deleteConversation(c.id)
                    } else {
                        confirmDelete = true
                    }
                },
            )
        }
    } }
    if (renaming) {
        RenameDialog(c.title, onDone = { title ->
            renaming = false
            if (title != null) actions.renameConversation(c.id, title)
        })
    }
}

@Composable
private fun Conversation(
    view: ChatView, actions: TalariaActions, showBack: Boolean, menu: @Composable () -> Unit, modifier: Modifier,
) {
    var renaming by remember(view.openId) { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                TextButton(onClick = actions::closeConversation, modifier = Modifier.testTag("back")) { Text("←") }
            }
            val title = (if (view.openBot != null) "🤖 " else "") + view.title
            if (showBack) {
                // a phone: the name gets the width; the model is a small line under it, the connection a dot (#53)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("title"))
                    view.model?.let { ModelChip(it, view.modelGroups, view.modelPicker, actions, small = true) }
                }
                ConnectionDot(view, actions, dotOnly = true)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("title"))
            }
            if (view.openId != null) {
                IconButton(onClick = actions::newConversation, modifier = Modifier.testTag("new-chat-here")) {
                    Icon(TalariaIcons.Plus, "New chat")
                }
            }
            if (!showBack) view.model?.let { ModelChip(it, view.modelGroups, view.modelPicker, actions) }
            ConversationMenu(view.openId, view.voice, actions, onRename = { renaming = true }, botId = view.openBot?.id)
            menu()
        }
        HorizontalDivider()
        view.openBot?.let { bot -> BotAtWork(bot, view.openId, actions) }

        view.notice?.let { notice ->
            Card(Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f).testTag("notice"))
                    TextButton(onClick = actions::dismissNotice) { Text("OK") }
                }
            }
        }

        Messages(view, actions, Modifier.weight(1f))
        Composer(view, actions)
    }
    view.status?.let { st ->
        AlertDialog(
            onDismissRequest = actions::closeStatus,
            title = { Text(st.title) },
            text = {
                Column(Modifier.testTag("status-lines"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.lines.forEach { (label, value) ->
                        Row {
                            Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = actions::closeStatus) { Text("Close") } },
        )
    }
    if (renaming && view.openId != null) {
        RenameDialog(view.title, onDone = { title ->
            renaming = false
            if (title != null) actions.renameConversation(view.openId, title)
        })
    }
}

/** The model this chat uses; tap it for a searchable dropdown of the models Hermes has keys for (Hermes's /model). */
@Composable
private fun ModelChip(label: String, groups: List<ModelGroup>, picker: String?, actions: TalariaActions, small: Boolean = false) {
    var query by remember(picker != null) { mutableStateOf(picker.orEmpty()) }
    Box {
        if (small) {
            Text("$label ▾", Modifier.clickable { actions.openModelPicker() }.testTag("model"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(50)).clickable { actions.openModelPicker() }
                    .testTag("model")) {
                Text("$label ▾", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = picker != null, onDismissRequest = actions::closeModelPicker,
            modifier = Modifier.widthIn(min = 260.dp, max = 340.dp).heightIn(max = 460.dp).testTag("model-picker")) {
            OutlinedTextField(query, { query = it.take(60) }, singleLine = true, placeholder = { Text("Search models") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("model-search"))
            val shown = filterModels(groups, query)
            when {
                groups.isEmpty() -> Text("Loading models…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.isEmpty() -> Text("No model matches \"$query\"", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { g ->
                Text(g.name, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(m.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (m.selected) FontWeight.SemiBold else null)
                        },
                        trailingIcon = { if (m.selected) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.testTag("model-${m.model}"),
                        onClick = { actions.pickModel(m.provider, m.model) },
                    )
                }
            }
        }
    }
}

/** The picker's groups, keeping only models whose name or label contains [query]. */
internal fun filterModels(groups: List<ModelGroup>, query: String): List<ModelGroup> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { g ->
        val models = g.models.filter { q in it.model.lowercase() || q in it.label.lowercase() || q in g.name.lowercase() }
        if (models.isEmpty()) null else g.copy(models = models)
    }
}

@Composable
private fun ConversationMenu(id: String?, voice: VoiceView, actions: TalariaActions, onRename: () -> Unit, botId: String? = null) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }
    if (pickVoice) VoicePicker(voice, actions) { pickVoice = false }
    if (id == null && !voice.canSpeak && !voice.canDictate) return
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("menu")) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; confirmDelete = false }) {
            if (voice.canSpeak) {
                DropdownMenuItem(text = { Text((if (voice.readAloud) "✓ " else "") + "Read replies aloud") },
                    modifier = Modifier.testTag("read-aloud"),
                    onClick = { open = false; actions.setReadAloud(!voice.readAloud) })
            }
            if (voice.canDictate) {
                DropdownMenuItem(text = { Text((if (voice.autoSend) "✓ " else "") + "Send dictation right away") },
                    modifier = Modifier.testTag("auto-send"),
                    onClick = { open = false; actions.setAutoSend(!voice.autoSend) })
            }
            if (voice.canDictate) {
                DropdownMenuItem(text = { Text("Talk waits: ${voice.talkWait.label}") },
                    modifier = Modifier.testTag("talk-wait"),
                    onClick = { actions.setTalkWait(voice.talkWait.next()) })
                DropdownMenuItem(text = { Text("Talk voice…") }, modifier = Modifier.testTag("talk-voice"),
                    onClick = { open = false; pickVoice = true; actions.loadTalkVoices() })
            }
            if (id == null) return@DropdownMenu
            if (botId != null) {
                DropdownMenuItem(text = { Text("Bot settings") }, modifier = Modifier.testTag("bot-settings"),
                    onClick = { open = false; actions.editBot(botId) })
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text(if (confirmDelete) "Tap again to delete" else "Delete") },
                onClick = {
                    if (confirmDelete) {
                        open = false
                        confirmDelete = false
                        actions.deleteConversation(id)
                    } else {
                        confirmDelete = true
                    }
                },
                modifier = Modifier.testTag("delete"),
            )
        }
    }
}

/** The talker's voice: hear each one (▶), pick one for every device. */
@Composable
private fun VoicePicker(voice: VoiceView, actions: TalariaActions, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Talk voice") },
        text = {
            if (voice.talkVoices.isEmpty()) {
                Text("Asking the server…", modifier = Modifier.testTag("voices-loading"))
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    voice.talkVoices.forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.setTalkVoice(id) }.testTag("voice-$id"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == voice.talkVoice, onClick = { actions.setTalkVoice(id) })
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { actions.previewTalkVoice(id) }, modifier = Modifier.testTag("hear-$id")) {
                                Text(if (voice.previewing == id) "…" else "▶")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename chat") },
        text = { OutlinedTextField(text, { text = it.take(100) }, singleLine = true, modifier = Modifier.testTag("rename-field")) },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun Messages(view: ChatView, actions: TalariaActions, modifier: Modifier) {
    val list = rememberLazyListState()
    val last = view.messages.lastOrNull()
    // Follow the conversation's bottom as it grows, but only while the user is there: dragging up to
    // read stops it, and scrolling back to the end (or sending) starts it again.
    var follow by remember(view.openId) { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start) follow = false
            if (i is DragInteraction.Stop || i is DragInteraction.Cancel) follow = !list.canScrollForward
        }
    }
    LaunchedEffect(list) {
        // a fling that ends at the bottom follows again
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, more) -> if (!moving && !more) follow = true }
    }
    LaunchedEffect(view.openId, view.messages.size) {
        if (last?.fromUser == true) follow = true  // the user just sent: show it
    }
    LaunchedEffect(view.openId, view.messages.size, last?.text?.length, last?.tools?.size, view.opsApprovals.size, follow) {
        if (follow && view.messages.isNotEmpty()) {
            // to the very end, not to the top of the last message: a long reply keeps its newest line in view
            list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            list.scrollBy(100_000f)
        }
    }
    Box(modifier.fillMaxWidth()) {
        if (view.messages.isEmpty() && !view.loading) {
            Text(
                if (view.openId == null) "Ask Hermes anything." else view.historyError ?: "No messages yet.",
                Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize().testTag("messages"), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "top") {
                when {
                    view.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    view.hasOlder -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = actions::loadOlder, modifier = Modifier.testTag("older")) { Text("Load older messages") }
                    }
                    view.historyError != null && view.messages.isNotEmpty() ->
                        Text(view.historyError, color = MaterialTheme.colorScheme.error)
                }
            }
            items(view.messages, key = { it.key }) { m ->
                if (m.worker != null) {
                    JobCard(m, actions)
                } else {
                    WithMessageMenu(m, view, actions) {
                        MessageBubble(m, view.voice, actions, view.openingFile, view.openingProgress, view.openBot?.name ?: "Hermes")
                    }
                }
            }
            items(view.asides, key = { "aside:" + it.id }) { a -> AsideCard(a, actions) }
            // server operations (PROTOCOL §10.8): where the owner is looking, below the newest message
            items(view.opsApprovals, key = { "ops:" + it.requestId }) { a -> OpsApprovalCard(a, actions) }
            items(view.opsResults, key = { "opsresult:" + it.requestId }) { r -> OpsResultCard(r, actions) }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageItem, voice: VoiceView, actions: TalariaActions, opening: String? = null,
                          openingProgress: Float? = null, speaker: String = "Hermes") {
    if (m.fromUser) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            m.attachments.forEach { a ->
                AttachmentView(a, Modifier.padding(bottom = 4.dp).testTag("attachment-${m.key}"))
            }
            if (m.text.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp).testTag("user-${m.key}")) {
                    Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            val footer = when (m.state) {
                ItemState.SENDING -> m.progress?.let { "Uploading ${(it * 100).toInt()}%" } ?: "Sending…"
                ItemState.QUEUED -> "Queued: sends when the reply ends"
                ItemState.CANCELLED -> "Removed from the queue"
                ItemState.NOT_SENT -> "Not sent: ${m.error ?: "try again"}"
                else -> m.time
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                footer?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (m.state == ItemState.NOT_SENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.state == ItemState.NOT_SENT) {
                    TextButton(onClick = { actions.retryMessage(m.key) }, modifier = Modifier.testTag("retry")) { Text("Retry") }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("reply-${m.key}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.tools.forEach { t ->
            val mark = when (t.state) { "completed" -> "✓"; "failed" -> "✗"; else -> "…" }
            Text("🔧 ${t.name} $mark", style = MaterialTheme.typography.bodySmall,
                color = if (t.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        m.commentary?.takeIf { m.approval == null }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val approval = m.approval
        val turn = m.turnId
        if (approval != null && turn != null) {
            ApprovalCard(approval) { choice -> actions.approve(turn, choice) }
        } else if (m.waitingForApproval) {
            Text("Waiting for approval in Hermes", style = MaterialTheme.typography.bodySmall,
                color = Brand.Brass, modifier = Modifier.testTag("approval"))
        }
        if (m.text.isNotEmpty()) MarkdownText(m.text)
        m.attachments.forEach { a ->
            AgentFile(a, actions, Modifier.testTag("agent-file-${m.key}"), openingProgress.takeIf { a.path != null && a.path == opening } ?: -1f)
        }
        when (m.state) {
            ItemState.STREAMING -> if (m.text.isEmpty() && m.tools.isEmpty() && m.commentary == null) {
                Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ItemState.FAILED -> Text("✗ ${m.error ?: "The reply failed"}", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reply-error"))
            ItemState.CANCELLED -> Text("Stopped", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                m.time?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (voice.canSpeak && m.text.isNotBlank()) {
                    TextButton(onClick = { actions.speak(m.key, m.text) }, modifier = Modifier.testTag("speak-${m.key}")) {
                        Text(if (voice.speakingKey == m.key) "■" else "🔊")
                    }
                }
            }
        }
    }
}

/** A /btw side question and its answer, beside the conversation rather than in it. */
@Composable
private fun AsideCard(a: AsideItem, actions: TalariaActions) {
    Card(Modifier.fillMaxWidth().testTag("aside-${a.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.command) {
                    Text(a.question, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                } else {
                    Text("By the way: ${a.question}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        fontStyle = FontStyle.Italic)
                }
                TextButton(onClick = { actions.dismissAside(a.id) }, modifier = Modifier.testTag("dismiss-aside")) { Text("✕") }
            }
            when {
                a.error != null -> Text(a.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                a.answer == null -> Text("Thinking…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.command -> Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("command-output")) {
                    MarkdownText(a.answer)
                }
                else -> MarkdownText(a.answer)
            }
        }
    }
}

/** A photo as a thumbnail when its bytes are here, otherwise a chip with its name. */
/** A file the agent sent: its name and size, and Open (which also lets the system share it). */
@Composable
private fun AgentFile(a: AttachmentChip, actions: TalariaActions, modifier: Modifier = Modifier, opening: Float = -1f) {
    val root = a.root
    val path = a.path
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier.widthIn(max = 420.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (a.isImage) "🖼 " else "📄 ") + a.name + (a.detail?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (root != null && path != null) {
                TextButton(onClick = { actions.openAttachment(root, path, a.name, a.mime ?: "application/octet-stream") },
                    enabled = opening < 0f, modifier = Modifier.testTag("open-file")) {
                    Text(if (opening < 0f) "Open" else "Opening ${(opening * 100).toInt()}%")
                }
            }
        }
    }
}

@Composable
private fun AttachmentView(a: AttachmentChip, modifier: Modifier = Modifier) {
    val image = a.image
    if (image != null) {
        Image(image, contentDescription = a.name, contentScale = ContentScale.Fit,
            modifier = modifier.widthIn(max = 280.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier) {
            Text((if (a.isImage) "📷 " else "📎 ") + a.name + (a.detail?.let { " · $it" } ?: ""),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Composer(view: ChatView, actions: TalariaActions) {
    // the text with its cursor: text set here (a picked @bot or /command, dictation) puts the cursor at its end
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    fun setText(t: String) { field = TextFieldValue(t, TextRange(t.length)) }
    val ready = view.canSend && (text.isNotBlank() || view.pending.isNotEmpty())
    fun send() {
        val now = field.text  // what's there now, also right after dictation set it
        if (view.canSend && (now.isNotBlank() || view.pending.isNotEmpty())) {
            actions.sendMessage(now)
            setText("")
        }
    }
    val voice = view.voice
    // dictation lands in the composer, to edit before sending unless auto-send is on
    LaunchedEffect(voice.dictation?.id) {
        val d = voice.dictation ?: return@LaunchedEffect
        setText(if (d.replace) d.text.take(32000) else listOf(field.text.trimEnd(), d.text).filter { it.isNotEmpty() }.joinToString(" ").take(32000))
        actions.dictationTaken(d.id)
        if (d.send) send()
    }
    voice.talk?.let { phase ->
        TalkBar(phase, voice.heard, voice.level, actions)
        return
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        view.composerHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(4.dp).testTag("composer-hint"))
        }
        if (voice.speakingKey != null) {
            TextButton(onClick = actions::stopSpeaking, modifier = Modifier.testTag("stop-speaking")) { Text("🔊 Stop reading") }
        }
        view.helpers.forEach { h -> HelperRow(h, actions) }
        if (view.editing) EditingBanner(actions)
        val mentions = mentionSuggestions(text, view.bots)
        if (mentions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("mentions")) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    mentions.forEach { b ->
                        Row(Modifier.fillMaxWidth().clickable { setText("@${b.handle} ") }.padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("mention-${b.handle}"), verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖 ${b.name}", Modifier.width(150.dp), style = MaterialTheme.typography.labelLarge)
                            Text(b.description ?: "@${b.handle}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        val suggestions = Command.suggestions(text, view.hermesCommands)
        if (suggestions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("commands")) {
                // Talaria's own and Hermes's (§18.3): scrolls when they don't fit
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    suggestions.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { setText("/${c.name} ") }.padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("command-${c.name}"), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.usage, Modifier.width(150.dp), style = MaterialTheme.typography.labelLarge)
                            Text(c.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (voice.listening) {
            Text("🎤 " + voice.heard.ifEmpty { "Listening…" }, style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp).testTag("heard"))
        }
        if (view.pending.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp).testTag("pending"),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                view.pending.forEachIndexed { i, a ->
                    Box {
                        AttachmentView(a, Modifier.heightIn(max = 72.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                .clickable { actions.removeAttachment(i) }.testTag("remove-$i")) {
                            Box(contentAlignment = Alignment.Center) { Text("✕", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // one rounded field with 📎 and 🎤 inside it, so the text gets the width on a phone
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f)) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                    if (view.canAttach) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = view.canAttachMore,
                                modifier = Modifier.padding(vertical = 4.dp).testTag("attach")) { Text("📎") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Photo") }, modifier = Modifier.testTag("attach-photo"),
                                    onClick = { menu = false; actions.attachFiles(photos = true) })
                                DropdownMenuItem(text = { Text("File") }, modifier = Modifier.testTag("attach-file"),
                                    onClick = { menu = false; actions.attachFiles(photos = false) })
                            }
                        }
                    }
                    TextField(
                        value = field,
                        onValueChange = { field = if (it.text.length > 32000) it.copy(text = it.text.take(32000)) else it },
                        placeholder = {
                            Text(when {
                                view.runningTurnId != null -> "Sends after this reply"
                                view.openBot != null -> "Message ${view.openBot.name}"
                                view.bots.isNotEmpty() -> "Message Hermes, / for commands, @ for bots"
                                else -> "Message Hermes, or / for commands"
                            },
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("composer").onPreviewKeyEvent { e ->
                            // Enter sends, Shift+Enter starts a new line (hardware keyboards)
                            if (e.key == Key.Enter && !e.isShiftPressed) {
                                if (e.type == KeyEventType.KeyDown) send()
                                true
                            } else {
                                false
                            }
                        },
                    )
                    if (voice.canDictate) {
                        // tap: Talk, a spoken conversation in this chat; long press: dictate into the box to edit first
                        Box(
                            Modifier.padding(vertical = 4.dp).size(48.dp).clip(CircleShape)
                                .combinedClickable(
                                    onClickLabel = if (voice.listening) "Stop dictating" else "Talk",
                                    onLongClickLabel = "Dictate",
                                    onLongClick = actions::toggleDictation,
                                    onClick = { if (voice.listening) actions.toggleDictation() else actions.talk() },
                                ).testTag("dictate"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (voice.listening) "■" else "🎤")
                        }
                    }
                }
            }
            val running = view.runningTurnId
            if (running != null) {
                FilledTonalIconButton(onClick = { actions.stopReply(running) },
                    modifier = Modifier.padding(bottom = 2.dp).size(48.dp).testTag("stop")) { Text("■") }
            }
            FilledIconButton(onClick = ::send, enabled = ready,
                modifier = Modifier.padding(bottom = 2.dp).size(48.dp).testTag("send")) {
                Text("➤")
            }
        }
    }
}

/** Hermes wants to run something it flags as risky; any paired device can answer (spec §9). */
@OptIn(ExperimentalLayoutApi::class)
/**
 * A job the Talk voice gave a worker (§9): one line ("🔧 Hermes · working · find flights to Goa"), tap to open the
 * brief and the worker's report. An approval it waits for shows even while it's folded.
 */
@Composable
private fun JobCard(m: MessageItem, actions: TalariaActions) {
    var open by remember(m.key) { mutableStateOf(false) }
    val report = m.report ?: m.takeIf { !it.fromUser }
    val brief = if (m.fromUser) m.text else null
    val status = when (report?.state) {
        null, ItemState.STREAMING, ItemState.SENDING, ItemState.QUEUED -> if (report?.waitingForApproval == true) "needs you" else "working"
        ItemState.FAILED -> "failed"
        ItemState.CANCELLED -> "stopped"
        else -> "done"
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("job-${m.key}")) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧 ${m.worker} · $status", style = MaterialTheme.typography.labelMedium,
                    color = if (status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text((brief ?: report?.text.orEmpty()).replace('\n', ' '), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(if (open) "▴" else "▾", style = MaterialTheme.typography.labelMedium)
            }
            val approval = report?.approval
            val turn = report?.turnId
            if (approval != null && turn != null) ApprovalCard(approval) { choice -> actions.approve(turn, choice) }
            if (open) {
                brief?.let {
                    Text("Asked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("job-brief"))
                }
                report?.let { r ->
                    r.tools.forEach { t ->
                        val mark = when (t.state) { "completed" -> "✓"; "failed" -> "✗"; else -> "…" }
                        Text("🔧 ${t.name} $mark", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.text.isNotEmpty()) {
                        Text("Report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.testTag("job-report")) { MarkdownText(r.text) }
                    }
                    r.error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: ApprovalItem, onAnswer: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("approval"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hermes asks to run", style = MaterialTheme.typography.titleSmall)
            a.command?.let {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("approval-command"))
            }
            a.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.choices.forEach { (choice, label) ->
                    val mod = Modifier.testTag("approve-$choice")
                    when (choice) {
                        "once" -> Button(onClick = { onAnswer(choice) }, modifier = mod) { Text(label) }
                        "deny" -> TextButton(onClick = { onAnswer(choice) }, modifier = mod) {
                            Text(label, color = MaterialTheme.colorScheme.error)
                        }
                        else -> OutlinedButton(onClick = { onAnswer(choice) }, modifier = mod) { Text(label) }
                    }
                }
            }
        }
    }
}

/**
 * In place of the composer while Talk is on (Talk 2): what it's doing and hearing. A tap anywhere on it sends what
 * was heard while listening, and interrupts Hermes (stopping its reply) while it thinks or speaks; End stops Talk.
 */
@Composable
private fun TalkBar(phase: TalkPhase, heard: String, level: Float, actions: TalariaActions) {
    Surface(
        color = if (phase == TalkPhase.LISTENING || phase == TalkPhase.APPROVING) Color(0xFFB4531A) else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = actions::talk).testTag("talk-bar"),
    ) {
        val on = if (phase == TalkPhase.LISTENING || phase == TalkPhase.APPROVING) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(phase.label, color = on, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("talk-phase"))
                Text(
                    when (phase) {
                        TalkPhase.LISTENING -> heard.ifEmpty { "Say something, or stay quiet to finish" }
                        TalkPhase.APPROVING -> heard.ifEmpty { "Yes allows it once, no stops it; or answer on screen" }
                        else -> heard.ifEmpty { "Tap to interrupt" }
                    },
                    color = on, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (phase == TalkPhase.LISTENING) {
                    // the microphone hears them: a bar that moves with their voice
                    val shown by animateFloatAsState(level.coerceIn(0f, 1f), label = "talk-level")
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(4.dp).background(on.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(shown).height(4.dp).background(on, RoundedCornerShape(2.dp)).testTag("talk-level"))
                    }
                }
            }
            TextButton(onClick = actions::endTalk, modifier = Modifier.testTag("talk-end")) {
                Text("End", color = on, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** In a bot's chat: what the bot is doing elsewhere right now (a group chat, a board task, a routine); tap to go there. */
@Composable
private fun BotAtWork(bot: BotItem, openId: String?, actions: TalariaActions) {
    val elsewhere = bot.busy.filter { it.conversationId == null || it.conversationId != openId }
    if (elsewhere.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 6.dp)
        .testTag("bot-at-work")) {
        elsewhere.forEach { w ->
            Row(Modifier.fillMaxWidth().clickable(enabled = w.roomId != null || w.board || w.schedule || w.conversationId != null) {
                when {
                    w.roomId != null -> actions.openRoom(w.roomId)
                    w.board -> { actions.selectTab(Tab.TODOS); actions.showBoard(true) }
                    w.schedule -> actions.selectTab(Tab.SCHEDULE)
                    w.conversationId != null -> actions.openConversation(w.conversationId)
                }
            }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Brand.Busy, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("${bot.name} is working: ${w.text}", style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

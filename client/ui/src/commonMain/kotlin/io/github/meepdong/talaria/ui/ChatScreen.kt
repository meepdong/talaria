package io.github.meepdong.talaria.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Two panes on a wide window (desktop), list then conversation on a phone (UI.md §3–4). */
@Composable
fun ChatHome(view: ChatView, actions: TalariaActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                ConversationList(view, actions, Modifier.width(320.dp).fillMaxHeight())
                VerticalDivider()
                Conversation(view, actions, showBack = false, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else if (view.conversationOpen) {
            Conversation(view, actions, showBack = true, modifier = Modifier.fillMaxSize())
        } else {
            ConversationList(view, actions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ConnectionDot(view: ChatView, actions: TalariaActions) {
    Row(
        Modifier.clickable(onClick = actions::showStatus).padding(8.dp).testTag("connection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(view.connection.color(), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(view.connectionSummary, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationList(view: ChatView, actions: TalariaActions, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            ConnectionDot(view, actions)
        }
        Button(onClick = actions::newConversation, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("new-chat")) {
            Text("✎ New chat")
        }
        view.listMessage?.let {
            Text(it, Modifier.padding(16.dp).testTag("list-message"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).testTag("conversations")) {
            items(view.conversations, key = { it.id }) { c ->
                ConversationRow(c, selected = c.id == view.openId, actions)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun ConversationRow(c: ConversationItem, selected: Boolean, actions: TalariaActions) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth().background(bg).clickable { actions.openConversation(c.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("conversation-${c.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(if (c.running) "⏳" else c.time, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c.preview.isNotEmpty()) {
            Text(c.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Conversation(view: ChatView, actions: TalariaActions, showBack: Boolean, modifier: Modifier) {
    var renaming by remember(view.openId) { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                TextButton(onClick = actions::closeConversation, modifier = Modifier.testTag("back")) { Text("←") }
            }
            Text(view.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("title"))
            if (showBack) ConnectionDot(view, actions)
            ConversationMenu(view.openId, view.voice, actions, onRename = { renaming = true })
        }
        HorizontalDivider()

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
    if (renaming && view.openId != null) {
        RenameDialog(view.title, onDone = { title ->
            renaming = false
            if (title != null) actions.renameConversation(view.openId, title)
        })
    }
}

@Composable
private fun ConversationMenu(id: String?, voice: VoiceView, actions: TalariaActions, onRename: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
            if (id == null) return@DropdownMenu
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
    // Follow the conversation as it grows, unless the user scrolled up to read.
    LaunchedEffect(view.openId, view.messages.size) {
        if (view.messages.isNotEmpty()) list.scrollToItem(view.messages.size)
    }
    LaunchedEffect(last?.text?.length, last?.tools?.size) {
        val info = list.layoutInfo
        val nearBottom = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        if (view.messages.isNotEmpty() && nearBottom) list.scrollToItem(view.messages.size)
    }
    Box(modifier.fillMaxWidth()) {
        if (view.messages.isEmpty() && !view.loading) {
            Text(
                if (view.openId == null) "Ask Hermes anything." else view.historyError ?: "No messages yet.",
                Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
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
                items(view.messages, key = { it.key }) { m -> MessageBubble(m, view.voice, actions) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageItem, voice: VoiceView, actions: TalariaActions) {
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
                ItemState.SENDING -> "Sending…"
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
        Text("Hermes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.tools.forEach { t ->
            val mark = when (t.state) { "completed" -> "✓"; "failed" -> "✗"; else -> "…" }
            Text("🔧 ${t.name} $mark", style = MaterialTheme.typography.bodySmall,
                color = if (t.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        m.commentary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.waitingForApproval) {
            Text("Waiting for approval in Hermes", style = MaterialTheme.typography.bodySmall,
                color = Brand.Brass, modifier = Modifier.testTag("approval"))
        }
        if (m.text.isNotEmpty()) MarkdownText(m.text)
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

/** A photo as a thumbnail when its bytes are here, otherwise a chip with its name. */
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
    var text by remember { mutableStateOf("") }
    val ready = view.canSend && (text.isNotBlank() || view.pending.isNotEmpty())
    fun send() {
        if (view.canSend && (text.isNotBlank() || view.pending.isNotEmpty())) {
            actions.sendMessage(text)
            text = ""
        }
    }
    val voice = view.voice
    // dictation lands in the composer, to edit before sending unless auto-send is on
    LaunchedEffect(voice.dictation?.id) {
        val d = voice.dictation ?: return@LaunchedEffect
        text = listOf(text.trimEnd(), d.text).filter { it.isNotEmpty() }.joinToString(" ").take(32000)
        actions.dictationTaken(d.id)
        if (d.send) send()
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        view.composerHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(4.dp).testTag("composer-hint"))
        }
        if (voice.speakingKey != null) {
            TextButton(onClick = actions::stopSpeaking, modifier = Modifier.testTag("stop-speaking")) { Text("🔊 Stop reading") }
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
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.canAttach) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = view.pending.size < 10,
                        modifier = Modifier.heightIn(min = 52.dp).testTag("attach")) { Text("📎") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, modifier = Modifier.testTag("attach-photo"),
                            onClick = { menu = false; actions.attachFiles(photos = true) })
                        DropdownMenuItem(text = { Text("File") }, modifier = Modifier.testTag("attach-file"),
                            onClick = { menu = false; actions.attachFiles(photos = false) })
                    }
                }
            }
            if (voice.canDictate) {
                TextButton(onClick = actions::toggleDictation, modifier = Modifier.heightIn(min = 52.dp).testTag("dictate")) {
                    Text(if (voice.listening) "■" else "🎤")
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(32000) },
                placeholder = { Text("Message…") },
                maxLines = 8,
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
            val running = view.runningTurnId
            if (running != null) {
                OutlinedButton(onClick = { actions.stopReply(running) }, modifier = Modifier.testTag("stop")) { Text("■ Stop") }
            } else {
                Button(onClick = ::send, enabled = ready, modifier = Modifier.testTag("send")) {
                    Text("➤")
                }
            }
        }
    }
}

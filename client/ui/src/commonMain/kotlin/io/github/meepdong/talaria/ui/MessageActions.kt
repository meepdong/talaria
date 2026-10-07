package io.github.meepdong.talaria.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A right click, for the same menu a long press opens (desktop). */
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) action()
        }
    }
}

private enum class MessageDialog { SELECT, MOVE, DELETE }

/** Long press (or right click) a message for its menu: copy, select, share, move, delete (UI.md §4). */
@Composable
fun WithMessageMenu(m: MessageItem, view: ChatView, actions: TalariaActions, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<MessageDialog?>(null) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val hasText = m.text.isNotBlank()
    val canRemove = view.openId != null && m.state !in setOf(ItemState.STREAMING, ItemState.SENDING, ItemState.QUEUED)
    // a gesture rather than combinedClickable, which would merge the message's buttons into one node
    Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
        .onSecondaryClick { menu = true }
        .semantics { onLongClick("Message options") { menu = true; true } }
        .testTag("hold-${m.key}")) {
        content()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable
            fun item(label: String, tag: String, onClick: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag(tag), onClick = { menu = false; onClick() })
            if (hasText) {
                item("Copy", "copy") { clipboard.setText(AnnotatedString(m.text)) }
                item("Select text", "select") { dialog = MessageDialog.SELECT }
                if (view.canShare) item("Share", "share") { actions.shareText(m.text) }
            }
            if (view.canRewind && m.key.startsWith("h:") && m.state == ItemState.DONE) {  // a bot's chat (§18.10)
                if (m.fromUser) item("Edit", "edit-message") { actions.editMessage(m.key, m.text) }
                else item("Regenerate", "regenerate") { actions.regenerate(m.key) }
            }
            if (canRemove) {
                if (hasText) item("Move to…", "move") { dialog = MessageDialog.MOVE }
                item("Delete", "delete-message") { dialog = MessageDialog.DELETE }
            }
        }
    }
    val close = { dialog = null }
    when (dialog) {
        MessageDialog.SELECT -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Select text") },
            text = {
                SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("select-text")) {
                    Text(m.text)
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Done") } },
        )
        MessageDialog.DELETE -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Delete this message?") },
            text = { Text("It goes from Talaria on all your devices. Hermes still remembers it in this chat.") },
            confirmButton = {
                TextButton(onClick = { close(); actions.deleteMessages(listOf(m.key)) }, modifier = Modifier.testTag("confirm-delete-message")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        MessageDialog.MOVE -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Move to…") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("It goes into the chat you pick as a quote, ready to send, and is deleted here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { close(); actions.moveMessages(listOf(m.key), null) },
                        modifier = Modifier.fillMaxWidth().testTag("move-new")) { Text("✎ New chat") }
                    view.conversations.filter { it.id != view.openId }.forEach { c ->
                        HorizontalDivider()
                        TextButton(onClick = { close(); actions.moveMessages(listOf(m.key), c.id) },
                            modifier = Modifier.fillMaxWidth().testTag("move-to-${c.id}")) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        null -> {}
    }
}

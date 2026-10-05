package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The keys under a terminal, as (label, tmux key or "=" literal text); the ones agent prompts need come first. */
private val KEY_BAR = listOf(
    "⏎" to "Enter", "1" to "=1", "2" to "=2", "3" to "=3", "y" to "=y", "n" to "=n", "Esc" to "Escape",
    "↑" to "Up", "↓" to "Down", "Ctrl-C" to "C-c", "Tab" to "Tab", "←" to "Left", "→" to "Right",
)

private val SCREEN_BG = Color(0xFF0E1216)
private val SCREEN_FG = Color(0xFFD7DAE0)

/** Terminals (spec §16.1): root's tmux sessions, watched and typed into from here. */
@Composable
fun TerminalScreen(view: TerminalView, actions: TalariaActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = if (view.open != null) actions::closeTerminal else actions::showChats,
                modifier = Modifier.testTag("terminal-back")) { Text("← Back") }
            Text(view.open ?: "Terminals", style = MaterialTheme.typography.titleLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (view.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (view.open == null) {
                TextButton(onClick = actions::refreshTerminals, modifier = Modifier.testTag("terminal-refresh")) { Text("Refresh") }
            }
        }
        view.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terminal-error")) }
        when {
            view.open == null -> SessionList(view, actions)
            view.approval != null && view.text == null -> ApprovalCard(view.approval, actions)
            else -> OpenTerminal(view, actions)
        }
    }
}

@Composable
private fun SessionList(view: TerminalView, actions: TalariaActions) {
    if (!view.available) {
        Text("This bridge offers no terminals. Install talaria-ops on the server (bridge/README.md).",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (view.sessions.isEmpty() && !view.loading) {
        Text("No tmux sessions are running as root on the server.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("terminal-none"))
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        view.sessions.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.openTerminal(s.name) }
                    .padding(vertical = 8.dp).testTag("terminal-row-${s.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(s.active, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: TerminalApproval, actions: TalariaActions) {
    Card(Modifier.fillMaxWidth().testTag("terminal-approval")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(approval.summary, style = MaterialTheme.typography.bodyLarge)
            if (approval.control) {
                Text("Typing here acts as root on the server.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.opsApprove(approval.requestId, "once") }, enabled = !approval.answering,
                    modifier = Modifier.testTag("terminal-allow")) { Text(if (approval.control) "Allow typing" else "Allow") }
                OutlinedButton(onClick = { actions.opsApprove(approval.requestId, "deny") }, enabled = !approval.answering,
                    modifier = Modifier.testTag("terminal-deny")) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun OpenTerminal(view: TerminalView, actions: TalariaActions) {
    var zoom by remember { mutableFloatStateOf(1f) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp)
                .background(SCREEN_BG, RoundedCornerShape(8.dp)).padding(6.dp).testTag("terminal-screen"),
        ) {
            // fit the session's width to the screen, then let zoom make it bigger (and scroll sideways)
            val fit = (maxWidth.value / (view.cols.coerceAtLeast(20) * 0.6f)).coerceIn(5f, 14f)
            val styled = remember(view.text, view.cursor) {
                ansiText(view.text.orEmpty(), SCREEN_FG, SCREEN_BG, view.cursor)
            }
            Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(styled, fontFamily = FontFamily.Monospace, fontSize = (fit * zoom).sp, lineHeight = (fit * zoom * 1.2f).sp,
                    softWrap = false, color = SCREEN_FG)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(view.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("terminal-status"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { zoom = (zoom / 1.2f).coerceAtLeast(0.6f) }, modifier = Modifier.testTag("terminal-zoom-out")) { Text("A−") }
            TextButton(onClick = { zoom = (zoom * 1.2f).coerceAtMost(3f) }, modifier = Modifier.testTag("terminal-zoom-in")) { Text("A+") }
        }
        view.closed?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terminal-closed")) }
        when {
            view.approval != null -> ApprovalCard(view.approval, actions)
            view.control -> KeyBar(actions)
            view.closed == null -> Button(onClick = actions::terminalTakeControl, modifier = Modifier.fillMaxWidth().testTag("terminal-take-control")) {
                Text("Take control")
            }
            else -> OutlinedButton(onClick = { actions.openTerminal(view.open!!) }, modifier = Modifier.fillMaxWidth().testTag("terminal-reopen")) {
                Text("Open again")
            }
        }
    }
}

@Composable
private fun KeyBar(actions: TalariaActions) {
    var draft by remember { mutableStateOf("") }
    val send = {
        if (draft.isNotEmpty()) actions.terminalText(draft, enter = true)
        draft = ""
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        KEY_BAR.forEach { (label, key) ->
            OutlinedButton(
                onClick = { if (key.startsWith("=")) actions.terminalText(key.drop(1), enter = false) else actions.terminalKey(key) },
                modifier = Modifier.testTag("terminal-key-$label"),
            ) { Text(label) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it.take(2000) }, singleLine = true,
            placeholder = { Text("Type, then send with Enter") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).testTag("terminal-input"),
        )
        Button(onClick = send, enabled = draft.isNotEmpty(), modifier = Modifier.testTag("terminal-send")) { Text("Send") }
    }
    Spacer(Modifier.size(4.dp))
}

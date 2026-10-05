package io.github.meepdong.talaria.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot

private val SCREEN_BG = Color(0xFF0E1216)
private val SCREEN_FG = Color(0xFFD7DAE0)
private val BAR_BG = Color(0xFF161C22)
private val BAR_FG = Color(0xFFB8C0CC)

/** Starting points for a new session; the fields take anything. */
private val FOLDER_PRESETS = listOf("/root", "/opt/talaria", "/home/hermes/projects", "/var/lib/talaria-wt")
private val COMMAND_PRESETS = listOf("Shell" to "", "Claude Code" to "claude", "opencode" to "opencode")
private val SESSION_NAME = Regex("[A-Za-z0-9_.@+-]{1,40}")

/** The extra keys above the keyboard: (label, tmux key). */
private val EXTRA_KEYS = listOf(
    "Esc" to "Escape", "Tab" to "Tab", "↑" to "Up", "↓" to "Down", "←" to "Left", "→" to "Right",
    "PgUp" to "PPage", "PgDn" to "NPage", "Home" to "Home", "End" to "End",
)
private val MORE_KEYS = (1..12).map { "F$it" to "F$it" } + listOf("|", "~", "/", "\\", "-", "_", "`", "^").map { it to "=$it" }

/** A hardware keyboard's letter keys, for Ctrl/Alt combinations. */
private val LETTER_KEYS = mapOf(
    Key.A to 'a', Key.B to 'b', Key.C to 'c', Key.D to 'd', Key.E to 'e', Key.F to 'f', Key.G to 'g', Key.H to 'h',
    Key.I to 'i', Key.J to 'j', Key.K to 'k', Key.L to 'l', Key.M to 'm', Key.N to 'n', Key.O to 'o', Key.P to 'p',
    Key.Q to 'q', Key.R to 'r', Key.S to 's', Key.T to 't', Key.U to 'u', Key.V to 'v', Key.W to 'w', Key.X to 'x',
    Key.Y to 'y', Key.Z to 'z',
)

/** What the hidden input keeps, so a backspace on an empty line still reaches us. */
private const val SENTINEL = "  "

/** Terminals (spec §16.1): the session list, or one session full screen. */
@Composable
fun TerminalScreen(view: TerminalView, actions: TalariaActions) {
    when {
        view.open == null -> SessionList(view, actions)
        view.approval != null && view.text == null -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = actions::closeTerminal, modifier = Modifier.testTag("terminal-back")) { Text("← Back") }
            Text(view.open, style = MaterialTheme.typography.titleLarge)
            ApprovalCard(view.approval, actions)
        }
        else -> FullTerminal(view, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(view: TerminalView, actions: TalariaActions) {
    var creating by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions::showChats, modifier = Modifier.testTag("terminal-back")) { Text("← Back") }
            Text("Terminals", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (view.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = actions::refreshTerminals, modifier = Modifier.testTag("terminal-refresh")) { Text("Refresh") }
        }
        view.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("terminal-error")) }
        if (!view.available) {
            Text("This bridge offers no terminals. Install talaria-ops on the server (bridge/README.md).",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (view.sessions.isEmpty() && !view.loading) {
                Text("No tmux sessions are running as root on the server.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp).testTag("terminal-none"))
            }
            view.sessions.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .combinedClickable(onLongClick = { ending = s.name }, onLongClickLabel = "End session") { actions.openTerminal(s.name) }
                        .padding(vertical = 8.dp).testTag("terminal-row-${s.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (s.name in view.ending) "Ending…" else s.active, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().testTag("terminal-new")) { Text("+ New session") }
        Text("Long-press a session to end it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (creating) {
        NewSession(taken = view.sessions.map { it.name }.toSet(), onDismiss = { creating = false }) { name, folder, command ->
            creating = false
            actions.newTerminal(name, folder, command)
        }
    }
    ending?.let { name ->
        AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("End $name?") },
            text = { Text("Everything running in it stops. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { ending = null; actions.endTerminal(name) }, modifier = Modifier.testTag("terminal-end-confirm")) {
                    Text("End session", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun NewSession(taken: Set<String>, onDismiss: () -> Unit, create: (String, String, String) -> Unit) {
    val suggested = remember(taken) { (1..99).map { "s-$it" }.first { it !in taken } }
    var name by remember { mutableStateOf(suggested) }
    var folder by remember { mutableStateOf("/root") }
    var command by remember { mutableStateOf("") }
    val ok = SESSION_NAME.matches(name) && name !in taken && folder.startsWith("/") && '\n' !in command
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
                    isError = name in taken, modifier = Modifier.fillMaxWidth().testTag("new-name"))
                OutlinedTextField(folder, { folder = it.take(1024) }, label = { Text("Start in") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new-folder"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FOLDER_PRESETS.forEach { f ->
                        FilterChip(folder == f, { folder = f }, label = { Text(f) }, modifier = Modifier.testTag("folder-preset-$f"))
                    }
                }
                OutlinedTextField(command, { command = it.replace("\n", " ").take(1000) }, label = { Text("Run (empty: a shell)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new-command"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COMMAND_PRESETS.forEach { (label, c) ->
                        FilterChip(command == c, { command = c }, label = { Text(label) }, modifier = Modifier.testTag("command-preset-$label"))
                    }
                }
                Text("It runs as root on the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { create(name, folder, command) }, enabled = ok, modifier = Modifier.testTag("new-create")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ApprovalCard(approval: TerminalApproval, actions: TalariaActions) {
    Card(Modifier.fillMaxWidth().testTag("terminal-approval")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(approval.summary, style = MaterialTheme.typography.bodyLarge)
            Text("Typing here acts as root on the server.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.opsApprove(approval.requestId, "once") }, enabled = !approval.answering,
                    modifier = Modifier.testTag("terminal-allow")) { Text("Allow") }
                OutlinedButton(onClick = { actions.opsApprove(approval.requestId, "deny") }, enabled = !approval.answering,
                    modifier = Modifier.testTag("terminal-deny")) { Text("Cancel") }
            }
        }
    }
}

/** Ctrl and Alt stay pressed for the next key. */
private class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** A key name with the pressed modifiers applied, and the modifiers released. */
    fun apply(key: String): String {
        val prefix = (if (ctrl) "C-" else "") + (if (alt) "M-" else "")
        ctrl = false
        alt = false
        return prefix + key
    }
}

/** Typed text as terminal keys: with Ctrl/Alt held, the first character becomes a key (C-c), Enter becomes Enter. */
internal fun typedKeys(typed: String, ctrl: Boolean, alt: Boolean): List<Pair<String, Boolean>> {
    // (value, isKey)
    val out = mutableListOf<Pair<String, Boolean>>()
    val text = StringBuilder()
    fun flush() {
        if (text.isNotEmpty()) out += text.toString() to false
        text.clear()
    }
    typed.forEachIndexed { i, ch ->
        when {
            ch == '\n' -> { flush(); out += "Enter" to true }
            i == 0 && (ctrl || alt) && (ch.lowercaseChar() in 'a'..'z' || ch in '0'..'9') -> {
                flush()
                out += ((if (ctrl) "C-" else "") + (if (alt) "M-" else "") + ch.lowercaseChar()) to true
            }
            else -> text.append(ch)
        }
    }
    flush()
    return out
}

@Composable
private fun FullTerminal(view: TerminalView, actions: TalariaActions) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val mods = remember { Modifiers() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    val scroll = rememberScrollState()
    val atBottom by remember { derivedStateOf { scroll.value >= scroll.maxValue - 4 } }
    var jumpToLive by remember { mutableStateOf(false) }

    fun send(keys: List<Pair<String, Boolean>>) = keys.forEach { (value, isKey) ->
        if (isKey) actions.terminalKey(value) else actions.terminalText(value, enter = false)
    }

    Box(Modifier.fillMaxSize().background(SCREEN_BG).imePadding().testTag("terminal-full")) {
        Column(Modifier.fillMaxSize()) {
            // a thin bar: not in the way, always there
            Row(Modifier.fillMaxWidth().background(BAR_BG).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::closeTerminal, modifier = Modifier.testTag("terminal-close")) { Text("✕", color = BAR_FG) }
                Text(view.status.ifEmpty { view.open.orEmpty() }, color = BAR_FG, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("terminal-status"))
                TextButton(onClick = { zoom = (zoom / 1.15f).coerceAtLeast(0.5f) }, modifier = Modifier.testTag("terminal-zoom-out")) { Text("A−", color = BAR_FG) }
                TextButton(onClick = { zoom = (zoom * 1.15f).coerceAtMost(4f) }, modifier = Modifier.testTag("terminal-zoom-in")) { Text("A+", color = BAR_FG) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("terminal-surface")) {
                val viewport = with(LocalDensity.current) { maxHeight.toPx() }
                val fit = (maxWidth.value / (view.cols.coerceAtLeast(20) * 0.6f)).coerceIn(4f, 16f)
                val size = fit * zoom
                val screen = remember(view.text, view.cursor) { ansiText(view.text.orEmpty(), SCREEN_FG, SCREEN_BG, view.cursor) }
                val history = remember(view.history) { view.history?.takeIf { it.isNotEmpty() }?.let { ansiText(it, SCREEN_FG, SCREEN_BG) } }
                // live: follow the bottom as the screen changes, unless the owner scrolled up
                LaunchedEffect(view.text) { if (atBottom) scroll.scrollTo(scroll.maxValue) }
                // scrollback arrived above: keep the screen where it was
                LaunchedEffect(view.history) { if (view.history != null) scroll.scrollTo((scroll.maxValue - viewport.toInt()).coerceAtLeast(0)) }
                // at the top of a shell's screen: fetch what scrolled off it
                LaunchedEffect(scroll.value == 0, view.history == null) {
                    if (scroll.value == 0 && scroll.maxValue > 0 && view.history == null && !view.alternate && !view.loadingHistory) actions.terminalHistory()
                }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            // pinch to zoom (two fingers); one finger is left to scrolling
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var last = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                        if (last > 0f) zoom = (zoom * d / last).coerceIn(0.5f, 4f)
                                        last = d
                                        pressed.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        .then(if (view.alternate) Modifier.pointerInput(Unit) {
                            // a full-screen program has no scrollback: a swipe pages it
                            var dragged = 0f
                            detectVerticalDragGestures(onDragEnd = { dragged = 0f }) { _, dy ->
                                dragged += dy
                                if (abs(dragged) > 80.dp.toPx()) {
                                    actions.terminalKey(if (dragged > 0) "PPage" else "NPage")
                                    dragged = 0f
                                }
                            }
                        } else Modifier)
                        .verticalScroll(scroll, enabled = !view.alternate)
                        .then(if (zoom > 1.01f) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .pointerInput(view.locked) {
                            // tap: the keyboard, to type straight into the terminal
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null && (up.position - down.position).getDistance() < 20f && view.control && !view.locked) {
                                    focus.requestFocus()
                                    keyboard?.show()
                                }
                            }
                        }
                        .padding(4.dp),
                ) {
                    Column {
                        if (view.loadingHistory) Text("Loading earlier lines…", color = BAR_FG, fontSize = (size * 0.9f).sp)
                        history?.let {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size * 1.2f).sp, softWrap = false,
                                color = SCREEN_FG, modifier = Modifier.testTag("terminal-history"))
                        }
                        Text(screen, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size * 1.2f).sp,
                            softWrap = false, color = SCREEN_FG, modifier = Modifier.testTag("terminal-screen"))
                    }
                }
                if (!atBottom && !view.alternate) {
                    Button(onClick = { jumpToLive = true }, modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(12.dp).testTag("terminal-live")) { Text("Back to live") }
                }
                LaunchedEffect(jumpToLive) { if (jumpToLive) { scroll.animateScrollTo(scroll.maxValue); jumpToLive = false } }
            }
            view.closed?.let {
                Row(Modifier.fillMaxWidth().background(BAR_BG).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).testTag("terminal-closed"))
                    TextButton(onClick = { actions.openTerminal(view.open!!) }, modifier = Modifier.testTag("terminal-reopen")) { Text("Open again") }
                }
            }
            view.approval?.let { Box(Modifier.padding(8.dp)) { ApprovalCard(it, actions) } }
            if (view.control && !view.locked && view.closed == null) ExtraKeys(mods, actions)
        }
        // the hidden input: the soft keyboard (and a hardware one) types through it
        BasicTextField(
            value = input,
            onValueChange = { new ->
                val text = new.text
                when {
                    text.length > SENTINEL.length && text.startsWith(SENTINEL) -> {
                        val ctrl = mods.ctrl
                        val alt = mods.alt
                        if (ctrl || alt) { mods.ctrl = false; mods.alt = false }
                        send(typedKeys(text.drop(SENTINEL.length), ctrl, alt))
                    }
                    text.length < SENTINEL.length -> repeat(SENTINEL.length - text.length) { actions.terminalKey("BSpace") }
                }
                input = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { actions.terminalKey("Enter") }, onDone = { actions.terminalKey("Enter") }),
            modifier = Modifier.size(1.dp).focusRequester(focus).testTag("terminal-input")
                .onPreviewKeyEvent { e ->
                    // a hardware keyboard: the keys the text field doesn't turn into text
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val named = when (e.key) {
                        Key.Enter, Key.NumPadEnter -> "Enter"
                        Key.Backspace -> "BSpace"
                        Key.Escape -> "Escape"
                        Key.Tab -> "Tab"
                        Key.DirectionUp -> "Up"
                        Key.DirectionDown -> "Down"
                        Key.DirectionLeft -> "Left"
                        Key.DirectionRight -> "Right"
                        Key.PageUp -> "PPage"
                        Key.PageDown -> "NPage"
                        Key.MoveHome -> "Home"
                        Key.MoveEnd -> "End"
                        Key.Delete -> "DC"
                        else -> null
                    }
                    val letter = LETTER_KEYS[e.key]
                    when {
                        named != null -> { actions.terminalKey((if (e.isCtrlPressed) "C-" else "") + (if (e.isAltPressed) "M-" else "") + named); true }
                        (e.isCtrlPressed || e.isAltPressed) && letter != null -> {
                            actions.terminalKey((if (e.isCtrlPressed) "C-" else "") + (if (e.isAltPressed) "M-" else "") + letter); true
                        }
                        else -> false
                    }
                },
        )
        if (view.locked) {
            Box(Modifier.fillMaxSize().background(SCREEN_BG.copy(alpha = 0.97f)).testTag("terminal-lock"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔒", fontSize = 40.sp)
                    Text("You were away for more than 5 minutes.", color = BAR_FG)
                    Button(onClick = actions::unlockTerminal, modifier = Modifier.testTag("terminal-unlock")) { Text("Unlock") }
                    TextButton(onClick = actions::closeTerminal) { Text("Close", color = BAR_FG) }
                }
            }
        }
    }
    LaunchedEffect(view.control, view.locked) {
        if (view.control && !view.locked) runCatching { focus.requestFocus() }
    }
}

@Composable
private fun ExtraKeys(mods: Modifiers, actions: TalariaActions) {
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(BAR_BG).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        KeyButton("Ctrl", mods.ctrl, "terminal-key-Ctrl") { mods.ctrl = !mods.ctrl }
        KeyButton("Alt", mods.alt, "terminal-key-Alt") { mods.alt = !mods.alt }
        EXTRA_KEYS.forEach { (label, key) -> KeyButton(label, false, "terminal-key-$label") { actions.terminalKey(mods.apply(key)) } }
        Box {
            KeyButton("⋯", more, "terminal-key-more") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                MORE_KEYS.forEach { (label, key) ->
                    DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("terminal-key-$label"), onClick = {
                        more = false
                        if (key.startsWith("=")) actions.terminalText(key.drop(1), enter = false) else actions.terminalKey(mods.apply(key))
                    })
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else BAR_FG,
        shape = RoundedCornerShape(6.dp),
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp).testTag(tag),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text(label) }
    }
}

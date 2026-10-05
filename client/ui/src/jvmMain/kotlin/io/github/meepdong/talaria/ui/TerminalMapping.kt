package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.ops.OpsState
import io.github.meepdong.talaria.terminal.TerminalState
import io.github.meepdong.talaria.terminal.TmuxSession

/** The Terminals page from the terminal state and the approvals waiting (spec §16.1). */
fun terminalView(t: TerminalState, ops: OpsState, nowMs: Long): TerminalView {
    val approval = t.requestId?.let { id ->
        ops.pending.firstOrNull { it.requestId == id }?.let { TerminalApproval(id, it.summary, t.control, id in ops.answering) }
    }
    val screen = t.screen?.takeIf { it.session == t.session }
    val control = screen?.control == true && t.grant != null
    return TerminalView(
        available = ops.available,
        sessions = t.sessions.map { terminalItem(it, nowMs) },
        loading = t.loading,
        open = t.session,
        approval = approval,
        text = screen?.text,
        cols = screen?.cols ?: 80,
        cursor = screen?.takeIf { control }?.let { it.cursorX to it.cursorY },
        control = control,
        status = when {
            screen == null && approval == null && t.session != null && t.closed == null -> "Opening…"
            screen == null -> ""
            else -> "${if (control) "Typing" else "Watching"} · ${screen.command.ifEmpty { screen.session }} · ${screen.cols}×${screen.rows}"
        },
        closed = t.closed,
        error = t.error,
    )
}

fun terminalItem(s: TmuxSession, nowMs: Long): TerminalItem {
    val what = when (s.command) {
        "claude" -> "Claude Code"
        "opencode" -> "opencode"
        "bash", "zsh", "sh", "fish" -> "Shell"
        else -> s.command
    }
    val attached = if (s.attached > 0) " · open on ${s.attached} screen${if (s.attached == 1) "" else "s"}" else ""
    val ago = ((nowMs / 1000 - s.activity) / 60).coerceAtLeast(0)
    val active = when {
        ago < 1 -> "active now"
        ago < 60 -> "$ago min ago"
        ago < 48 * 60 -> "${ago / 60} h ago"
        else -> "${ago / 1440} days ago"
    }
    return TerminalItem(s.name, "$what · ${s.path}$attached", active)
}

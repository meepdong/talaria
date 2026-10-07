package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.Bot
import io.github.meepdong.talaria.rooms.RoomMessageKind
import io.github.meepdong.talaria.rooms.RoomsState

/** rooms.create's name for the owner's own assistant (spec/README.md §18.2). */
const val ROOM_ASSISTANT = "assistant"

/** Group chats (§18.2) into the chat screens: the list, who can join a new one, and the open room. */
fun ChatView.withRooms(state: RoomsState?, openId: String?, bots: List<Bot>, nowMs: Long): ChatView {
    if (state == null || !state.available) return this
    val rooms = state.rooms.map { r ->
        RoomItem(
            id = r.id, name = r.name, members = r.members.joinToString(", ") { it.name },
            preview = r.previewText?.let { t -> (r.previewSpeaker?.let { "$it: " } ?: "") + t.replace('\n', ' ') }.orEmpty(),
            time = shortTime(r.updatedAt * 1000, nowMs).orEmpty(), working = r.working, needsYou = r.needsYou,
        )
    }
    val candidates = listOf(ROOM_ASSISTANT to "Your assistant") + bots.map { it.id to it.name }
    val room = openId?.let { id ->
        val summary = state.rooms.firstOrNull { it.id == id } ?: return@let null
        val thread = state.threads[id]
        RoomView(
            id = id, name = summary.name, members = summary.members.map { it.name to it.handle },
            messages = thread?.messages.orEmpty().map { m ->
                RoomMessageItem(
                    key = "r${m.seq}",
                    kind = when (m.kind) { RoomMessageKind.USER -> "user"; RoomMessageKind.MEMBER -> "member"; RoomMessageKind.NOTE -> "note" },
                    speaker = m.speaker, text = m.text, time = shortTime(m.atMs, nowMs), threadId = m.threadId,
                )
            },
            working = thread?.working ?: summary.working,
            approvals = thread?.approvals.orEmpty().map { RoomApprovalItem(it.id, it.member, it.command, it.description) },
            loading = thread?.loading == true, notice = state.notice, stuck = thread?.stuck ?: 0,
        )
    }
    return copy(rooms = rooms, roomCandidates = candidates, room = room)
}

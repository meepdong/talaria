package io.github.meepdong.talaria.ui

/** One row of the chat list (UI.md §3). */
data class ConversationItem(
    val id: String,
    val title: String,
    val preview: String,
    val time: String,
    val running: Boolean,
    /** Pinned to the top of the list. */
    val pinned: Boolean = false,
    /** A bot's chat (§18.1): shown with 🤖. */
    val bot: Boolean = false,
)

/** One of Hermes's bots, for the strip above the chat list and @mentions. [handle] is what @ matches first. */
data class BotItem(val id: String, val name: String, val handle: String, val description: String? = null,
                   val image: androidx.compose.ui.graphics.ImageBitmap? = null) {
    val initials: String get() = name.split(' ', '-', '_').filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

/**
 * "@scout find a cafe" → (the bot, "find a cafe"): a message for that bot's chat. Matches a bot's handle or its
 * name without spaces, ignoring case; null when the text doesn't start with a known bot.
 */
fun parseMention(text: String, bots: List<BotItem>): Pair<BotItem, String>? {
    val t = text.trimStart()
    if (!t.startsWith("@")) return null
    val word = t.drop(1).takeWhile { !it.isWhitespace() }.trimEnd(',', ':', '.').lowercase()
    if (word.isEmpty()) return null
    val bot = bots.firstOrNull { it.handle.lowercase() == word || it.name.lowercase().replace(" ", "") == word } ?: return null
    return bot to t.drop(1 + t.drop(1).takeWhile { !it.isWhitespace() }.length).trim()
}

/** The bots an "@…" being typed could mean, for the composer's suggestions; empty once a space follows. */
fun mentionSuggestions(text: String, bots: List<BotItem>): List<BotItem> {
    if (!text.startsWith("@") || text.any { it.isWhitespace() }) return emptyList()
    val typed = text.drop(1).lowercase()
    return bots.filter { it.handle.lowercase().startsWith(typed) || it.name.lowercase().replace(" ", "").startsWith(typed) }
}

enum class ItemState { SENDING, NOT_SENT, QUEUED, STREAMING, DONE, FAILED, CANCELLED }

/** A tool call in a reply: "🔧 web_search…", then ✓ or ✗. */
data class ToolChip(val name: String, val state: String, val preview: String? = null)

/** A photo or file on a message or waiting in the composer. [image] is set where the photo's bytes are known. */
data class AttachmentChip(
    val name: String,
    val isImage: Boolean,
    val detail: String?,
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** On the server (a file the agent sent): Open fetches it with files.read. */
    val root: String? = null,
    val path: String? = null,
    val mime: String? = null,
)

data class MessageItem(
    val key: String,
    val fromUser: Boolean,
    val text: String,
    val time: String?,
    val state: ItemState,
    val turnId: String? = null,
    val tools: List<ToolChip> = emptyList(),
    val commentary: String? = null,
    val waitingForApproval: Boolean = false,
    /** What the agent asks to run, with the answers it takes; null while it isn't asking. */
    val approval: ApprovalItem? = null,
    val error: String? = null,
    val attachments: List<AttachmentChip> = emptyList(),
    /** 0..1 while this message's files upload. */
    val progress: Float? = null,
    /** A job the Talk voice gave a worker (§9): the worker's name. Shown as one collapsed card, not a message. */
    val worker: String? = null,
    /** On a job's order: the worker's report, folded into the same card. */
    val report: MessageItem? = null,
)

/**
 * A job's order and the worker's report after it become one item (the order, with [MessageItem.report]), so a
 * Talk conversation shows the owner's and the voice's words as messages and each job as one card.
 */
fun foldJobs(items: List<MessageItem>): List<MessageItem> {
    val out = mutableListOf<MessageItem>()
    var i = 0
    while (i < items.size) {
        val m = items[i]
        val next = items.getOrNull(i + 1)
        if (m.worker != null && m.fromUser && next != null && !next.fromUser && next.worker == m.worker &&
            (m.turnId == null || next.turnId == null || m.turnId == next.turnId)) {
            out += m.copy(report = next)
            i += 2
        } else {
            out += m
            i += 1
        }
    }
    return out
}

/** An approval card: [choices] are (choice, button label), in Hermes's order. */
data class ApprovalItem(val command: String?, val description: String?, val choices: List<Pair<String, String>>)


data class ChatView(
    val conversations: List<ConversationItem>,
    /** Shown instead of the list when it's empty or failed. */
    val listMessage: String?,
    /** The open conversation, or null for a new one. */
    val openId: String?,
    /** On a phone: the conversation is showing rather than the list. */
    val conversationOpen: Boolean,
    val title: String,
    val messages: List<MessageItem>,
    val hasOlder: Boolean,
    val loading: Boolean,
    val historyError: String?,
    /** The reply that is still running, for the Stop button. */
    val runningTurnId: String?,
    val canSend: Boolean,
    /** Why sending is off, or a warning to show above the composer. */
    val composerHint: String?,
    val notice: String?,
    val connection: Health,
    val connectionSummary: String,
    /** Photos and files picked for the next message. */
    val pending: List<AttachmentChip> = emptyList(),
    /** The server file being fetched to open (a file the agent sent), and how far it got. */
    val openingFile: String? = null,
    val openingProgress: Float? = null,
    /** Whether this app can pick files (the 📎 button). */
    val canAttach: Boolean = false,
    /** False once the next message holds as many files as one can carry. */
    val canAttachMore: Boolean = true,
    val voice: VoiceView = VoiceView(),
    /** The model chip in the header: the conversation's, or the agent's default. Null hides it. */
    val model: String? = null,
    /** What the model chip offers, by provider. */
    val modelGroups: List<ModelGroup> = emptyList(),
    /** The model picker's search text while it's open (from the chip, or /model with several matches). Null: closed. */
    val modelPicker: String? = null,
    /** Side questions (/btw) on the open conversation. */
    val asides: List<AsideItem> = emptyList(),
    /** Hermes's own commands in the open chat (§18.3), offered after Talaria's in the / menu. */
    val hermesCommands: List<Command.Help> = emptyList(),
    /** Helper agents of the open bot chat's running reply (§18.7). */
    val helpers: List<HelperItem> = emptyList(),
    /** /status, while it's showing. */
    val status: ConversationStatusView? = null,
    /** Whether this app can hand text to other apps (Share in a message's menu). */
    val canShare: Boolean = false,
    /** Server operations waiting for approval (PROTOCOL §10.8). */
    val opsApprovals: List<OpsApprovalItem> = emptyList(),
    /** Approved server operations that finished, until dismissed. */
    val opsResults: List<OpsResultItem> = emptyList(),
    /** Chats swiped away, under Archived at the end of the list, newest first. */
    val archived: List<ConversationItem> = emptyList(),
    /** Hermes's bots (§18.1), above the chat list and for @mentions. */
    val bots: List<BotItem> = emptyList(),
    /** The open conversation is this bot's chat. */
    val openBot: BotItem? = null,
    /** Hermes's group chats (§18.2), below the chats; empty without the doorway. */
    val rooms: List<RoomItem> = emptyList(),
    /** Who can sit in a new group chat (id, name): the owner's assistant and the bots. Two or more: "New group". */
    val roomCandidates: List<Pair<String, String>> = emptyList(),
    /** The open group chat, instead of a conversation. */
    val room: RoomView? = null,
)

/** One group chat in the list. */
data class RoomItem(
    val id: String, val name: String, val members: String, val preview: String, val time: String,
    val working: Boolean, val needsYou: Boolean,
)

data class RoomMessageItem(
    val key: String, val kind: String, val speaker: String, val text: String, val time: String?, val threadId: String?,
)

data class RoomApprovalItem(val id: String, val member: String, val command: String?, val description: String?)

/** An open group chat: its members (name, handle for @), messages, and what's going on. */
data class RoomView(
    val id: String, val name: String, val members: List<Pair<String, String>>, val messages: List<RoomMessageItem>,
    val working: Boolean, val approvals: List<RoomApprovalItem>, val loading: Boolean, val notice: String? = null,
)

data class ModelGroup(val provider: String, val name: String, val models: List<ModelItem>)

data class ModelItem(val provider: String, val model: String, val label: String, val selected: Boolean)

/** A /btw side question, or (with [command]) one of Hermes's own commands and its output (§18.3). */
data class AsideItem(val id: String, val question: String, val answer: String?, val error: String?, val command: Boolean = false)

/** /status as label and value lines. */
data class ConversationStatusView(val title: String, val lines: List<Pair<String, String>>)

/** Text for the composer, heard by dictation or shared from another app. [id] tells one from the next. */
data class Dictation(val id: Long, val text: String, val send: Boolean)

/** The 🎤 and 🔊 controls. */
data class VoiceView(
    val canDictate: Boolean = false,
    val listening: Boolean = false,
    /** What dictation has heard so far, shown while listening. */
    val heard: String = "",
    /** Finished dictation waiting to go into the composer. */
    val dictation: Dictation? = null,
    val canSpeak: Boolean = false,
    /** The message being read aloud. */
    val speakingKey: String? = null,
    val readAloud: Boolean = false,
    val autoSend: Boolean = false,
    /** Talk is on, and what it's doing; null when it's off. */
    val talk: TalkPhase? = null,
    /** How loud the microphone hears them while Talk listens, 0 to 1. */
    val level: Float = 0f,
    /** How long Talk waits after they stop before it answers. */
    val talkWait: TalkWait = TalkWait.NORMAL,
    /** The talker's voices (id to label), once asked for, and the one it uses. */
    val talkVoices: List<Pair<String, String>> = emptyList(),
    val talkVoice: String? = null,
    /** The voice being heard as a sample. */
    val previewing: String? = null,
)

/** How long a pause ends the owner's turn in Talk. */
enum class TalkWait(val label: String, val quietMs: Int) {
    QUICK("Quick", 500), NORMAL("Normal", 800), PATIENT("Patient", 1500);

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Talk's state (Talk 2): it listens, waits for Hermes, speaks its reply, then listens again. */
enum class TalkPhase(val label: String) {
    LISTENING("Listening…"), THINKING("Hermes is thinking…"), SPEAKING("Hermes is speaking…"),
    /** Hermes asked for an approval: "yes" allows it once, "no" denies it. */
    APPROVING("Say yes or no…"),
}

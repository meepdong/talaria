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
)

enum class ItemState { SENDING, NOT_SENT, QUEUED, STREAMING, DONE, FAILED, CANCELLED }

/** A tool call in a reply: "🔧 web_search…", then ✓ or ✗. */
data class ToolChip(val name: String, val state: String, val preview: String? = null)

/** A photo or file on a message or waiting in the composer. [image] is set where the photo's bytes are known. */
data class AttachmentChip(
    val name: String,
    val isImage: Boolean,
    val detail: String?,
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,
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
)

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
    /** Whether this app can pick files (the 📎 button). */
    val canAttach: Boolean = false,
    val voice: VoiceView = VoiceView(),
    /** The model chip in the header: the conversation's, or the agent's default. Null hides it. */
    val model: String? = null,
    /** What the model chip offers, by provider. */
    val modelGroups: List<ModelGroup> = emptyList(),
    /** The model picker's search text while it's open (from the chip, or /model with several matches). Null: closed. */
    val modelPicker: String? = null,
    /** Side questions (/btw) on the open conversation. */
    val asides: List<AsideItem> = emptyList(),
    /** /status, while it's showing. */
    val status: ConversationStatusView? = null,
    /** Whether this app can hand text to other apps (Share in a message's menu). */
    val canShare: Boolean = false,
)

data class ModelGroup(val provider: String, val name: String, val models: List<ModelItem>)

data class ModelItem(val provider: String, val model: String, val label: String, val selected: Boolean)

data class AsideItem(val id: String, val question: String, val answer: String?, val error: String?)

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
)

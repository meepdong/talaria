package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.control.ControlState
import io.github.meepdong.talaria.rooms.RoomsState

/**
 * What each bot is doing right now, by bot id: a reply in its chat, a group chat it sits in that is busy, a task on
 * the bots' board it was given, a routine of its that runs. Bots with nothing going on aren't in the map.
 */
fun botWork(chat: ChatState?, rooms: RoomsState?, control: ControlState?): Map<String, List<BotWorkItem>> {
    val work = mutableMapOf<String, MutableList<BotWorkItem>>()
    fun add(botId: String?, item: BotWorkItem) {
        if (botId != null && botId.startsWith("bot:")) work.getOrPut(botId) { mutableListOf() } += item
    }
    chat?.conversations.orEmpty().filter { it.activeTurnId != null }.forEach { c ->
        add(c.agentId, BotWorkItem("Replying in its chat", conversationId = c.id))
    }
    rooms?.rooms.orEmpty().filter { it.working }.forEach { r ->
        r.members.forEach { m -> add(m.botId, BotWorkItem("In the group chat “${r.name}”", roomId = r.id)) }
    }
    control?.tasks.orEmpty().filter { it.status == "running" }.forEach { t ->
        add(t.assignee, BotWorkItem("On the board task “${t.title}”", board = true))
    }
    control?.routines.orEmpty().filter { it.state == "running" }.forEach { r ->
        add(r.botId, BotWorkItem("Running its routine “${r.name}”", schedule = true))
    }
    return work
}

/** The bots in the strip and the open bot chat with what they're doing; a bot's chat row shows it as running too. */
fun ChatView.withBotWork(work: Map<String, List<BotWorkItem>>, botIdOf: (String) -> String?): ChatView = copy(
    bots = bots.map { it.copy(busy = work[it.id].orEmpty()) },
    openBot = openBot?.let { it.copy(busy = work[it.id].orEmpty()) },
    conversations = conversations.map { c -> if (!c.running && botIdOf(c.id)?.let { it in work } == true) c.copy(running = true) else c },
)

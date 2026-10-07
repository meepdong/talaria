package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.Bot
import io.github.meepdong.talaria.control.BoardColumn
import io.github.meepdong.talaria.control.BoardComment
import io.github.meepdong.talaria.control.BoardTask
import io.github.meepdong.talaria.control.ControlState
import io.github.meepdong.talaria.control.Helper
import io.github.meepdong.talaria.control.Routine
import io.github.meepdong.talaria.control.Usage
import io.github.meepdong.talaria.control.UsageReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoardScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun showBoard(show: Boolean) { calls += "show $show" }
        override fun boardAdd(title: String, assignee: String?) { calls += "add $title $assignee" }
        override fun boardOpen(taskId: String?) { calls += "open $taskId" }
        override fun boardMove(taskId: String, status: String) { calls += "move $taskId $status" }
        override fun boardGive(taskId: String, assignee: String) { calls += "give $taskId $assignee" }
        override fun boardComment(taskId: String, text: String) { calls += "comment $taskId $text" }
        override fun loadUsage(days: Int) { calls += "usage $days" }
        override fun routineAdd(botId: String, name: String, schedule: String, task: String) { calls += "routine $botId $name|$schedule|$task" }
        override fun routineSet(botId: String, routineId: String, action: String) { calls += "set $botId $routineId $action" }
        override fun helperSteer(helperId: String, text: String) { calls += "steer $helperId $text" }
        override fun helperStop(helperId: String) { calls += "stop $helperId" }
        override fun saveBot(draft: BotDraft, confirm: Boolean) { calls += "save $draft $confirm" }
        override fun deleteBot() { calls += "delete" }
        override fun closeBotEditor() { calls += "close" }
        override fun pickBotPicture() { calls += "pick" }
    }

    private val bots = listOf(Bot("bot:meetingminder", "Meeting Minder", "meetingminder"), Bot("bot:research", "Research", "research"))
    private val now = 1_700_010_000_000L
    private val state = ControlState(
        boardAvailable = true, boardLoaded = true,
        columns = listOf(
            BoardColumn("todo", listOf(BoardTask("t_1", "Book the hall", "todo", createdAt = 1_700_000_000))),
            BoardColumn("ready", emptyList()),
            BoardColumn("running", listOf(BoardTask("t_2", "Summarise Monday's call", "running", createdAt = 1_700_000_000,
                startedAt = 1_700_009_000, assignee = "bot:meetingminder", summary = "Reading the notes", comments = 1))),
        ),
        openTask = "t_2",
        comments = mapOf("t_2" to listOf(BoardComment("owner", "Keep it short", 1_700_009_500))),
    )

    @Test
    fun mapping() {
        val v = boardView(state, showing = true, bots = bots, nowMs = now)
        assertEquals(listOf("To do", "Working on it"), v.columns.map { it.label }, "empty columns are left out")
        assertEquals("Meeting Minder", v.columns[1].tasks.single().who)
        assertEquals("16 min ago", v.columns[1].tasks.single().age)
        assertEquals(2, v.taskCount)
        val d = v.detail!!
        assertEquals(listOf(Triple("You", "Keep it short", "8 min ago")), d.comments)
        assertTrue("running" !in d.moves.map { it.first } && "archived" in d.moves.map { it.first })
        assertEquals(listOf("assistant", "bot:meetingminder", "bot:research"), v.people.map { it.first })
        assertEquals(BoardView(), boardView(ControlState(), true, bots, now), "no board on this bridge")
    }

    @Test
    fun usageMapping() {
        val u = usageView(UsageReport(7, Usage(6.653, true, 4_597_220, 351_425, 77, 874),
            listOf("2026-10-05" to Usage(2.0, true, 1, 1, 1, 1), "2026-10-06" to Usage(1.0, false, 1, 1, 1, 1)),
            listOf("qwen/qwen3.8-flash" to Usage(2.23, true, 1, 1, 47, 508))))
        assertEquals("$6.65 (estimate)", u.total)
        assertEquals("874 calls · 77 chats · 4.6M tokens in, 351k out", u.detail)
        assertEquals(listOf("Mon 5" to 1f, "Tue 6" to 0.5f), u.byDay.map { it.label to it.fraction })
        assertEquals("qwen/qwen3.8-flash" to "$2.23 · 508 calls", u.byModel.single())
    }

    @Test
    fun theBoardOnAPhone() = runComposeUiTest {
        val actions = Recorder()
        val board = boardView(state, showing = true, bots = bots, nowMs = now)
        setContent { Box(Modifier.size(380.dp, 1400.dp)) { TodosTab(TodosView(), board, actions) } }
        onNodeWithTag("show-board").assertTextContains("Bots' board · 2")
        onNodeWithText("Reading the notes").assertExists()
        onNodeWithText("Keep it short").assertExists()
        onNodeWithTag("task-move").performClick()
        onNodeWithTag("move-done").performClick()
        onNodeWithTag("task-give").performClick()
        onNodeWithTag("give-bot:research").performClick()
        onNodeWithTag("task-comment").performTextInput("Thanks")
        onNodeWithTag("task-comment-send").performClick()
        onNodeWithTag("task-title").performTextInput("Draft the vendor email")
        onNodeWithTag("task-for").performClick()
        onNodeWithTag("for-bot:research").performClick()
        onNodeWithTag("task-add").assertTextContains("Add and start").performClick()
        onNodeWithTag("task-t_1").performClick()
        onNodeWithTag("show-list").performClick()
        assertEquals(listOf("move t_2 done", "give t_2 bot:research", "comment t_2 Thanks",
            "add Draft the vendor email bot:research", "open t_1", "show false"), actions.calls)
    }

    @Test
    fun noBoardMeansJustTheList() = runComposeUiTest {
        setContent { Box(Modifier.size(380.dp, 800.dp)) { TodosTab(TodosView(), BoardView(), Recorder()) } }
        onNodeWithTag("show-board").assertDoesNotExist()
        onNodeWithTag("todos-page").assertExists()
    }

    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private val routines = ControlState(routines = listOf(
        Routine("r1", "bot:research", "Weekly digest", "every monday 8am", "Digest the week's news", true, "scheduled",
            nextRunAt = 1_700_012_000, lastRunAt = 1_700_005_000, lastStatus = "error", lastError = "sandbox down", toChat = true),
        Routine("r2", "assistant", "Flight watch", "0 */6 * * *", "Check prices", false, "paused")),
        helpers = mapOf("c-1" to listOf(Helper("sa-1", "Find three cafes", "running", 2, "web_search", canSteer = true))))

    @Test
    fun routinesMapping() {
        val v = routinesView(routines, bots, now, zone)!!
        val r = v.items[0]
        assertEquals("Research" to "in 33 min", r.who to r.next)
        assertEquals("Failed 05:06: sandbox down", r.last)
        assertEquals(true, r.failed)
        assertEquals("Hermes" to false, v.items[1].who to v.items[1].on)
        assertEquals(null, routinesView(ControlState(), bots, now, zone), "no routines on this bridge")
        assertEquals(listOf(HelperItem("sa-1", "Find three cafes", "running · 2 tools · web_search", true)), helperItems(routines, "c-1"))
    }

    @Test
    fun routinesAndHelpersOnAPhone() = runComposeUiTest {
        val actions = Recorder()
        val v = routinesView(routines, bots, now, zone)!!
        val helpers = helperItems(routines, "c-1")
        setContent {
            Box(Modifier.size(380.dp, 1600.dp)) {
                androidx.compose.foundation.layout.Column {
                    RoutinesCard(v, actions)
                    helpers.forEach { HelperRow(it, actions) }
                }
            }
        }
        onNodeWithTag("routine-on-r1").performClick()
        onNodeWithTag("routine-run-r2").performClick()
        onNodeWithTag("routine-delete-r1").performClick()
        onNodeWithTag("routine-delete-sure-r1").performClick()
        onNodeWithTag("routine-new").performClick()
        onNodeWithTag("routine-bot").performClick()
        onNodeWithTag("routine-bot-bot:meetingminder").performClick()
        onNodeWithTag("routine-name").performTextInput("Monday recap")
        onNodeWithTag("routine-when").performTextInput("every monday 9am")
        onNodeWithTag("routine-task").performTextInput("Recap last week's meetings")
        onNodeWithTag("routine-add").performClick()
        onNodeWithTag("helper-note-sa-1").performClick()
        onNodeWithTag("helper-text-sa-1").performTextInput("Only vegan")
        onNodeWithTag("helper-send-sa-1").performClick()
        onNodeWithTag("helper-stop-sa-1").performClick()
        assertEquals(listOf("set bot:research r1 pause", "set assistant r2 run", "set bot:research r1 remove",
            "routine bot:meetingminder Monday recap|every monday 9am|Recap last week's meetings", "steer sa-1 Only vegan", "stop sa-1"),
            actions.calls)
    }

    @Test
    fun theBotEditor() = runComposeUiTest {
        val actions = Recorder()
        val v = BotEditorView(botId = "bot:scout", name = "Scout", about = "Finds things", personality = "Be kind.",
            model = "qwen/qwen3.8-flash", models = listOf("google/gemini-3-flash", "qwen/qwen3.8-flash"),
            skills = listOf(SwitchItem("research", "research", "", true), SwitchItem("spotify", "spotify", "", false)),
            toolsets = listOf(SwitchItem("web", "Web", "Search the web", true)), version = 1, canPickPicture = true)
        setContent { Box(Modifier.size(380.dp, 1800.dp)) { BotEditorScreen(v, actions) } }
        onNodeWithTag("bot-save").assertIsNotEnabled()  // nothing changed yet
        onNodeWithTag("bot-about").performTextReplacement("Finds places to eat")
        onNodeWithTag("bot-Skills").performClick()
        onNodeWithTag("switch-spotify").performClick()
        onNodeWithTag("bot-model").performClick()
        onNodeWithTag("bot-model-google/gemini-3-flash").performClick()
        onNodeWithTag("bot-picture").performClick()
        onNodeWithTag("bot-save").performClick()
        onNodeWithTag("bot-delete").performClick()
        onNodeWithTag("bot-delete-sure").performClick()
        onNodeWithTag("bot-editor-back").performClick()
        assertEquals(listOf("pick",
            "save ${BotDraft("Scout", "Finds places to eat", "Be kind.", "google/gemini-3-flash", setOf("research", "spotify"), setOf("web"), emptySet())} false",
            "delete", "close"), actions.calls)
    }

    @Test
    fun aNewBotAndAnExpensiveModel() = runComposeUiTest {
        val actions = Recorder()
        setContent { Box(Modifier.size(380.dp, 1200.dp)) { BotEditorScreen(BotEditorView(confirm = "Expensive!"), actions) } }
        onNodeWithTag("bot-save").assertTextContains("Make bot").assertIsNotEnabled()
        onNodeWithTag("bot-name").performTextInput("Trip Planner")
        onNodeWithTag("bot-delete").assertDoesNotExist()
        onNodeWithTag("bot-picture").assertDoesNotExist()
        onNodeWithTag("bot-confirm-yes").performClick()
        onNodeWithTag("bot-save").performClick()
        assertEquals(listOf("save ${BotDraft("Trip Planner", "", "", null, emptySet(), emptySet(), emptySet())} true",
            "save ${BotDraft("Trip Planner", "", "", null, emptySet(), emptySet(), emptySet())} false"), actions.calls)
    }

    @Test
    fun theMenuShowsGroupChatsAndBoardWorkAsRunning() {
        val status = StatusView(rows = emptyList(), lastConnected = "just now", deviceName = "Phone", server = "wss://vps",
            keyProtection = "Keystore", overall = Health.GOOD, summary = "Connected")
        val chat = chatView(io.github.meepdong.talaria.chat.ChatState(listLoaded = true), false, true, status, now).copy(rooms = listOf(
            RoomItem("g1", "Trip crew", "Scout, Hermes", "", "", working = true, needsYou = false),
            RoomItem("g2", "Budget", "Freaksheet", "", "", working = false, needsYou = true),
            RoomItem("g3", "Quiet", "Research", "", "", working = false, needsYou = false)))
        val menu = menuView(chat, status, board = boardView(state, false, bots, now))
        assertEquals(listOf(
            RunningItem("Trip crew", "Group chat: members are talking", roomId = "g1"),
            RunningItem("Budget", "Group chat: waiting for you", roomId = "g2"),
            RunningItem("Summarise Monday's call", "Board: Meeting Minder is working on it", board = true)), menu.running)
    }

    @Test
    fun whatEachBotIsDoing() {
        val chat = io.github.meepdong.talaria.chat.ChatState(conversations = listOf(
            io.github.meepdong.talaria.chat.ConversationSummary("c-r", "bot:research", "Research", 1, 1, activeTurnId = "t-1"),
            io.github.meepdong.talaria.chat.ConversationSummary("c-m", "bot:meetingminder", "Meeting Minder", 1, 1)))
        val rooms = io.github.meepdong.talaria.rooms.RoomsState(rooms = listOf(io.github.meepdong.talaria.rooms.RoomSummary("g1", "Trip crew",
            listOf(io.github.meepdong.talaria.rooms.RoomMember("m1", "Research", "research", "bot:research"),
                io.github.meepdong.talaria.rooms.RoomMember("m2", "Hermes", "hermes")), 1, working = true)))
        val control = state.copy(routines = listOf(Routine("r1", "bot:research", "Digest", "daily", "x", true, "running")))
        val work = botWork(chat, rooms, control)
        assertEquals(listOf(BotWorkItem("Replying in its chat", conversationId = "c-r"), BotWorkItem("In the group chat “Trip crew”", roomId = "g1"),
            BotWorkItem("Running its routine “Digest”", schedule = true)), work["bot:research"])
        assertEquals(listOf(BotWorkItem("On the board task “Summarise Monday's call”", board = true)), work["bot:meetingminder"])
        assertEquals(setOf("bot:research", "bot:meetingminder"), work.keys)
    }

    @Test
    fun aBusyBotShowsInItsChatAndTheStrip() = runComposeUiTest {
        val actions = Recorder()
        val status = StatusView(rows = emptyList(), lastConnected = "just now", deviceName = "Phone", server = "wss://vps",
            keyProtection = "Keystore", overall = Health.GOOD, summary = "Connected")
        val chat = io.github.meepdong.talaria.chat.ChatState(listLoaded = true, openId = "c-m", bots = bots,
            conversations = listOf(io.github.meepdong.talaria.chat.ConversationSummary("c-m", "bot:meetingminder", "Meeting Minder", 1, 1)),
            threads = mapOf("c-m" to io.github.meepdong.talaria.chat.ConversationThread(loaded = true)))
        val work = botWork(chat, null, state)
        val v = chatView(chat, true, true, status, now).withBotWork(work) { "bot:meetingminder" }
        assertEquals(true, v.conversations.single().running, "its chat row shows it at work")
        setContent { Box(Modifier.size(380.dp, 800.dp)) { ChatHome(v, actions) } }
        onNodeWithTag("bot-at-work").assertExists()
        onNodeWithText("Meeting Minder is working: On the board task “Summarise Monday's call”").performClick()
        assertEquals(listOf("show true"), actions.calls)
    }
}

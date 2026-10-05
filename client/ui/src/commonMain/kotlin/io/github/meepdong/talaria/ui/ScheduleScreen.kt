package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Schedule: today's calendar next to the agent's automations, and a way to add one. */
@Composable
fun ScheduleScreen(schedule: ScheduleView, actions: TalariaActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("schedule"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Schedule", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (!schedule.available) {
                Text("This bridge doesn't run automations yet. Update it to the latest version.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule-unavailable"))
                return@Column
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 1120.dp)) {
                    AgendaCard(schedule, Modifier.weight(1f))
                    Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AutomationsCard(schedule, actions, Modifier.fillMaxWidth())
                        NewAutomationCard(schedule, actions, Modifier.fillMaxWidth())
                    }
                }
            } else {
                AgendaCard(schedule, Modifier.fillMaxWidth())
                AutomationsCard(schedule, actions, Modifier.fillMaxWidth())
                NewAutomationCard(schedule, actions, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AgendaCard(schedule: ScheduleView, modifier: Modifier) {
    SectionCard("Today · ${schedule.date}", modifier = modifier.testTag("agenda")) {
        schedule.calendarNote?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        schedule.agenda.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp),
                    color = if (e.now) Brand.Busy else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (e.now) FontWeight.SemiBold else null,
                        color = if (e.past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (e.now) Text("Now", style = MaterialTheme.typography.labelMedium, color = Brand.Busy)
            }
        }
    }
}

@Composable
private fun AutomationsCard(schedule: ScheduleView, actions: TalariaActions, modifier: Modifier) {
    SectionCard("Automations", modifier = modifier.testTag("automations")) {
        schedule.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        if (schedule.automations.isEmpty()) {
            Text(if (schedule.loaded) "None yet. Add one below, or ask Hermes in any chat." else "Loading…",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        schedule.automations.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AutomationRow(a, actions)
        }
    }
}

@Composable
private fun AutomationRow(a: AutomationItem, actions: TalariaActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("automation-${a.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (a.paused) MaterialTheme.colorScheme.outlineVariant else if (a.failed)
                MaterialTheme.colorScheme.error else Brand.Blue, CircleShape))
            Text(a.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f))
            Switch(checked = !a.paused, onCheckedChange = { actions.setAutomationPaused(a.id, !it) },
                modifier = Modifier.testTag("automation-on-${a.id}"))
        }
        Text(a.schedule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val facts = listOfNotNull(a.next?.let { "Next: $it" }, a.last, if (a.byAgent) "Made by Hermes" else null)
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = if (a.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        // where its results go; Home also notifies every device (§14), for jobs made anywhere, Hermes's own included
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Results:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RESULT_CHOICES.forEach { (key, label) ->
                FilterChip(selected = a.resultKey == key, onClick = { if (a.resultKey != key) actions.setAutomationResultTo(a.id, key) },
                    label = { Text(label) }, modifier = Modifier.testTag("automation-result-$key-${a.id}"))
            }
        }
        Row {
            TextButton(onClick = { actions.runAutomation(a.id) }, modifier = Modifier.testTag("automation-run-${a.id}")) { Text("Run now") }
            if (a.blocked) {
                TextButton(onClick = { actions.runAutomationInChat(a.id) }, modifier = Modifier.testTag("automation-chat-${a.id}")) {
                    Text("Run in chat")
                }
            }
            TextButton(onClick = { actions.deleteAutomation(a.id) }, modifier = Modifier.testTag("automation-delete-${a.id}")) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NewAutomationCard(schedule: ScheduleView, actions: TalariaActions, modifier: Modifier) {
    var words by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(AutomationDraft()) }
    SectionCard("New automation", modifier = modifier.testTag("new-automation")) {
        Text("Describe it", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = words, onValueChange = { words = it.take(4000) },
            placeholder = { Text("Every weekday at 8, summarise my unread email") },
            minLines = 2, modifier = Modifier.fillMaxWidth().testTag("describe-input"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { actions.describeAutomation(words); words = "" }, enabled = words.isNotBlank() && !schedule.describing,
                modifier = Modifier.testTag("describe")) { Text("Ask Hermes to set it up") }
            if (schedule.describing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        schedule.describeReply?.let { reply ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
                .padding(12.dp), verticalAlignment = Alignment.Top) {
                Text(reply, modifier = Modifier.weight(1f).testTag("describe-reply"))
                TextButton(onClick = actions::clearDescribeReply) { Text("OK") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Or set it up yourself", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { draft = AutomationDraft.MORNING_SUMMARY }, modifier = Modifier.testTag("template-morning")) {
                Text("Morning catch-up summary")
            }
        }
        DraftForm(draft) { draft = it }
        Button(
            onClick = { actions.addAutomation(draft); draft = AutomationDraft() },
            enabled = draft.ready,
            modifier = Modifier.padding(top = 12.dp).testTag("add-automation"),
        ) { Text("Add automation") }
    }
}

@Composable
private fun ColumnScope.DraftForm(draft: AutomationDraft, change: (AutomationDraft) -> Unit) {
    Field("Name", draft.name, "draft-name") { change(draft.copy(name = it.take(200))) }
    Text("When", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WhenKind.entries.forEach { k ->
            FilterChip(selected = draft.kind == k, onClick = { change(draft.copy(kind = k)) }, label = { Text(k.label) },
                modifier = Modifier.testTag("kind-${k.name.lowercase()}"))
        }
    }
    when (draft.kind) {
        WhenKind.TIME -> Field("Schedule, such as \"weekdays at 9am\", \"every 2h\" or \"0 9 * * 1-5\"", draft.schedule,
            "draft-schedule") { change(draft.copy(schedule = it.take(100))) }
        WhenKind.ARRIVES -> {
            Field("What to watch for", draft.watch, "draft-watch") { change(draft.copy(watch = it.take(500))) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("From (HH:MM)", draft.from, "draft-from") { change(draft.copy(from = it.take(5))) } }
                Box(Modifier.weight(1f)) { Field("Until (HH:MM)", draft.until, "draft-until") { change(draft.copy(until = it.take(5))) } }
            }
            Days(draft, change)
            Field("If nothing arrives by then (optional)", draft.fallback, "draft-fallback") { change(draft.copy(fallback = it.take(2000))) }
        }
        WhenKind.AFTER_EVENT -> {
            Field("Event title contains", draft.event, "draft-event") { change(draft.copy(event = it.take(200))) }
            Field("Minutes after it ends", draft.delayMinutes.toString(), "draft-delay") {
                change(draft.copy(delayMinutes = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0))
            }
            Days(draft, change)
        }
    }
    Field("What Hermes should do", draft.task, "draft-task", minLines = 3) { change(draft.copy(task = it.take(4000))) }
    Text("Send the result to", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RESULT_CHOICES.forEach { (key, label) ->
            FilterChip(selected = draft.resultTo == key, onClick = { change(draft.copy(resultTo = key)) }, label = { Text(label) },
                modifier = Modifier.testTag("result-$key"))
        }
    }
}

@Composable
private fun Days(draft: AutomationDraft, change: (AutomationDraft) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AutomationDraft.DAYS.forEach { d ->
            FilterChip(
                selected = d in draft.days,
                onClick = { change(draft.copy(days = if (d in draft.days) draft.days - d else draft.days + d)) },
                label = { Text(d.replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.testTag("day-$d"),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, minLines = minLines, singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag),
    )
}

/**
 * Home's cards for the day: Needs you (only when something waits for the owner), Your day, Next up and
 * Automations on. Needs you holds server operations waiting for approval and runs Hermes couldn't finish
 * without one, so Your day keeps only what was done.
 */
@Composable
fun DayCards(home: HomeView, actions: TalariaActions, wide: Boolean) {
    NeedsYouCard(home, actions)
    if (!home.automationsAvailable) return
    if (wide) {
        YourDayCard(home, actions, Modifier.fillMaxWidth().widthIn(max = 1120.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 1120.dp)) {
            NextUpCard(home, Modifier.weight(1f))
            AutomationsOnCard(home, actions, Modifier.weight(1f))
        }
    } else {
        YourDayCard(home, actions, Modifier.fillMaxWidth())
        NextUpCard(home, Modifier.fillMaxWidth())
        AutomationsOnCard(home, actions, Modifier.fillMaxWidth())
    }
}

/** Blocked runs and server approvals, while any wait; Home keeps it on top. */
@Composable
fun NeedsYouCard(home: HomeView, actions: TalariaActions) {
    val blocked = home.day.filter { it.blocked != null }
    if (blocked.isNotEmpty() || home.approvals.isNotEmpty()) {
        SectionCard("Needs you", modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("needs-you")) {
            home.approvals.forEach { a -> OpsApprovalCard(a, actions) }
            blocked.forEachIndexed { i, d ->
                if (i > 0 || home.approvals.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(d.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Hermes needed your approval for ${d.blocked}, and nobody was there to give it.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp).testTag("blocked-${d.id}"))
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.runAutomationInChat(d.id) }, modifier = Modifier.testTag("run-in-chat-${d.id}")) {
                        Text("Run in chat")
                    }
                    OutlinedButton(onClick = { actions.dismissHomeItem(d.id, d.at) }, modifier = Modifier.testTag("dismiss-${d.id}")) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

/** Today's finished results. [tile] adds Home's rearranging. */
@Composable
fun YourDayCard(home: HomeView, actions: TalariaActions, m: Modifier, tile: TileChrome = TileChrome()) {
    val done = home.day.filter { it.blocked == null }
    SectionCard("Your day", modifier = m.testTag("your-day"), trailing = tile.trailing ?: {}, onTitleLongClick = tile.onTitleLongClick) {
        if (done.isEmpty()) {
            Text("Nothing yet today. Results from automations that report to Home show up here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        done.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(d.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.text.isNotBlank()) {
                if (d.failed) {
                    Text(d.text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                } else {
                    ResultText(d.text, Modifier.padding(top = 4.dp).testTag("result-${d.id}"))
                }
            }
            d.conversationId?.let { c -> TextButton(onClick = { actions.openConversation(c) }) { Text("Open chat") } }
        }
    }
}

@Composable
fun NextUpCard(home: HomeView, m: Modifier, tile: TileChrome = TileChrome()) {
    SectionCard("Next up", modifier = m.testTag("next-up"), trailing = tile.trailing ?: {}, onTitleLongClick = tile.onTitleLongClick) {
        if (home.nextUp.isEmpty()) Text("Nothing coming up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        home.nextUp.forEach { n ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (n.automation) "⚙ " else "") + n.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(n.until, style = MaterialTheme.typography.labelMedium, color = Brand.Blue)
            }
        }
    }
}

@Composable
fun AutomationsOnCard(home: HomeView, actions: TalariaActions, m: Modifier, tile: TileChrome = TileChrome()) {
    SectionCard("Automations on", modifier = m.testTag("automations-on"), onTitleLongClick = tile.onTitleLongClick,
        trailing = tile.trailing ?: { OutlinedButton(onClick = { actions.selectTab(Tab.SCHEDULE) }) { Text("Schedule") } }) {
        if (home.automationsOn.isEmpty()) Text("None on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        home.automationsOn.forEach { a ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.schedule, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                a.next?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.Blue) }
            }
        }
    }
}

/** An automation's result as the agent wrote it (Markdown), the first [RESULT_LINES] lines until Show more. */
@Composable
fun ResultText(text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    val lines = text.trim().lines()
    val long = lines.size > RESULT_LINES
    Column(modifier) {
        MarkdownText(if (long && !expanded) lines.take(RESULT_LINES).joinToString("\n") else text.trim())
        if (long) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("result-more")) {
                Text(if (expanded) "Show less" else "Show more")
            }
        }
    }
}

private const val RESULT_LINES = 8

/** Where an automation's results go (§14). */
private val RESULT_CHOICES = listOf("home" to "Home", "chat" to "A new chat", "log" to "Log only")

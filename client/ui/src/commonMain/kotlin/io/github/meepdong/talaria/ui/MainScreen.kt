package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The paired app: a menu bar (down the left on a wide window, along the bottom on a
 * phone), the page for the chosen tab, and the ☰ panel over it.
 */
@Composable
fun MainScreen(screen: Screen.Chat, actions: TalariaActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Box(Modifier.fillMaxSize()) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SideBar(screen, actions)
                    Page(screen, actions, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Page(screen, actions, Modifier.weight(1f).fillMaxWidth())
                    // a phone shows a conversation full screen, with its own back button
                    if (!(screen.tab == Tab.CHATS && screen.view.conversationOpen)) BottomTabs(screen, actions)
                }
            }
            if (screen.menuOpen) MenuPanel(screen, actions, wide)
        }
    }
}

@Composable
private fun SideBar(screen: Screen.Chat, actions: TalariaActions) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(Brand.Rail).padding(horizontal = 14.dp, vertical = 20.dp)
            .testTag("side-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Talaria", color = Color.White, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 20.dp))
        screen.tabs.forEach { tab ->
            val on = tab == screen.tab
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(if (on) Brand.RailSelected else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { actions.selectTab(tab) }.padding(horizontal = 14.dp).testTag("tab-${tab.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TalariaIcons.forTab(tab), null, tint = if (on) Color.White else Brand.RailText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(tab.label, color = if (on) Color.White else Brand.RailText)
            }
        }
    }
}

@Composable
private fun BottomTabs(screen: Screen.Chat, actions: TalariaActions) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().testTag("bottom-tabs")) {
                screen.tabs.forEach { tab ->
                    val on = tab == screen.tab
                    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp).clickable { actions.selectTab(tab) }
                            .padding(vertical = 6.dp).testTag("tab-${tab.name.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(TalariaIcons.forTab(tab), null, tint = color, modifier = Modifier.size(22.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = color,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(screen: Screen.Chat, actions: TalariaActions, modifier: Modifier) {
    when (screen.tab) {
        // the chat screens keep their own headers; the ☰ button joins them there
        Tab.CHATS -> Box(modifier) {
            ChatHome(screen.view, actions, menu = { MenuButton(actions) })
        }
        else -> Column(modifier) {
            TopBar(screen.home.date, actions)
            Box(Modifier.weight(1f)) {
                when (screen.tab) {
                    Tab.HOME -> HomeScreen(screen, actions)
                    Tab.FILES -> FilesScreen(screen.files, actions)
                    Tab.SCHEDULE -> ScheduleScreen(screen.schedule, actions)
                    Tab.CHATS -> {}  // shown above, with its own headers
                }
            }
            ActionBar(screen.view.voice, actions)
        }
    }
}

@Composable
private fun TopBar(date: String, actions: TalariaActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag("date"))
        MenuButton(actions)
    }
}

@Composable
private fun MenuButton(actions: TalariaActions, modifier: Modifier = Modifier) {
    IconButton(onClick = { actions.setMenuOpen(true) }, modifier = modifier.testTag("menu")) {
        Icon(TalariaIcons.Menu, "Open menu")
    }
}

/** Chat and Talk, at the bottom of Home (and later the other pages). */
@Composable
private fun ActionBar(voice: VoiceView, actions: TalariaActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = actions::startChat,
            modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 160.dp).testTag("start-chat"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(TalariaIcons.Chat, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chat with Hermes")
        }
        Button(
            onClick = actions::talk,
            modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 120.dp).testTag("talk"),
            shape = RoundedCornerShape(14.dp),
            colors = if (voice.listening) ButtonDefaults.buttonColors(containerColor = Color(0xFFB4531A)) else ButtonDefaults.buttonColors(),
        ) {
            Icon(TalariaIcons.Mic, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (voice.listening) "Listening…" else "Talk")
        }
    }
}

/** A white card with a heading, as on Home. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
private fun MenuPanel(screen: Screen.Chat, actions: TalariaActions, wide: Boolean) {
    val menu = screen.menu
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        // the backdrop closes the menu; beside the panel, not around it, so the panel's rows keep their own semantics
        Box(
            Modifier.matchParentSize().background(Color(0x47_1B2229))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.setMenuOpen(false) },
        )
        Column(
            Modifier.fillMaxHeight().width(if (wide) 360.dp else 310.dp).background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) { detectTapGestures {} }  // taps on the panel's empty space stay on it
                .verticalScroll(rememberScrollState()).padding(20.dp).testTag("menu-panel"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Menu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.setMenuOpen(false) }, modifier = Modifier.testTag("close-menu")) {
                    Icon(TalariaIcons.Close, "Close menu")
                }
            }

            MenuSection("Running now") {
                if (menu.running.isEmpty()) {
                    Text("Nothing running.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("nothing-running"))
                }
                menu.running.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = r.conversationId != null) {
                            r.conversationId?.let(actions::openConversation)
                        }.padding(vertical = 6.dp).testTag("running-item"),
                    ) {
                        Box(Modifier.padding(top = 7.dp).size(8.dp).background(Brand.Busy, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (menu.balances.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val uri = LocalUriHandler.current
                menu.balances.forEach { b ->
                    MenuSection("${b.name} balance") {
                        if (b.amount != null) {
                            Text(b.amount, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("menu-balance"))
                        } else {
                            Text(b.error ?: "Unknown", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { runCatching { uri.openUri(b.topUpUrl) } }, modifier = Modifier.testTag("menu-top-up")) {
                            Text("Add credits")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MenuSection("Connection") {
                menu.connection.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.size(8.dp).background(row.health.color(), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("${row.label}: ${row.value}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = actions::showStatus, modifier = Modifier.testTag("connection-details")) {
                    Text("Connection details")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MenuSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

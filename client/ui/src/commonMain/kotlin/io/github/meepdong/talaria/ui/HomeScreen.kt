package io.github.meepdong.talaria.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Home: today at a glance. */
@Composable
fun HomeScreen(screen: Screen.Chat, actions: TalariaActions) {
    val home = screen.home
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SectionCard(
            "Recent chats",
            modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp),
            trailing = { TextButton(onClick = { actions.selectTab(Tab.CHATS) }) { Text("All chats") } },
        ) {
            if (home.recent.isEmpty()) {
                Text("No chats yet. Start one with Chat with Hermes below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            home.recent.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.openConversation(c.id) }
                        .padding(vertical = 8.dp).testTag("recent-${c.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        if (c.preview.isNotEmpty()) {
                            Text(c.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(if (c.running) "Replying…" else c.time, style = MaterialTheme.typography.labelMedium,
                        color = if (c.running) Brand.Busy else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

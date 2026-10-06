package io.github.meepdong.talaria.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.ui.TalariaTheme
import io.github.meepdong.talaria.ui.TalkPhase

/**
 * Talk over the lock screen (WP L): what Talk is doing and hearing, and End, nothing else. The chats stay behind
 * the lock; Unlock shows them, and approvals are given there.
 */
@Composable
fun LockedTalk(phase: TalkPhase?, heard: String, onTap: () -> Unit, onEnd: () -> Unit, onUnlock: () -> Unit) = TalariaTheme {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
                .clickable(enabled = phase != null, onClick = onTap).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(phase?.label ?: "Starting Talk…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                when (phase) {
                    TalkPhase.LISTENING -> heard.ifEmpty { "Say something, or stay quiet to finish" }
                    TalkPhase.THINKING, TalkPhase.SPEAKING -> heard.ifEmpty { "Tap to interrupt" }
                    else -> heard
                },
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onUnlock, modifier = Modifier.weight(1f)) { Text("Unlock") }
            Button(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("End") }
        }
    }
}

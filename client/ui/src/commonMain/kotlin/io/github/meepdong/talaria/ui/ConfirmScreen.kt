package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI.md §1, right: the code to compare with the terminal while the operator approves. */
@Composable
fun ConfirmScreen(screen: Screen.Confirm, actions: TalariaActions, windowSeconds: Int = 120) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Confirm pairing", style = MaterialTheme.typography.headlineSmall)
        Text("Check that your terminal shows the same code:", style = MaterialTheme.typography.bodyLarge)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                screen.digits,
                fontSize = 44.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("sas-digits"),
            )
            Text(screen.emoji.joinToString("  "), fontSize = 40.sp, modifier = Modifier.testTag("sas-emoji"))
            Text(
                screen.emojiNames.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Then type  y  in the terminal to approve.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "If anything differs, press Cancel and type n in the terminal. Someone may be in the middle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { (screen.secondsLeft.toFloat() / windowSeconds).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val m = screen.secondsLeft / 60
            val s = (screen.secondsLeft % 60).toString().padStart(2, '0')
            Text(
                if (screen.secondsLeft > 0) "Waiting… ($m:$s)" else "Still waiting for the terminal…",
                modifier = Modifier.testTag("countdown"),
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = actions::cancelPairing, modifier = Modifier.testTag("cancel")) { Text("Cancel") }
        }
    }
}

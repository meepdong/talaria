package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a swipe does: [label] shows behind the row as it moves ("Archive", "Mark read"). */
class SwipeAction(val label: String, val color: Color, val run: () -> Unit)

/** The colours swipes use everywhere, so a direction always looks like what it does. */
object SwipeColors {
    val Archive = Color(0xFF8A5A14)
    val Read = Color(0xFF1D5A85)
    val Done = Color(0xFF2E7D4F)
    val Delete = Color(0xFFB3261E)
}

/**
 * A row that does [left] when swiped left and [right] when swiped right (spec/README.md §18, UX1).
 * The row springs back after either; a row whose item goes away (archived, deleted) simply leaves the list.
 * Both actions are also custom accessibility actions, and every place that uses this offers them in a long-press
 * menu too, so nothing needs the gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(
    key: Any,
    left: SwipeAction?,
    right: SwipeAction?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = key(key) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            customActions = listOfNotNull(left, right).map { a -> CustomAccessibilityAction(a.label) { a.run(); true } }
        },
        enableDismissFromStartToEnd = right != null,
        enableDismissFromEndToStart = left != null,
        onDismiss = { value ->
            val action = if (value == SwipeToDismissBoxValue.EndToStart) left else right
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            action?.run()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val toLeft = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart -> left
                SwipeToDismissBoxValue.StartToEnd -> right
                else -> null
            }
            Row(
                Modifier.fillMaxSize().background(action?.color ?: Color.Transparent).padding(horizontal = 20.dp),
                horizontalArrangement = if (toLeft) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                action?.let { Text(it.label, color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

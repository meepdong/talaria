package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The whole app for one [screen]. [connectExtras] go above the pairing fields (the QR
 * scanner on Android) and [statusExtras] on the status screen (platform settings).
 */
@Composable
fun TalariaApp(
    screen: Screen,
    actions: TalariaActions,
    connectExtras: @Composable ColumnScope.() -> Unit = {},
    statusExtras: @Composable ColumnScope.() -> Unit = {},
) {
    TalariaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                is Screen.Connect -> ConnectScreen(screen, actions, connectExtras)
                is Screen.Confirm -> ConfirmScreen(screen, actions)
                is Screen.Status -> StatusScreen(screen.view, actions, statusExtras)
                is Screen.Server -> ServerScreen(screen.view, actions)
                is Screen.BotEditor -> BotEditorScreen(screen.view, actions)
                is Screen.Terminal -> TerminalScreen(screen.view, actions)
                is Screen.Chat -> MainScreen(screen, actions)
            }
        }
    }
}

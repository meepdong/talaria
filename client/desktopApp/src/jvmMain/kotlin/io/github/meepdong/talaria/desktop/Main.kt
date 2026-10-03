package io.github.meepdong.talaria.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import io.github.meepdong.talaria.chat.MessageState
import io.github.meepdong.talaria.security.desktop.DesktopKeyStore
import io.github.meepdong.talaria.session.ConnectionLog
import io.github.meepdong.talaria.session.FilePairingStore
import io.github.meepdong.talaria.ui.Prefs
import io.github.meepdong.talaria.ui.Screen
import io.github.meepdong.talaria.ui.Tab
import io.github.meepdong.talaria.ui.TalariaApp
import io.github.meepdong.talaria.ui.TalariaController
import io.github.meepdong.talaria.ui.color
import io.github.meepdong.talaria.ui.overall
import io.github.meepdong.talaria.ui.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.io.File
import java.net.InetAddress
import javax.swing.JOptionPane

/** This computer's name, which the bridge shows in `talaria devices`. */
fun defaultDeviceName(env: Map<String, String> = System.getenv()): String =
    (env["COMPUTERNAME"] ?: env["HOSTNAME"] ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
        ?.takeIf { it.isNotBlank() }?.take(64) ?: "Desktop"

fun main(args: Array<String>) {
    // %APPDATA%\Talaria on Windows, ~/.config/talaria on Linux: the key, bridge.json and the log.
    val dataDir = DesktopKeyStore.defaultDataDir()
    val lock = AppLock.acquire(File(dataDir, "app.lock"))
    if (lock == null) {
        JOptionPane.showMessageDialog(null, "Talaria is already running. Open it from its tray icon.", "Talaria",
            JOptionPane.INFORMATION_MESSAGE)
        return
    }

    val windows = System.getProperty("os.name").startsWith("Windows")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logFile = File(dataDir, "connection.log")
    val controller = TalariaController(
        scope = scope,
        keyStore = DesktopKeyStore.forThisComputer(dataDir),
        pairingStore = FilePairingStore(File(dataDir, "bridge.json")),
        platform = if (windows) "windows" else "linux",
        defaultDeviceName = defaultDeviceName(),
        log = ConnectionLog(ConnectionLog.fileSink(logFile)),
        imageDecoder = { bytes -> Image.makeFromEncoded(bytes).toComposeImageBitmap() },
        speechOutput = DesktopVoice.forThisComputer(windows),
        prefs = Prefs.FileBacked(File(dataDir, "settings.properties")),
    )
    controller.setSpeechInput(VoskInput.forThisComputer(File(dataDir, "speech"), scope))
    controller.setFilePicker { photos ->
        val files = Attachments.pick(photos)  // on the UI thread: the dialog is modal
        if (files.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val (ready, problem) = Attachments.prepare(files)
                controller.addAttachments(ready, problem)
            }
        }
    }
    val opened = OpenedFiles(File(dataDir, "opened"), windows)
    controller.setFileOpener { name, _, bytes -> opened.open(name, bytes) }
    controller.start()
    val loginItem = LoginItem.forThisComputer()
    val startHidden = LoginItem.MINIMIZED_FLAG in args

    application {
        val screen by controller.screen.collectAsState()
        // Without a tray (some Linux desktops), closing the window has to quit.
        val tray = isTraySupported
        var visible by remember { mutableStateOf(!(startHidden && tray)) }
        val icon = remember { loadAppIcon() }
        val trayIcon = remember(screen.overall) { TrayIconPainter(icon, screen.overall.color()) }
        val trayState = rememberTrayState()
        var focused by remember { mutableStateOf(false) }

        // A toast for automation results for Home (the morning summary), unless Home is in front of you.
        LaunchedEffect(Unit) {
            controller.automationResults.collect { ran ->
                val home = (controller.screen.value as? Screen.Chat)?.tab == Tab.HOME
                if (!(visible && focused && home) && tray) {
                    val text = if (ran.run.status == "error") "It failed: ${ran.run.error ?: "unknown error"}" else ran.run.text.orEmpty()
                    trayState.sendNotification(Notification(ran.name, text.take(240)))
                }
            }
        }

        // A toast for replies that finish while their conversation isn't in front of you.
        LaunchedEffect(Unit) {
            controller.replies.collect { reply ->
                val open = (controller.screen.value as? Screen.Chat)?.takeIf { it.tab == Tab.CHATS }?.view?.openId ==
                    reply.conversationId
                if (!(visible && focused && open) && tray) {
                    val text = when (reply.state) {
                        MessageState.DONE -> reply.text.ifBlank { "(empty reply)" }
                        MessageState.CANCELLED -> "Stopped"
                        else -> "The reply failed: ${reply.error ?: "unknown error"}"
                    }
                    trayState.sendNotification(Notification(reply.title, text.take(240)))
                }
            }
        }

        fun quit() {
            controller.close()
            scope.cancel()
            lock.release()
            exitApplication()
        }

        if (tray) {
            Tray(
                state = trayState,
                icon = trayIcon,
                tooltip = "Talaria: ${screen.summary}",
                onAction = { visible = true },
                menu = {
                    Item("Open Talaria", onClick = { visible = true })
                    Item("Reconnect now", onClick = controller::reconnectNow)
                    Separator()
                    Item("Quit", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = { if (tray) visible = false else quit() },
            visible = visible,
            title = "Talaria",
            icon = icon,
            // Wide enough for the chat list beside the conversation.
            state = rememberWindowState(width = 1040.dp, height = 760.dp),
        ) {
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) { focused = windowFocused }
            TalariaApp(screen, controller) {
                DesktopSettings(loginItem, logFile, keepsRunning = tray)
            }
        }
    }
}

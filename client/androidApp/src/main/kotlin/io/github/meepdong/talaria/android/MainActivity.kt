package io.github.meepdong.talaria.android

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.ui.FileOpener
import io.github.meepdong.talaria.ui.Screen
import io.github.meepdong.talaria.ui.Tab
import io.github.meepdong.talaria.ui.TalariaApp
import io.github.meepdong.talaria.ui.paired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as TalariaApplication
    private var resumed by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // after a rotation the same intent comes back; it was handled the first time
        if (savedInstanceState == null) {
            handleLink(intent)
            OpenedFiles.clear(this)
        }
        setContent {
            val controller = app.controller
            val screen by controller.screen.collectAsState()
            var scanning by rememberSaveable { mutableStateOf(false) }

            // Once paired, the service holds the session in the background.
            val paired = screen.paired
            LaunchedEffect(paired) { if (paired) ConnectionService.start(this@MainActivity) }

            // Reply notifications need permission on Android 13+; ask once paired.
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(paired) {
                if (paired && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // 📎: the photo picker for photos, the document picker for anything else
            val io = rememberCoroutineScope()
            val onPicked = { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    io.launch(Dispatchers.IO) {
                        val (files, problem) = AndroidAttachments.prepare(this@MainActivity, uris)
                        controller.addAttachments(files, problem)
                    }
                }
            }
            // as many as the system picker allows (often 100); the controller keeps the first 128
            val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPicked(it) }
            val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
            DisposableEffect(controller) {
                controller.setFilePicker { photos ->
                    if (photos) pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else pickFiles.launch(arrayOf("*/*"))
                }
                onDispose { controller.setFilePicker(null) }
            }

            // Share in a message's menu: the system share sheet
            DisposableEffect(controller) {
                controller.setTextSharer { text ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    startActivity(Intent.createChooser(send, null))
                }
                onDispose { controller.setTextSharer(null) }
            }

            // Open on the Files page: a copy lent to the app that opens it
            DisposableEffect(controller) {
                controller.setFileOpener(object : FileOpener {
                    override fun target(name: String) = OpenedFiles.target(this@MainActivity, name)
                    override fun open(file: java.io.File, mime: String) = OpenedFiles.open(this@MainActivity, file, mime)
                })
                onDispose { controller.setFileOpener(null) }
            }

            // 🎤: the speech recogniser, which asks for the microphone the first time
            var onMicAnswer by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                onMicAnswer?.invoke(granted)
                onMicAnswer = null
            }
            DisposableEffect(controller) {
                if (AndroidDictation.isAvailable(this@MainActivity)) {
                    controller.setSpeechInput(AndroidDictation(this@MainActivity) { answer ->
                        onMicAnswer = answer
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
                onDispose { controller.setSpeechInput(null) }
            }

            // Back: closes the menu, then a conversation, then Connection, then any page to Home.
            // The last BackHandler declared wins.
            val current = screen
            BackHandler(enabled = current is Screen.Chat && current.tab != Tab.HOME) { controller.selectTab(Tab.HOME) }
            BackHandler(enabled = current is Screen.Chat && current.tab == Tab.CHATS && current.view.conversationOpen) {
                controller.closeConversation()
            }
            BackHandler(enabled = current is Screen.Status && current.view.canGoBack) { controller.showChats() }
            BackHandler(enabled = current is Screen.Server) { controller.showChats() }
            BackHandler(enabled = current is Screen.Terminal) {
                if ((current as Screen.Terminal).view.open != null) controller.closeTerminal() else controller.showChats()
            }
            BackHandler(enabled = current is Screen.Chat && current.menuOpen) { controller.setMenuOpen(false) }

            // a terminal on screen takes it all: the system bars come back with a swipe
            val immersive = current is Screen.Terminal && current.view.open != null && current.view.text != null
            LaunchedEffect(immersive) {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                if (immersive) {
                    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    bars.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            DisposableEffect(controller) {
                controller.setAuthenticator(Unlock(this@MainActivity))
                onDispose { controller.setAuthenticator(null) }
            }

            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (scanning && screen is Screen.Connect) {
                    QrScanScreen(
                        owner = this@MainActivity,
                        onLink = { link ->
                            scanning = false
                            controller.pairWithLink(link, (screen as? Screen.Connect)?.deviceName.orEmpty())
                        },
                        onCancel = { scanning = false },
                    )
                } else {
                    TalariaApp(
                        screen = screen,
                        actions = controller,
                        connectExtras = {
                            Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Scan the QR code")
                            }
                        },
                        statusExtras = { AndroidSettings(app.logFile, resumed) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed++
        app.controller.resumeUpdate()  // back from "Install unknown apps": the update carries on
    }

    override fun onStart() {
        super.onStart()
        app.visible = true
        app.controller.setForeground(true)  // a terminal locks again after 5 minutes away
    }

    override fun onStop() {
        app.visible = false
        app.controller.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Shared from another app: read the files now, while this activity may still read them. */
    private fun receiveShare(intent: Intent) {
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }
        }
        // content:// from other apps only: a file:// path or our own authority could hand
        // Talaria's private files (the pairing) to the chat
        val shared = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(packageName) != true }
        val refused = if (shared.size < uris.size) "Some shared items couldn't be read" else null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        intent.action = null
        Thread {
            val (files, problem) = AndroidAttachments.prepare(this, shared)
            app.controller.showChats()
            app.controller.receiveShare(files, text, problem ?: refused)
        }.start()
    }

    /** A tapped talaria://pair#… link starts pairing, if this phone isn't paired yet. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            receiveShare(intent)
            return
        }
        if (intent?.getBooleanExtra(OpsNotifier.EXTRA_SERVER, false) == true) {
            // opened from a server approval
            intent.removeExtra(OpsNotifier.EXTRA_SERVER)
            app.controller.showServer()
            return
        }
        if (intent?.getBooleanExtra(AutomationNotifier.EXTRA_HOME, false) == true) {
            // opened from an automation's result
            intent.removeExtra(AutomationNotifier.EXTRA_HOME)
            app.controller.showChats()
            app.controller.selectTab(Tab.HOME)
            return
        }
        intent?.getStringExtra(ReplyNotifier.EXTRA_CONVERSATION)?.let { conv ->
            // opened from a reply notification
            intent.removeExtra(ReplyNotifier.EXTRA_CONVERSATION)
            app.controller.showChats()
            app.controller.openConversation(conv)
            return
        }
        val link = intent?.dataString ?: return
        if (!link.startsWith(PairingPayload.LINK_PREFIX)) return
        val screen = app.controller.screen.value
        if (screen is Screen.Connect && !screen.busy) app.controller.pairWithLink(link, screen.deviceName)
    }
}

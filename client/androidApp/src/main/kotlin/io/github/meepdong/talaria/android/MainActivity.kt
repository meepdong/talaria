package io.github.meepdong.talaria.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.meepdong.talaria.protocol.PairingPayload
import io.github.meepdong.talaria.ui.Screen
import io.github.meepdong.talaria.ui.TalariaApp

class MainActivity : ComponentActivity() {
    private val app get() = application as TalariaApplication
    private var resumed by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLink(intent)
        setContent {
            val controller = app.controller
            val screen by controller.screen.collectAsState()
            var scanning by rememberSaveable { mutableStateOf(false) }

            // Once paired, the service holds the session in the background.
            val paired = screen is Screen.Status
            LaunchedEffect(paired) { if (paired) ConnectionService.start(this@MainActivity) }

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A tapped talaria://pair#… link starts pairing, if this phone isn't paired yet. */
    private fun handleLink(intent: Intent?) {
        val link = intent?.dataString ?: return
        if (!link.startsWith(PairingPayload.LINK_PREFIX)) return
        val screen = app.controller.screen.value
        if (screen is Screen.Connect && !screen.busy) app.controller.pairWithLink(link, screen.deviceName)
    }
}

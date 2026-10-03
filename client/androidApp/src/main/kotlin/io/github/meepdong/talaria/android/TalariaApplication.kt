package io.github.meepdong.talaria.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import io.github.meepdong.talaria.session.ConnectionLog
import io.github.meepdong.talaria.session.FilePairingStore
import io.github.meepdong.talaria.ui.TalariaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Owns the one [TalariaController]; the activity and the service both use it. */
class TalariaApplication : Application() {
    lateinit var controller: TalariaController
        private set

    val logFile: File get() = File(filesDir, "connection.log")

    /** True while MainActivity is started, so replies on screen don't also notify. */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        controller = TalariaController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            keyStore = AndroidDeviceKeyStore(),
            pairingStore = FilePairingStore(File(filesDir, "bridge.json")),
            platform = "android",
            defaultDeviceName = deviceName(),
            log = ConnectionLog(ConnectionLog.fileSink(logFile)),
        )
        controller.start()
    }

    /** The name people gave the phone ("OnePlus 10 Pro"), or the maker and model. */
    private fun deviceName(): String =
        (Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}").take(64)
}

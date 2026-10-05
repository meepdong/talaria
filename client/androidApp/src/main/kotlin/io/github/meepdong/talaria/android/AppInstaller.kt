package io.github.meepdong.talaria.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.meepdong.talaria.ui.AppUpdater

/**
 * Installs an update the bridge offered (spec §17) through Android's session installer. Android checks that it's
 * signed with the same key as this app. On Android 12+ a self-update needs no system prompt; older versions, and
 * the first update, show Android's own confirmation.
 */
class AppInstaller(private val context: Context) : AppUpdater {
    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Once: Android's "Install unknown apps" switch for Talaria. MainActivity.onResume carries on. */
    override fun askPermission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands [apk] to the system installer; the result arrives in [InstallReceiver]. */
    override fun install(apk: ByteArray) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.size.toLong())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("talaria.apk", 0, apk.size.toLong()).use { out ->
                out.write(apk)
                session.fsync(out)
            }
            // mutable: the installer adds the status to it
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }
}

/** The installer's answer: ask the user when Android wants to, or say why it failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // this process ends; UpdatedReceiver starts the new one
            // the user closed Android's dialog: Update simply works again
            PackageInstaller.STATUS_FAILURE_ABORTED -> (context.applicationContext as TalariaApplication).controller.updateFailed(null)
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "the installer refused it"
                (context.applicationContext as TalariaApplication).controller.updateFailed(why)
            }
        }
    }
}

/** After an update, reconnect straight away rather than when the app is next opened. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ConnectionService.start(context)
    }
}

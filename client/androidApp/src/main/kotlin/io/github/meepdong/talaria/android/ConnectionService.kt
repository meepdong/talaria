package io.github.meepdong.talaria.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.meepdong.talaria.ui.Health
import io.github.meepdong.talaria.ui.Screen
import io.github.meepdong.talaria.ui.Tab
import io.github.meepdong.talaria.ui.color
import io.github.meepdong.talaria.ui.overall
import io.github.meepdong.talaria.ui.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A foreground service (type specialUse) that keeps the process, and so the bridge
 * session in [TalariaApplication], alive while the app is in the background. Its quiet
 * notification shows the connection state. It stops once the phone is no longer paired.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val app get() = application as TalariaApplication
    private val controller get() = app.controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ReplyNotifier.createChannel(this)
        AutomationNotifier.createChannel(this)
        OpsNotifier.createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(Health.UNKNOWN, "Starting"), type)

        scope.launch {
            var paired = false
            controller.screen
                .map { s -> if (s is Screen.Connect) null else s.overall to s.summary }
                .distinctUntilChanged()
                .collectLatest { state ->
                    if (state != null) {
                        paired = true
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(state.first, state.second))
                    } else {
                        // Not paired (or just forgotten). Right after a restart the saved
                        // pairing takes a moment to load, so give it a few seconds.
                        if (!paired) delay(10_000)
                        stopSelf()
                    }
                }
        }

        // Replies that finish while their conversation isn't on screen.
        scope.launch {
            controller.replies.collect { reply ->
                val view = (controller.screen.value as? Screen.Chat)?.takeIf { it.tab == Tab.CHATS }?.view
                val showing = app.visible && view != null && view.conversationOpen && view.openId == reply.conversationId
                if (!showing) ReplyNotifier.show(this@ConnectionService, reply)
            }
        }

        // Automation results for Home, unless Home is on screen.
        scope.launch {
            controller.automationResults.collect { ran ->
                val home = (controller.screen.value as? Screen.Chat)?.tab == Tab.HOME
                if (!(app.visible && home)) AutomationNotifier.show(this@ConnectionService, ran)
            }
        }

        // A result read or archived on any device takes its notification with it.
        scope.launch {
            controller.homeUnread.collect { unread -> AutomationNotifier.keepOnly(this@ConnectionService, unread) }
        }

        // Server operations waiting for approval: a notification each until answered (PROTOCOL §10.8).
        scope.launch {
            controller.opsPending.collect { pending -> OpsNotifier.update(this@ConnectionService, pending) }
        }

        // A network coming back (Wi-Fi, mobile data, Tailscale) is worth a retry now
        // rather than at the end of the backoff.
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = controller.reconnectNow()
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT) controller.reconnectNow()
        if (intent?.action == OpsNotifier.ACTION_ANSWER) {
            // Allow or Deny from an approval notification; the notification closes on ops.approval.done
            val request = intent.getStringExtra(OpsNotifier.EXTRA_REQUEST)
            val choice = intent.getStringExtra(OpsNotifier.EXTRA_CHOICE)
            if (request != null && (choice == "once" || choice == "deny")) controller.opsApprove(request, choice)
        }
        if (intent?.action == AutomationNotifier.ACTION_READ) {
            val id = intent.getStringExtra(AutomationNotifier.EXTRA_ID)
            val at = intent.getLongExtra(AutomationNotifier.EXTRA_AT, -1)
            if (id != null && at >= 0) controller.markHomeRead(id, at, true)
        }
        if (intent?.action == ReplyNotifier.ACTION_REPLY) {
            val conv = intent.getStringExtra(ReplyNotifier.EXTRA_CONVERSATION)
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ReplyNotifier.KEY_TEXT)?.toString()
            if (conv != null && !text.isNullOrBlank()) {
                controller.replyFromNotification(conv, text)
                ReplyNotifier.clear(this, conv)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows whether Talaria is connected to your server"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(health: Health, summary: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reconnect = PendingIntent.getService(this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_RECONNECT), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(health.color().toArgb())
            .setContentTitle("Talaria")
            .setContentText(summary)
            .setContentIntent(open)
        // Connected: just say so. Reconnect now only when there's something to reconnect.
        if (health == Health.BAD || health == Health.UNKNOWN) builder.addAction(0, "Reconnect now", reconnect)
        return builder
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RECONNECT = "io.github.meepdong.talaria.RECONNECT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}

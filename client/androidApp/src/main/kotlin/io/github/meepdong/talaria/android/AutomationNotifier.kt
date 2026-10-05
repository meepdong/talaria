package io.github.meepdong.talaria.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.meepdong.talaria.schedule.AutomationRan
import io.github.meepdong.talaria.ui.notificationText

/** Results of automations that report to Home, such as the morning summary, and blocked runs (spec/README.md §14). */
object AutomationNotifier {
    private const val CHANNEL = "automations"
    const val EXTRA_HOME = "open_home"

    /** Swiped away in Android: the result counts as read, on every device (UX1). */
    const val ACTION_READ = "io.github.meepdong.talaria.HOME_READ"
    const val EXTRA_ID = "automation_id"
    const val EXTRA_AT = "run_at"

    /** What's showing, as "id@at", so one read or archived anywhere can be taken down. */
    private val shown = mutableSetOf<String>()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Results of your automations, such as the morning summary"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** One notification per automation, so today's result replaces yesterday's. */
    private fun id(automationId: String) = 3_000_000 + (automationId.hashCode() and 0xFFFFF)

    fun show(context: Context, ran: AutomationRan) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = ran.notificationText()
        val code = id(ran.id)
        val open = PendingIntent.getActivity(context, code,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_HOME, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ran.name)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(4000)))
            .setContentIntent(open)
            .setDeleteIntent(PendingIntent.getService(context, code,
                Intent(context, ConnectionService::class.java).setAction(ACTION_READ)
                    .putExtra(EXTRA_ID, ran.id).putExtra(EXTRA_AT, ran.run.at),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(code, n)
        synchronized(shown) {
            shown.removeAll { it.substringBefore('@') == ran.id }
            shown += "${ran.id}@${ran.run.at}"
        }
    }

    /** Take down what's no longer [unread] on Home: read or archived here or on another device. */
    fun keepOnly(context: Context, unread: Set<String>) {
        val gone = synchronized(shown) { shown.filter { it !in unread }.also { shown.removeAll(it.toSet()) } }
        val nm = context.getSystemService(NotificationManager::class.java)
        gone.forEach { nm.cancel(id(it.substringBefore('@'))) }
    }
}

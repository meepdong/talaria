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

/** Results of automations that report to Home, such as the morning summary (spec/README.md §14). */
object AutomationNotifier {
    private const val CHANNEL = "automations"
    const val EXTRA_HOME = "open_home"

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
        val text = if (ran.run.status == "error") "It failed: ${ran.run.error ?: "unknown error"}" else ran.run.text.orEmpty()
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
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(code, n)
    }
}

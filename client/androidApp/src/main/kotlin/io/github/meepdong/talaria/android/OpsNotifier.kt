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
import io.github.meepdong.talaria.ui.OpsApprovalItem

/** Server operations waiting for the owner's approval (PROTOCOL §10.8). Tapping one opens the Server page. */
object OpsNotifier {
    private const val CHANNEL = "approvals"
    const val EXTRA_SERVER = "open_server"
    const val ACTION_ANSWER = "io.github.meepdong.talaria.OPS_ANSWER"
    const val EXTRA_REQUEST = "request_id"
    const val EXTRA_CHOICE = "choice"
    private val shown = mutableSetOf<String>()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Server approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Hermes or another device asks to change the server"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun id(requestId: String) = 4_000_000 + (requestId.hashCode() and 0xFFFFF)

    private fun answer(context: Context, requestId: String, choice: String, label: String, unlock: Boolean): NotificationCompat.Action {
        val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_ANSWER)
            .putExtra(EXTRA_REQUEST, requestId).putExtra(EXTRA_CHOICE, choice)
        val pending = PendingIntent.getService(context, id(requestId) + if (choice == "once") 1 else 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, pending).setAuthenticationRequired(unlock).build()
    }

    /** Show new approvals and clear the ones that were answered or expired. */
    fun update(context: Context, pending: List<OpsApprovalItem>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ids = pending.map { it.requestId }.toSet()
        (shown - ids).forEach { nm.cancel(id(it)) }
        shown.retainAll(ids)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        for (a in pending) {
            if (!shown.add(a.requestId)) continue
            val code = id(a.requestId)
            val open = PendingIntent.getActivity(context, code,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_SERVER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${a.from.replaceFirstChar { it.uppercase() }} asks to")
                .setContentText(a.summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(a.summary, a.detail).filter { it.isNotEmpty() }.joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Answer from the shade. Allow needs the phone unlocked, so nobody can approve from a locked phone;
            // a reboot (tier 2) only offers Deny here, because Allow asks once more in the app.
            if (a.tier < 2) builder.addAction(answer(context, a.requestId, "once", "Allow", unlock = true))
            builder.addAction(answer(context, a.requestId, "deny", "Deny", unlock = false))
            nm.notify(code, builder.build())
        }
    }
}

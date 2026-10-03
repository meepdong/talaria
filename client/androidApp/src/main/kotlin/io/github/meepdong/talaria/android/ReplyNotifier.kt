package io.github.meepdong.talaria.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.meepdong.talaria.chat.FinishedReply
import io.github.meepdong.talaria.chat.MessageState

/**
 * "Hermes replied" notifications, with a Reply action that answers straight from the
 * shade. The foreground service keeps the session open, so no push service is needed.
 */
object ReplyNotifier {
    private const val CHANNEL = "replies"
    const val ACTION_REPLY = "io.github.meepdong.talaria.REPLY"
    const val EXTRA_CONVERSATION = "conversation_id"
    const val KEY_TEXT = "reply_text"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Replies from your agent while Talaria isn't on screen"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** One notification per conversation, so a newer reply replaces the older one. */
    private fun id(conversationId: String) = 1000 + (conversationId.hashCode() and 0xFFFFF)

    fun show(context: Context, reply: FinishedReply) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = when (reply.state) {
            MessageState.DONE -> reply.text.ifBlank { "(empty reply)" }
            MessageState.CANCELLED -> "Stopped"
            else -> "The reply failed: ${reply.error ?: "unknown error"}"
        }
        val code = id(reply.conversationId)
        val open = PendingIntent.getActivity(context, code,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CONVERSATION, reply.conversationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // RemoteInput needs a mutable PendingIntent so the system can add the typed text.
        val answer = PendingIntent.getService(context, code,
            Intent(context, ConnectionService::class.java).setAction(ACTION_REPLY)
                .putExtra(EXTRA_CONVERSATION, reply.conversationId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(KEY_TEXT).setLabel("Reply to Hermes").build()
        val action = NotificationCompat.Action.Builder(0, "Reply", answer)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reply.title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2000)))
            .setContentIntent(open)
            .addAction(action)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(code, n)
    }

    /** After answering from the shade: drop the notification. */
    fun clear(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(id(conversationId))
    }
}

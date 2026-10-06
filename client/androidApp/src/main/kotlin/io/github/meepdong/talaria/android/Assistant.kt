package io.github.meepdong.talaria.android

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Talaria as the phone's default assistant (WP L). Android starts it from a long press on the power button, the
 * assistant gesture or a headset's voice button, also on the lock screen; it opens Talk in the chat last open.
 */
class TalariaVoiceService : VoiceInteractionService()

class TalariaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TalariaSession(this)
}

/** Shows nothing of its own: it starts Talk in [MainActivity], over the lock screen if the phone is locked. */
class TalariaSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val talk = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_TALK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // an assistant may start its activity even from the lock screen this way
        startAssistantActivity(talk)
        hide()
    }
}

/**
 * Android asks an assistant to name a speech recogniser too. Talaria listens through the phone's own (Google's),
 * so this one only says it can't.
 */
class TalariaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

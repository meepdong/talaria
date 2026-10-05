package io.github.meepdong.talaria.android

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import io.github.meepdong.talaria.ui.Authenticator

/**
 * The owner's fingerprint, or their screen lock (PIN, pattern, password), before a root terminal opens (spec §16.1).
 * Android's own prompt: nothing to install, and the app never sees the fingerprint.
 */
class Unlock(private val activity: Activity) : Authenticator {
    override fun authenticate(reason: String, done: (Boolean) -> Unit) = activity.runOnUiThread {
        if (!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            done(true)  // the phone has no lock to ask for
            return@runOnUiThread
        }
        val builder = BiometricPrompt.Builder(activity).setTitle("Talaria").setSubtitle(reason)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        })
    }
}

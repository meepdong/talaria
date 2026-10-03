package io.github.meepdong.talaria.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

/** Phone makers whose battery manager stops background apps unless told otherwise. */
private val STRICT_BATTERY = setOf("oneplus", "oppo", "realme")

/**
 * Battery onboarding on the status screen: notifications for the service, no battery
 * optimisation, and on OnePlus the extra "Allow background activity" switch.
 */
@Composable
fun AndroidSettings(logFile: File, resumed: Int) {
    val context = LocalContext.current
    // [resumed] changes each time the activity comes back to the front, for example from
    // the system settings, so these checks run again then.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    key(resumed) { Checks(context, logFile) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
}

@Composable
private fun Checks(context: Context, logFile: File, askNotifications: () -> Unit) {
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val batteryOk = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val strictMaker = Build.MANUFACTURER.lowercase() in STRICT_BATTERY

    Card(Modifier.fillMaxWidth().testTag("battery")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Stay connected in the background", style = MaterialTheme.typography.titleMedium)

            if (!notificationsOk) {
                Text("Allow notifications so Talaria can show that it's connected.")
                OutlinedButton(onClick = askNotifications) {
                    Text("Allow notifications")
                }
            }

            if (batteryOk) {
                Text("✓ Battery optimisation is off for Talaria")
            } else {
                Text("Android may pause Talaria to save battery, which drops the connection.")
                OutlinedButton(onClick = { context.open(batteryExemption(context)) }) { Text("Let Talaria run in the background") }
            }

            if (strictMaker) {
                Text(
                    "On OnePlus, also open App info → Battery and turn on Allow background activity " +
                        "(on some versions: Battery usage → Allow background activity).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { context.open(appDetails(context)) }) { Text("Open App info") }
            }

            Text("Log: ${logFile.path}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun batteryExemption(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(appDetails(this))
    }
}

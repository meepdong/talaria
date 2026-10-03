package io.github.meepdong.talaria.android

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens a file fetched from the server in another app: a copy under the cache's
 * `opened/`, lent through the FileProvider with read access only.
 */
object OpenedFiles {
    private fun dir(activity: Activity) = File(activity.cacheDir, "opened")

    /** Called when the app starts: the copies from last time are no longer needed. */
    fun clear(activity: Activity) {
        dir(activity).deleteRecursively()
    }

    fun open(activity: Activity, name: String, mime: String, bytes: ByteArray) {
        val safe = name.substringAfterLast('/').replace(Regex("[\\u0000-\\u001f]"), "_").trim(' ', '.').take(120)
            .ifEmpty { "file" }
        val target = File(File(dir(activity), System.nanoTime().toString()).apply { mkdirs() }, safe)
        target.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", target)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // the chooser itself says so when no app opens this kind of file
        activity.runOnUiThread { activity.startActivity(Intent.createChooser(view, safe)) }
    }
}

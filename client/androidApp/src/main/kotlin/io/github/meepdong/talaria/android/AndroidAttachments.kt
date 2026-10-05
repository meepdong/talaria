package io.github.meepdong.talaria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import io.github.meepdong.talaria.chat.OutgoingFile
import java.io.ByteArrayOutputStream

/** Photos and files to send from the phone (spec/README.md §10). */
object AndroidAttachments {
    private const val LONG_EDGE = 1568

    /** Ready to send: photos re-encoded (smaller, upright, and without EXIF, so without location); other files as they are. */
    fun prepare(context: Context, uris: List<Uri>): Pair<List<OutgoingFile>, String?> {
        val out = mutableListOf<OutgoingFile>()
        var problem: String? = null
        for (uri in uris) {
            val (name, size) = describe(context, uri)
            try {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                if (mime.startsWith("image/")) {
                    val photo = photo(context, uri, name.substringBeforeLast('.'))
                    if (photo == null) problem = "Can't read $name as a photo" else out += photo
                } else {
                    // read as it uploads, so a 2 GB video never sits in memory; count it when the picker didn't say
                    val resolver = context.contentResolver
                    val length = size ?: resolver.openInputStream(uri)?.use { input ->
                        var n = 0L
                        val buffer = ByteArray(1 shl 16)
                        while (true) { val r = input.read(buffer); if (r < 0) break; n += r }
                        n
                    }
                    when {
                        length == null -> problem = "Couldn't open $name"
                        length > OutgoingFile.MAX_SIZE -> problem = "$name is over 2 GB, too large to send"
                        else -> out += OutgoingFile(name, mime, length, { resolver.openInputStream(uri) ?: error("Couldn't open $name") })
                    }
                }
            } catch (e: Exception) {
                problem = "Couldn't read $name: ${e.message}"
            }
        }
        return out to problem
    }

    private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "file"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "file") to null
    }

    private fun photo(context: Context, uri: Uri, baseName: String): OutgoingFile? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= LONG_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val scale = minOf(1f, LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // compress() writes no EXIF, so the location and camera details stay behind
        val bytes = ByteArrayOutputStream()
        return if (upright.hasAlpha()) {
            upright.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            OutgoingFile("$baseName.png", "image/png", bytes.toByteArray())
        } else {
            upright.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            OutgoingFile("$baseName.jpg", "image/jpeg", bytes.toByteArray())
        }
    }
}

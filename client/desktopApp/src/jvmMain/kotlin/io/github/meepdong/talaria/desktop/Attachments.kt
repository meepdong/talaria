package io.github.meepdong.talaria.desktop

import io.github.meepdong.talaria.chat.OutgoingFile
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Photos and files to send from the desktop app (spec/README.md §10). */
object Attachments {
    const val LONG_EDGE = 1568
    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff")
    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "bmp" to "image/bmp", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
        "tif" to "image/tiff", "tiff" to "image/tiff",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "html" to "text/html", "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    /** The system's file dialog; blocks until the user picks or cancels. */
    fun pick(photos: Boolean): List<File> {
        val dialog = FileDialog(null as Frame?, if (photos) "Send photos" else "Send files", FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (photos) dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in PHOTO_EXTENSIONS }
        dialog.isVisible = true
        return dialog.files.orEmpty().toList()
    }

    /** Ready to send: photos re-encoded (smaller, and without EXIF, so without location); other files as they are. */
    fun prepare(files: List<File>): Pair<List<OutgoingFile>, String?> {
        val out = mutableListOf<OutgoingFile>()
        var problem: String? = null
        for (file in files) {
            try {
                val ext = file.extension.lowercase()
                val mime = MIME[ext] ?: "application/octet-stream"
                if (mime.startsWith("image/")) {
                    val image = ImageIO.read(file)
                    if (image == null) {
                        // can't re-encode it here, and sending it as is could leak where it was taken
                        problem = "Can't read ${file.name} as a photo. Convert it to JPEG or PNG first."
                        continue
                    }
                    out += photo(file.nameWithoutExtension, image)
                } else {
                    if (file.length() > OutgoingFile.MAX_SIZE) {
                        problem = "${file.name} is over 20 MB, too large to send"
                        continue
                    }
                    out += OutgoingFile(file.name, mime, file.readBytes())
                }
            } catch (e: Exception) {
                problem = "Couldn't read ${file.name}: ${e.message}"
            }
        }
        return out to problem
    }

    fun photo(baseName: String, source: BufferedImage): OutgoingFile {
        val scale = minOf(1.0, LONG_EDGE.toDouble() / maxOf(source.width, source.height))
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val alpha = source.colorModel.hasAlpha()
        val target = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream()
        if (alpha) {
            ImageIO.write(target, "png", bytes)
            return OutgoingFile("$baseName.png", "image/png", bytes.toByteArray())
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            writer.write(null, IIOImage(target, null, null), param)
            writer.dispose()
        }
        return OutgoingFile("$baseName.jpg", "image/jpeg", bytes.toByteArray())
    }
}

package io.github.meepdong.talaria.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test
    fun photosAreDownscaledAndLoseTheirMetadata() {
        val dir = Files.createTempDirectory("talaria-attach").toFile()
        val big = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        val jpeg = File(dir, "IMG_0001.JPG")
        ImageIO.write(big, "jpeg", jpeg)
        // an APP1 "Exif" segment, where cameras put GPS, spliced in after the SOI marker
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10) + "Exif\u0000\u0000GPS-HERE".toByteArray()
        val raw = jpeg.readBytes()
        jpeg.writeBytes(raw.copyOfRange(0, 2) + exif + raw.copyOfRange(2, raw.size))
        val pdf = File(dir, "report.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
        val heic = File(dir, "x.heic").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val (files, problem) = Attachments.prepare(listOf(jpeg, pdf, heic))
        assertEquals(listOf("IMG_0001.jpg" to "image/jpeg", "report.pdf" to "application/pdf"), files.map { it.name to it.mime })
        val photo = assertNotNull(ImageIO.read(ByteArrayInputStream(files[0].preview!!)))
        assertEquals(1568 to 1176, photo.width to photo.height)
        assertFalse(String(files[0].preview!!, Charsets.ISO_8859_1).contains("GPS-HERE"))
        assertTrue(problem!!.startsWith("Can't read x.heic"))
    }
}

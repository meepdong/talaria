package io.github.meepdong.talaria.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrDecoderTest {
    private val link = "talaria://pair#eyJ2IjoxLCJ1cmwiOiJ3c3M6Ly9zcnYyMDI5NjAzLnRhaWxjODU3MjcudHMubmV0Ojg0NDMvdG5wIn0"

    /** A camera-like frame: the code drawn into a wider buffer, as rowStride > width. */
    private fun frame(inverted: Boolean, width: Int = 480, height: Int = 480, rowStride: Int = 512): ByteArray {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 360, 360)
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        val dark: Byte = if (inverted) 0xE0.toByte() else 0x20
        val light: Byte = if (inverted) 0x20 else 0xE0.toByte()
        return ByteArray(rowStride * height) { i ->
            val x = i % rowStride - left
            val y = i / rowStride - top
            if (x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]) dark else light
        }
    }

    @Test
    fun readsTheLink() = assertEquals(link, QrDecoder.decode(frame(inverted = false), 512, 480, 480))

    @Test
    fun readsALightOnDarkTerminalCode() = assertEquals(link, QrDecoder.decode(frame(inverted = true), 512, 480, 480))

    @Test
    fun nothingInABlankFrame() = assertNull(QrDecoder.decode(ByteArray(512 * 480) { 0x80.toByte() }, 512, 480, 480))
}

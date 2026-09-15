package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    /**
     * Draws a QR code the way ZXing reads one: one byte per pixel, dark modules low.
     *
     * Built from the encoder rather than from a stored image, so the test proves the
     * decoder against this app's own pairing URLs and not against a fixture that could
     * drift away from them.
     */
    private fun render(text: String, scale: Int = 4, quietZone: Int = 4, stridePadding: Int = 0): Frame {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text,
            com.google.zxing.BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to quietZone)
        )
        val width = matrix.width * scale
        val height = matrix.height * scale
        val stride = width + stridePadding
        val bytes = ByteArray(stride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x / scale, y / scale)) bytes[y * stride + x] = 0
            }
        }
        return Frame(bytes, stride, width, height)
    }

    private data class Frame(val bytes: ByteArray, val stride: Int, val width: Int, val height: Int)

    private fun QrDecoder.decode(frame: Frame) = decode(frame.bytes, frame.stride, frame.width, frame.height)

    @Test
    fun readsAPairingUrl() {
        // The shape the Home Assistant integration produces, secret and all.
        val url = "https://owen282000.github.io/life-dashboard-companion-app/pair" +
            "#v=1&url=http%3A%2F%2F192.168.10.138%3A8123%2Fapi%2Fwebhook%2F" + "f".repeat(64) +
            "&secret=" + "e".repeat(64) + "&name=Home%20Assistant&sources=health_connect%2Cscreen_time"

        assertEquals(url, QrDecoder().decode(render(url)))
    }

    @Test
    fun readsTheCustomSchemeForm() {
        val url = "lifedashboard://pair#v=1&url=https%3A%2F%2Fha.example.com%2Fhook&secret=xyz"
        assertEquals(url, QrDecoder().decode(render(url)))
    }

    @Test
    fun handlesAPaddedRowStride() {
        // Camera frames are usually padded, so the stride is wider than the image.
        val url = "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz"
        assertEquals(url, QrDecoder().decode(render(url, stridePadding = 48)))
    }

    @Test
    fun oneInstanceReadsManyFramesInARow() {
        // decodeWithState plus reset() has to stay usable frame after frame.
        val decoder = QrDecoder()
        val first = "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=one"
        val second = "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=two"
        assertEquals(first, decoder.decode(render(first)))
        assertNull(decoder.decode(blank()))
        assertEquals(second, decoder.decode(render(second)))
    }

    @Test
    fun anEmptyFrameIsNotACode() {
        assertNull(QrDecoder().decode(blank()))
    }

    @Test
    fun nonsensicalDimensionsAreRefusedRatherThanCrashing() {
        val decoder = QrDecoder()
        val bytes = ByteArray(64)
        assertNull(decoder.decode(bytes, 8, 8, 0))
        assertNull(decoder.decode(bytes, 8, 0, 8))
        // A stride narrower than the image cannot be a frame.
        assertNull(decoder.decode(bytes, 4, 8, 8))
        // Too few bytes for the stated size: ZXing would read past the end.
        assertNull(decoder.decode(bytes, 32, 32, 32))
    }

    private fun blank() = Frame(ByteArray(64 * 64) { 0xFF.toByte() }, 64, 64, 64)
}

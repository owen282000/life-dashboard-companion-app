package com.owen282000.lifedashboard

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Finds a QR code in a greyscale frame.
 *
 * Free of Android and CameraX types so the decoding is covered by JVM unit tests; the
 * analyzer around it only pulls the luminance plane out of a camera frame and hands it
 * here. Only QR is looked for: the app has no use for the other formats and every extra
 * one costs work on every frame.
 *
 * Frames arrive in sensor orientation, on their side for a phone held upright. That is
 * fine: a QR code carries its own orientation in its finder patterns and ZXing reads it
 * at any angle, so nothing is rotated here.
 *
 * Not thread safe. One instance belongs to one scanner screen, which decodes on a single
 * background thread.
 */
class QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // Home Assistant draws the pairing code in the theme's colours, so in
                // dark mode it is light modules on a dark card. ZXing reads dark-on-light
                // only unless told to try the inverse as well; without this the scanner
                // read nothing from a sharp code filling the frame, while the phone's own
                // camera app, which tries both, read it at once.
                DecodeHintType.ALSO_INVERTED to true
            )
        )
    }

    /**
     * The text of the code in this frame, or null when there is none.
     *
     * [luminance] holds one byte per pixel, row by row, [rowStride] bytes apart: a camera
     * frame is usually padded, so the stride is not always the width.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // Enough rows to work with, without demanding a full padded rectangle. A camera
        // buffer routinely ends before the last row's padding, and insisting on
        // rowStride * height bytes threw every real frame away while the unit tests, which
        // build perfectly padded frames, stayed green.
        if (luminance.size < rowStride * (height - 1) + width) return null

        val source = PlanarYUVLuminanceSource(
            luminance,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            // No code in this frame, which is the normal case while the user is aiming.
            null
        } finally {
            reader.reset()
        }
    }
}

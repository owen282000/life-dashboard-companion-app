package com.owen282000.lifedashboard.screens

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.owen282000.lifedashboard.QrDecoder

/**
 * Hands camera frames to the decoder.
 *
 * Only the luminance plane is passed on, which is all ZXing needs and the cheapest thing
 * to give it: converting each frame to colour would buy nothing.
 *
 * The same text is reported once. A code stays in view for many frames, and without this
 * the confirmation dialog would be raised again on every one of them.
 */
class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()
    private var lastText: String? = null

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val text = decoder.decode(bytes, plane.rowStride, image.width, image.height)
            if (text != null && text != lastText) {
                lastText = text
                onDecoded(text)
            }
        } catch (e: Exception) {
            // A single unreadable frame is normal and there are thirty more this second.
            Log.v(TAG, "Frame not decoded", e)
        } finally {
            // CameraX stalls without this, whatever happened above.
            image.close()
        }
    }

    private companion object {
        const val TAG = "QrAnalyzer"
    }
}

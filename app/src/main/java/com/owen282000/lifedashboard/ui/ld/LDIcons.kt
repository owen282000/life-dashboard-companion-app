package com.owen282000.lifedashboard.ui.ld

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The boards' line icons, from gen_all.py. Icon's `tint` colours them. */
object LDIcons {
    private fun line(name: String, path: String, width: Float = 1.8f, mirrors: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = "ld.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = mirrors
        ).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).build()

    val whatToSend by lazy { line("what", "M22 12h-4l-3 9L9 3l-3 9H2") }
    val whenToSend by lazy { line("when", "M12 7v5l3 2 M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18") }
    val receive by lazy { line("receive", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3") }
    val more by lazy { line("more", "M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 M1 14h6 M9 8h6 M17 16h6") }
    val notifications by lazy { line("bell", "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9 M13.7 21a2 2 0 0 1-3.4 0") }
    val apps by lazy { line("apps", "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z") }
    val calendar by lazy { line("calendar", "M8 2v4 M16 2v4 M3 9h18 M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z") }
    val help by lazy { line("help", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M9.5 9a2.5 2.5 0 1 1 3.6 2.3c-.7.4-1.1 1-1.1 1.7 M12 17h.01") }
    val support by lazy { line("star", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z") }
    val privacy by lazy { line("shield", "M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z M9 12l2 2 4-4") }
    val openSource by lazy { line("code", "M8 7l-5 5 5 5 M16 7l5 5-5 5 M14 4l-4 16") }
    val heart by lazy { line("heart", "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78L12 21.23l8.84-8.84a5.5 5.5 0 0 0 0-7.78z") }
    val phone by lazy { line("phone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M12 18h.01") }
    val logs by lazy { line("logs", "M3 12a9 9 0 1 0 3-6.7L3 8 M3 3v5h5 M12 7v5l3 2") }
    val qr by lazy { line("qr", "M3 3h6v6H3z M15 3h6v6h-6z M3 15h6v6H3z M15 15h2 M21 15v2 M15 21h6v-2") }
    val padlock by lazy { line("padlock", "M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z M8 11V7a4 4 0 0 1 8 0v4", width = 2f) }
    val chevron by lazy { line("chevron", "M9 18l6-6-6-6", width = 2f, mirrors = true) }
    val back by lazy { line("back", "M19 12H5 M12 19l-7-7 7-7", width = 2f, mirrors = true) }
    val check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5", width = 3f) }
    val close by lazy { line("close", "M18 6L6 18 M6 6l12 12", width = 2f) }
}

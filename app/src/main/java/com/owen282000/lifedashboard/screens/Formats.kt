package com.owen282000.lifedashboard.screens

import androidx.annotation.StringRes
import com.owen282000.lifedashboard.PayloadPreview
import com.owen282000.lifedashboard.R
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [count] with the phone's digit grouping: 12,000 in English, 12.000 in Dutch and German. */
internal fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A time as the locale writes it, so 16:00 and 4:00 PM both read naturally. */
fun formatTime(time: LocalTime): String = time.format(TIME_FORMAT)

/** Where the full payload is, under a preview cut for display: Copy JSON when it fits, Share always. */
@StringRes
internal fun fullPayloadHint(payload: String): Int =
    if (PayloadPreview.fitsClipboard(payload)) R.string.sync_preview_full_in_copy_share else R.string.sync_preview_full_in_share

/** File name for an export share: `prefix_20260914_120000.ext`. */
fun exportFileName(prefix: String, extension: String): String {
    val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    return "${prefix}_$timestamp.$extension"
}

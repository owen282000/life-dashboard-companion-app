package com.owen282000.lifedashboard.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

/**
 * Android 13's ClipDescription.EXTRA_IS_SENSITIVE, spelled out: androidx has no compat
 * constant, and Android's own guidance is to set this key on older versions too, where a
 * keyboard's clipboard strip may honor it.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Puts a secret or health data on the clipboard. The clip is flagged sensitive, so from
 * Android 13 the clipboard overlay shows dots instead of the text, and the system shows its
 * own "copied" confirmation, so a second one from the app would only stack on top. False when
 * the clipboard refused the clip, which one too large for the binder makes it do.
 */
internal fun copySensitive(context: Context, label: String, text: String, copiedMessage: String): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    try {
        clipboard.setPrimaryClip(clip)
    } catch (e: RuntimeException) {
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
    return true
}

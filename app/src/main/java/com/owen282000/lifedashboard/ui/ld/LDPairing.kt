package com.owen282000.lifedashboard.ui.ld

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingSource

/** How the one test ping after Pair went. */
sealed interface LDPairedPing {
    data object Sending : LDPairedPing
    data object Delivered : LDPairedPing
    data class Failed(val reason: String) : LDPairedPing
}

/** A pairing that was written, and what its ping found. */
data class LDPairedResult(val link: PairingLink, val written: Set<PairingSource>, val ping: LDPairedPing)

/**
 * Pairing as a screen sees it: MainActivity shows the scanner and the confirmation, because they span
 * both tabs and the wizard, and PairingViewModel holds them across a rotation. [result] is memory
 * only, so the link's secret stays out of the saved stack: after a process death it is null, and
 * PairedResultScreen pops itself.
 */
@Stable
class LDPairing(private val scan: () -> Unit, private val ping: () -> Unit) {
    var result by mutableStateOf<LDPairedResult?>(null)

    /** Opens the QR scanner. What it finds lands in the confirmation, over whatever is showing. */
    fun startScan() = scan()

    /** The same ping again, for Try again on the Paired page. */
    fun tryAgain() = ping()
}

val LocalLDPairing = staticCompositionLocalOf<LDPairing> { error("No LDPairing: this screen runs outside MainActivity") }

package com.owen282000.lifedashboard.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.Destinations
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.OnboardingSupport
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingSource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.TestPing
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.ui.ld.LDPairedPing
import com.owen282000.lifedashboard.ui.ld.LDPairedResult
import com.owen282000.lifedashboard.ui.ld.LDPairing
import kotlinx.coroutines.launch

/**
 * What a pairing holds while it is under way: the scanner, the code waiting for an answer, and the
 * ping after Pair. Here and not in the Activity, because a rotation recreates that: the Paired page
 * would lose its result and the ping would be cancelled. In memory only, as a link holds a secret.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val preferences = application.appPreferences()

    /** A scanned or opened pairing link, waiting for the user to confirm or dismiss it. */
    var pending by mutableStateOf<PairingLink?>(null)

    /** Whether the QR scanner is open. */
    var scanning by mutableStateOf(false)

    /** The code last paired while the wizard is open, for the wizard to show. */
    var pairedInWizard by mutableStateOf<OnboardingSupport.WizardPairing?>(null)

    /** Pairing as the screens see it. */
    val pairing = LDPairing(scan = { scanning = true }, ping = ::pingPaired)

    fun pairedInWizard(link: PairingLink, written: Set<PairingSource>) {
        pairedInWizard = OnboardingSupport.WizardPairing(link = link, written = written, seq = (pairedInWizard?.seq ?: 0) + 1)
    }

    /** No page can open over the wizard, so there the ping's outcome comes as a toast. */
    fun confirmInWizard(link: PairingLink, written: Set<PairingSource>) {
        Toast.makeText(app, app.getString(R.string.pairing_ping_sending, link.host), Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            val text = pingResult(link, written).fold(
                onSuccess = { app.getString(R.string.pairing_ping_delivered, link.host) },
                onFailure = { app.getString(R.string.pairing_ping_failed, link.host, FailureReason.of(it).orEmpty()) }
            )
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    /** Outside the wizard the Paired page shows the outcome; MainActivity opens it. */
    fun confirmOnPage(link: PairingLink, written: Set<PairingSource>) {
        pairing.result = LDPairedResult(link, written, LDPairedPing.Sending)
        pingPaired()
    }

    /** The ping for the Paired page, also its Try again. */
    private fun pingPaired() {
        val result = pairing.result ?: return
        pairing.result = result.copy(ping = LDPairedPing.Sending)
        viewModelScope.launch {
            val ping = pingResult(result.link, result.written).fold(
                onSuccess = { LDPairedPing.Delivered },
                onFailure = { LDPairedPing.Failed(FailureReason.of(it).orEmpty()) }
            )
            pairing.result = pairing.result?.takeIf { it.link == result.link }?.copy(ping = ping)
        }
    }

    /** Sent to the destination as it is stored now, so with the headers it has, like every sync to it: none when pairing added it. */
    private suspend fun pingResult(link: PairingLink, written: Set<PairingSource>): Result<Unit> {
        val logType = if (PairingSource.HEALTH in written) LogType.HEALTH_CONNECT else LogType.SCREEN_TIME
        val destination = preferences.getDestinations(logType).firstOrNull { it.url == link.url }
            ?: Destination(id = Destinations.newId(), url = link.url, secret = link.secret)
        return TestPing.send(app, listOf(destination), logType)
    }
}

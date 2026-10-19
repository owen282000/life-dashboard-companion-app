package com.owen282000.lifedashboard.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.owen282000.lifedashboard.OnboardingSupport

/**
 * What the first-run wizard has been told so far, kept in a ViewModel so turning the phone does
 * not empty it (P2-15). Not in the saved-instance Bundle: that would put the webhook secret and
 * the MQTT password in it, and survive the process as well. Losing the answers when Android ends
 * the process is fine for a wizard that takes a minute.
 *
 * The scanned code is kept with the fields it filled in. Without it, a scanned address that
 * outlived a rotation would look typed, and finishing would write it into sections the pairing
 * dialog left out.
 */
class OnboardingForm : ViewModel() {
    var healthConnect by mutableStateOf(true)
    var screenTime by mutableStateOf(true)

    var useWebhook by mutableStateOf(false)
    var webhookUrl by mutableStateOf("")

    // Typed in, or filled in by the pairing dialog after a scan. The wizard wrote URLs
    // only until now, which left a scanned secret behind on a fresh install.
    var webhookSecret by mutableStateOf("")
    var allowHttp by mutableStateOf(false)

    var useMqtt by mutableStateOf(false)
    var mqttHost by mutableStateOf("")
    var mqttPort by mutableStateOf("1883")
    var mqttTls by mutableStateOf(false)
    var mqttUser by mutableStateOf("")
    var mqttPass by mutableStateOf("")

    var preset by mutableStateOf(OnboardingSupport.TypePreset.ESSENTIALS)
    var stepIndex by mutableStateOf(0)

    /** The last code the pairing dialog wrote while the wizard was open. */
    var paired by mutableStateOf<OnboardingSupport.WizardPairing?>(null)

    private var loaded = false

    /** Takes the settings the form starts from, once: a rotation keeps what was changed since. */
    fun loadOnce(allowHttpWebhooks: () -> Boolean) {
        if (loaded) return
        allowHttp = allowHttpWebhooks()
        loaded = true
    }
}

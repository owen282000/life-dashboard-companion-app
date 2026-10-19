package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.viewmodel.OnboardingForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's answers live in a ViewModel, which a rotation keeps (P2-15). The screen asks it
 * to load the settings on every composition; only the first may, or turning the phone would
 * put back what the user just changed.
 */
class OnboardingFormTest {

    @Test
    fun theSettingsAreLoadedOnceAndAChangeSurvivesTheNextLoad() {
        val form = OnboardingForm()
        form.loadOnce { true }
        assertTrue(form.allowHttp)

        form.allowHttp = false
        form.loadOnce { true }

        assertFalse(form.allowHttp)
    }

    @Test
    fun aScannedCodeIsKeptNextToTheAddressItFilledIn() {
        val form = OnboardingForm()
        val link = PairingLink(
            url = "https://ha.local/api/webhook/abc",
            secret = "s3cret",
            name = null,
            sources = setOf(PairingSource.HEALTH)
        )
        form.paired = OnboardingSupport.WizardPairing(link, setOf(PairingSource.HEALTH), seq = 1)
        form.webhookUrl = link.url
        form.webhookSecret = link.secret

        // Unchanged after a scan, so finishing leaves the address where the dialog wrote it.
        assertFalse(OnboardingSupport.writesWebhook(true, form.webhookUrl, form.webhookSecret, form.paired?.link))
        assertEquals(
            setOf(PairingSource.HEALTH),
            OnboardingSupport.webhookSections(true, true, form.webhookUrl, form.webhookSecret, form.paired)
        )
    }
}

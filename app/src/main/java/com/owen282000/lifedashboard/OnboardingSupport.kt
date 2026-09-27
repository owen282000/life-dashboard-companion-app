package com.owen282000.lifedashboard

/**
 * Pure definitions for the first-run wizard, kept free of Android types so the presets can be
 * unit tested on the JVM.
 *
 * A fresh install has zero data types enabled, which meant a new user's first screen was a
 * wall of 33 toggles and an empty configuration. The wizard asks two questions instead:
 * where should the data go, and which types matter to you.
 */
object OnboardingSupport {
    /** The wizard's screens, in order. TYPES only makes sense when Health Connect is chosen. */
    enum class Step { WELCOME, FEATURES, DESTINATION, TYPES, DONE }

    fun stepsFor(healthConnect: Boolean): List<Step> = buildList {
        add(Step.WELCOME)
        add(Step.FEATURES)
        add(Step.DESTINATION)
        if (healthConnect) add(Step.TYPES)
        add(Step.DONE)
    }

    /** The choices offered for the data-type step. */
    enum class TypePreset { ALL, ESSENTIALS, LATER }

    /**
     * The starter set: the types nearly every setup wants, and nothing that needs
     * explanation. Everything else is one toggle away in the Health tab.
     */
    val ESSENTIAL_TYPES: Set<HealthDataType> = setOf(
        HealthDataType.STEPS,
        HealthDataType.SLEEP,
        HealthDataType.HEART_RATE,
        HealthDataType.RESTING_HEART_RATE,
        HealthDataType.DISTANCE,
        HealthDataType.ACTIVE_CALORIES,
        HealthDataType.TOTAL_CALORIES,
        HealthDataType.WEIGHT
    )

    /** What each preset enables. */
    fun typesFor(preset: TypePreset): Set<HealthDataType> = when (preset) {
        TypePreset.ALL -> HealthDataType.entries.toSet()
        TypePreset.ESSENTIALS -> ESSENTIAL_TYPES
        TypePreset.LATER -> emptySet()
    }

    /**
     * Whether finishing the wizard writes its webhook into the sections. Not when it is
     * exactly what a scanned code already stored: pairing wrote it into the sections picked
     * in its dialog, and writing it again here would add the ones left out there.
     */
    fun writesWebhook(useWebhook: Boolean, url: String, secret: String, paired: PairingLink?): Boolean {
        if (!useWebhook || url.isBlank()) return false
        return paired == null || !isPaired(url, secret, paired)
    }

    /**
     * A code paired while the wizard is open, with the sections its dialog wrote. [seq] makes
     * pairing the same code again a new event, so the wizard takes it over a second time.
     */
    data class WizardPairing(val link: PairingLink, val written: Set<PairingSource>, val seq: Int)

    /**
     * Which sections the webhook card's address ends up in. The scanned address went only
     * where the pairing dialog wrote it, which is not always what the wizard's own choices say;
     * an address typed or edited here goes to the sources picked in the wizard.
     */
    fun webhookSections(
        healthConnect: Boolean,
        screenTime: Boolean,
        url: String,
        secret: String,
        pairing: WizardPairing?
    ): Set<PairingSource> {
        if (pairing != null && isPaired(url, secret, pairing.link)) return pairing.written
        return buildSet {
            if (healthConnect) add(PairingSource.HEALTH)
            if (screenTime) add(PairingSource.SCREEN_TIME)
        }
    }

    private fun isPaired(url: String, secret: String, paired: PairingLink): Boolean =
        url == paired.url && secret == paired.secret
}

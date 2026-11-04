package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.SettingsRules

/**
 * Pure definitions for the first-run wizard, kept free of Android types so the presets can be
 * unit tested on the JVM.
 *
 * A fresh install has zero data types enabled, which meant a new user's first screen was a
 * wall of 34 toggles and an empty configuration. The wizard asks two questions instead:
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
        if (!useWebhook || SettingsRules.urlProblem(url) != null) return false
        return paired == null || !isPaired(url, secret, paired)
    }

    /**
     * Whether the destination step can go on. A chosen destination needs what it sends to:
     * a valid webhook URL without a credential in it, a broker host and port that can be connected to, as the MQTT card
     * checks them (SettingsRules.brokerHostProblem). Choosing none is what "I will set this up
     * later" is for, so Next with nothing chosen goes on too.
     */
    fun destinationReady(useWebhook: Boolean, url: String, useMqtt: Boolean, mqttHost: String, mqttPort: String): Boolean =
        (!useWebhook || SettingsRules.urlProblem(url) == null) &&
            (!useMqtt || (MqttSupport.isValidHost(mqttHost) && MqttSupport.portOrNull(mqttPort) != null))

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

    /**
     * A section's destinations with the wizard's address. Appended, as a pairing does, so a code
     * scanned earlier in the wizard stays; on a fresh install it is the section's one destination.
     */
    fun withWebhook(
        current: List<Destination>,
        url: String,
        secret: String,
        newId: () -> String = Destinations::newId
    ): List<Destination> =
        if (current.any { it.url == url }) current else current + Destination(id = newId(), url = url, secret = secret.ifBlank { null })

    /** What the last step offers: a permission the setup needs and the phone does not have yet. */
    enum class Task { HEALTH_ACCESS, USAGE_ACCESS }

    /**
     * The real tasks left. Health access only with types to read, and not on a phone that cannot
     * have Health Connect at all, where neither Grant nor Install would help. A missing or outdated
     * Health Connect counts: installing it is the first part of this task.
     */
    fun tasksLeft(
        healthConnect: Boolean,
        screenTime: Boolean,
        preset: TypePreset,
        availability: HcAvailability,
        granted: Set<String>,
        usageAccess: Boolean
    ): List<Task> = buildList {
        val needed = HealthPermissionRequests.readPermissions(typesFor(preset))
        if (healthConnect && needed.isNotEmpty() && availability != HcAvailability.UNAVAILABLE && !granted.containsAll(needed)) {
            add(Task.HEALTH_ACCESS)
        }
        if (screenTime && !usageAccess) add(Task.USAGE_ACCESS)
    }

    private fun isPaired(url: String, secret: String, paired: PairingLink): Boolean =
        url == paired.url && secret == paired.secret
}

package com.owen282000.lifedashboard

/** What pairing would change per section, so the dialog can say it before anything happens. */
data class SectionChange(
    val addsUrl: Boolean,
    /** The paired address is there already and gets a secret, which [replacesSecret] when it had another one. */
    val setsSecret: Boolean,
    val replacesSecret: Boolean,
    /** The other addresses of the section: they keep their own secret and headers. */
    val othersKeepTheirOwn: List<String> = emptyList()
) {
    val changesNothing: Boolean get() = !addsUrl && !setsSecret
}

/** The section of the settings a pairing source writes to. */
val PairingSource.logType: LogType
    get() = when (this) {
        PairingSource.HEALTH -> LogType.HEALTH_CONNECT
        PairingSource.SCREEN_TIME -> LogType.SCREEN_TIME
    }

/** The user's answer in the confirmation dialog. */
data class PairingChoice(
    val health: Boolean,
    val screenTime: Boolean,
    val allowPlainHttp: Boolean
) {
    val nothingSelected: Boolean get() = !health && !screenTime
}

/**
 * The store pairing writes through. Narrower than PreferencesManager on purpose, so the
 * rules below can be tested without Android's SharedPreferences.
 */
interface PairingStore {
    fun destinations(source: PairingSource): List<Destination>

    /** False when nothing was stored because the secure storage is not ready yet. */
    fun setDestinations(source: PairingSource, destinations: List<Destination>): Boolean
    fun setAllowPlainHttp(enabled: Boolean)
}

/** The sections a pairing wrote, and the ones it could not, because the secure storage was not ready. */
data class PairingResult(val written: Set<PairingSource>, val refused: Set<PairingSource> = emptySet())

/**
 * Turning a scanned pairing link into settings.
 *
 * Two rules worth stating plainly, because both are visible in the dialog before the user
 * agrees to them. The address is appended, not replaced: someone feeding a second receiver
 * keeps it. The secret goes to the paired destination alone: every other destination keeps
 * its own, so pairing Home Assistant never breaks the signature check of a receiver already
 * there, and the other way round (P2-13).
 *
 * A third one shows on the Webhook card afterwards: a destination pairing adds has none of
 * the custom headers. Those were typed for the receivers already there, and a pairing link
 * can come from anyone, so an API key never follows a scanned code to its host.
 */
object PairingApply {

    /** What pairing would do to a section with [current], without doing it. */
    fun preview(link: PairingLink, current: List<Destination>): SectionChange {
        val existing = current.firstOrNull { it.url == link.url }
        return SectionChange(
            addsUrl = existing == null,
            setsSecret = existing != null && existing.secret != link.secret,
            replacesSecret = existing?.secret != null && existing.secret != link.secret,
            othersKeepTheirOwn = current.map { it.url }.filter { it != link.url }
        )
    }

    /** Which sections the dialog may offer: what the receiver accepts. */
    fun offered(link: PairingLink): Set<PairingSource> = link.sources

    /**
     * Write the link into the sections the user picked. Returns the sections written, so the
     * caller knows which ViewModels to reload and what to report, and the ones refused, which
     * keep what they had: the user pairs again in a moment.
     */
    fun apply(
        link: PairingLink,
        choice: PairingChoice,
        store: PairingStore,
        newId: () -> String = Destinations::newId
    ): PairingResult {
        val written = mutableSetOf<PairingSource>()
        val refused = mutableSetOf<PairingSource>()

        for (source in PairingSource.entries) {
            val picked = if (source == PairingSource.HEALTH) choice.health else choice.screenTime
            if (!picked || source !in link.sources) continue
            if (store.setDestinations(source, Destinations.paired(store.destinations(source), link.url, link.secret, newId))) {
                written += source
            } else {
                refused += source
            }
        }

        // Only ever turned on, and only when something was actually paired: an internal Home
        // Assistant address is http://, and without this the first sync fails with a message
        // the user did not ask for. Turning it off is left to the user's own switch.
        if (written.isNotEmpty() && link.isPlainHttp && choice.allowPlainHttp) {
            store.setAllowPlainHttp(true)
        }

        return PairingResult(written, refused)
    }
}

package com.owen282000.lifedashboard

/** What one webhook section holds today, as far as pairing cares. */
data class SectionWebhook(val urls: List<String>, val secret: String?)

/** What pairing would change per section, so the dialog can say it before anything happens. */
data class SectionChange(
    val addsUrl: Boolean,
    val replacesSecret: Boolean
) {
    val changesNothing: Boolean get() = !addsUrl && !replacesSecret
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
    fun health(): SectionWebhook
    fun screenTime(): SectionWebhook
    fun setHealth(urls: List<String>, secret: String)
    fun setScreenTime(urls: List<String>, secret: String)
    fun setAllowPlainHttp(enabled: Boolean)
}

/**
 * Turning a scanned pairing link into settings.
 *
 * Two rules worth stating plainly, because both are visible in the dialog before the user
 * agrees to them. The address is appended, not replaced: someone feeding a second receiver
 * keeps it. The secret is replaced, because a section holds exactly one and the new
 * receiver would otherwise be signed for with the old one and refused.
 */
object PairingApply {

    /** What pairing would do, without doing it. */
    fun preview(link: PairingLink, current: SectionWebhook): SectionChange =
        SectionChange(
            addsUrl = link.url !in current.urls,
            replacesSecret = current.secret != null && current.secret != link.secret
        )

    /** Which sections the dialog may offer: what the receiver accepts. */
    fun offered(link: PairingLink): Set<PairingSource> = link.sources

    /**
     * Write the link into the sections the user picked. Returns the sections written, so the
     * caller knows which ViewModels to reload and what to report.
     */
    fun apply(link: PairingLink, choice: PairingChoice, store: PairingStore): Set<PairingSource> {
        val written = mutableSetOf<PairingSource>()

        if (choice.health && PairingSource.HEALTH in link.sources) {
            val current = store.health()
            store.setHealth(withUrl(current.urls, link.url), link.secret)
            written += PairingSource.HEALTH
        }

        if (choice.screenTime && PairingSource.SCREEN_TIME in link.sources) {
            val current = store.screenTime()
            store.setScreenTime(withUrl(current.urls, link.url), link.secret)
            written += PairingSource.SCREEN_TIME
        }

        // Only ever turned on, and only when something was actually paired: an internal Home
        // Assistant address is http://, and without this the first sync fails with a message
        // the user did not ask for. Turning it off is left to the user's own switch.
        if (written.isNotEmpty() && link.isPlainHttp && choice.allowPlainHttp) {
            store.setAllowPlainHttp(true)
        }

        return written
    }

    private fun withUrl(urls: List<String>, url: String): List<String> =
        if (url in urls) urls else urls + url
}

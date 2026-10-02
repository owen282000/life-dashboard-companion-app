package com.owen282000.lifedashboard

/** What one webhook section holds today, as far as pairing cares. */
data class SectionWebhook(
    val urls: List<String>,
    val secret: String?,
    /** URLs that get none of the section's custom headers, see [WebhookSupport.headersFor]. */
    val urlsWithoutHeaders: Set<String> = emptySet()
)

/** What pairing would change per section, so the dialog can say it before anything happens. */
data class SectionChange(
    val addsUrl: Boolean,
    val replacesSecret: Boolean,
    /**
     * The other addresses in the section when the secret changes: they keep getting payloads,
     * signed with the new secret from then on, and one that checks signatures starts refusing.
     */
    val othersSignedWithNewSecret: List<String> = emptyList()
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
    fun setHealth(urls: List<String>, secret: String, urlsWithoutHeaders: Set<String>)
    fun setScreenTime(urls: List<String>, secret: String, urlsWithoutHeaders: Set<String>)
    fun setAllowPlainHttp(enabled: Boolean)
}

/**
 * Turning a scanned pairing link into settings.
 *
 * Two rules worth stating plainly, because both are visible in the dialog before the user
 * agrees to them. The address is appended, not replaced: someone feeding a second receiver
 * keeps it. The secret is replaced, because a section holds exactly one and the new
 * receiver would otherwise be signed for with the old one and refused.
 *
 * A third one shows on the Webhook card afterwards: an address pairing adds gets none of the
 * section's custom headers. Those were typed for the receivers already there, and a pairing
 * link can come from anyone, so an API key never follows a scanned code to its host.
 */
object PairingApply {

    /** What pairing would do, without doing it. */
    fun preview(link: PairingLink, current: SectionWebhook): SectionChange {
        val replacesSecret = current.secret != null && current.secret != link.secret
        return SectionChange(
            addsUrl = link.url !in current.urls,
            replacesSecret = replacesSecret,
            othersSignedWithNewSecret = if (replacesSecret) current.urls.filter { it != link.url } else emptyList()
        )
    }

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
            store.setHealth(withUrl(current.urls, link.url), link.secret, withoutHeaders(current, link.url))
            written += PairingSource.HEALTH
        }

        if (choice.screenTime && PairingSource.SCREEN_TIME in link.sources) {
            val current = store.screenTime()
            store.setScreenTime(withUrl(current.urls, link.url), link.secret, withoutHeaders(current, link.url))
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

    /** Only an address pairing adds is marked: one the user typed in already keeps its headers. */
    private fun withoutHeaders(current: SectionWebhook, url: String): Set<String> =
        if (url in current.urls) current.urlsWithoutHeaders else current.urlsWithoutHeaders + url
}

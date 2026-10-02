package com.owen282000.lifedashboard

import java.net.URI

/**
 * A delivery that reached some webhooks and not others. It counts as delivered, so nothing is
 * queued for the ones that missed it (P2-13 changes that); until then the app at least says so,
 * in the sync line and in a notification after a run of them.
 */
object PartialDelivery {

    /**
     * The hosts of [urls], once each, for a notification or a dialog. Only the host: a path or a
     * query can hold a webhook id or a token, and the text ends up on the lock screen.
     */
    fun hosts(urls: Collection<String>): String =
        urls.map { url -> runCatching { URI(url.trim()).host }.getOrNull() ?: url.substringAfter("://").substringBefore('/').substringBefore('?') }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")

    /**
     * The next value of the partial streak: up by one when [missed] is not empty, back to zero
     * when the delivery reached every URL. A delivery that reached none leaves it alone; the
     * failure streak covers that.
     */
    fun nextStreak(current: Int, delivered: Boolean, missed: Collection<String>): Int = when {
        !delivered -> current
        missed.isEmpty() -> 0
        else -> current + 1
    }
}

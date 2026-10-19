package com.owen282000.lifedashboard

/** [urls] as a section's destinations, each with [secret] and [headers], ids d0, d1 and so on. */
internal fun destinationsOf(
    urls: List<String>,
    secret: String? = null,
    headers: Map<String, String> = emptyMap()
): List<Destination> = urls.mapIndexed { i, url -> Destination(id = "d$i", url = url, secret = secret, headers = headers) }

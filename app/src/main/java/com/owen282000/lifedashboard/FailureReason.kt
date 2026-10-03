package com.owen282000.lifedashboard

/**
 * The reason a delivery failed, short enough for the end of a notification, which can show on
 * the lock screen. The Logs tab keeps the full text; this keeps what tells the user where to
 * look: "HTTP 502", "timeout", "Unable to resolve host ha.example.com".
 */
object FailureReason {

    /** Longer than this is cut at a word, so the notification does not turn into the log row. */
    const val MAX_LENGTH = 80

    private val HTTP_STATUS = Regex("""^HTTP (\d{3})\b""")
    private val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""")

    /** A wrapped exception's class name in front of its message, e.g. "javax.net.ssl.SSLHandshakeException: ". */
    private val CLASS_PREFIX = Regex("""^(?:[a-z][a-z0-9_]*\.)+[A-Za-z0-9_$]+(?:Exception|Error): """)

    /** The short reason for [error], or null when it says nothing at all. */
    fun of(error: Throwable?): String? {
        if (error == null) return null
        return shorten(error.message) ?: error.javaClass.simpleName.takeIf { it.isNotBlank() }
    }

    /**
     * A failure message, shortened. An HTTP status keeps only its code, since the reason phrase
     * after it adds nothing. A URL keeps only its host: a path or query can hold a webhook id or
     * a token, and the part before an @ a password. A header value is left out, since OkHttp
     * quotes it when it refuses one and a custom header usually carries a key. Only the first
     * sentence and line are kept, without the class name of a wrapped exception.
     */
    fun shorten(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        HTTP_STATUS.find(text)?.let { return "HTTP ${it.groupValues[1]}" }

        var short = text.lineSequence().first().replace(CLASS_PREFIX, "")
        short = URL.replace(short) { host(it.value) }
        short = short.substringBefore(" value: ")
        short = short.substringBefore(". ").trimEnd('.', ' ', ':', ';')
        if (short.length > MAX_LENGTH) {
            val cut = short.take(MAX_LENGTH)
            val atWord = cut.substringBeforeLast(' ').takeIf { it.length >= MAX_LENGTH / 2 } ?: cut
            short = atWord.trimEnd('.', ',', ' ', ':', ';') + "…"
        }
        return short.takeIf { it.isNotBlank() }
    }

    /** The host of [url] alone: no user info, port, path, query or fragment. */
    private fun host(url: String): String {
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val hostAndPort = authority.substringAfterLast('@')
        return if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']') + "]"
        } else {
            hostAndPort.substringBefore(':')
        }
    }
}

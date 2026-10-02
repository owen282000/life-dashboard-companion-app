package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The part of a payload the Logs tab and the data preview put on screen (P2-11).
 *
 * A payload kept in full can be a few hundred KB. In one `Text` Compose lays all of it out, and
 * builds accessibility and content capture semantics for all of it as soon as any accessibility
 * service runs (a password manager is enough, TalkBack is not needed), enough to run the app
 * out of memory. 12,000 characters is a few hundred lines of JSON, more than anyone reads on a
 * phone. The full payload stays where it was: in the store, the JSON log export and the data
 * export.
 *
 * [cutForDisplay] says the screen shows less than there is; [cutInStorage] says the store kept
 * only the first [WebhookLogStore.DEFAULT_PAYLOAD_LIMIT] characters, so the rest is nowhere and
 * pointing at the export would be wrong. [totalLength] counts what there is to show, without
 * the store's marker.
 */
data class PayloadPreview(
    val text: String,
    val totalLength: Int,
    val cutForDisplay: Boolean,
    val cutInStorage: Boolean
) {
    companion object {
        /** Characters shown at most, the same on iOS. */
        const val MAX_CHARS = 12_000

        /** How far before the limit a line end may be, to end the preview on a whole line. */
        private const val LINE_SLACK = 500

        private val prettyJson = Json { prettyPrint = true }

        /** The first [limit] characters of [payload], ending on a whole line when one is close. */
        fun of(payload: String, limit: Int = MAX_CHARS): PayloadPreview {
            val cutInStorage = payload.endsWith(WebhookLogStore.TRUNCATION_MARKER)
            val body = payload.removeSuffix(WebhookLogStore.TRUNCATION_MARKER)
            if (body.length <= limit) return PayloadPreview(body, body.length, false, cutInStorage)

            var end = body.lastIndexOf('\n', limit).takeIf { it >= limit - LINE_SLACK } ?: limit
            // Never end on the first half of a surrogate pair, which would show as a broken glyph.
            if (end > 0 && Character.isHighSurrogate(body[end - 1])) end--
            return PayloadPreview(body.substring(0, end), body.length, true, cutInStorage)
        }

        /**
         * [payload] pretty printed when it is JSON, as is otherwise (a payload the store cut
         * short is not). Parsing a few hundred KB takes a while, so call it off the main thread.
         */
        fun pretty(payload: String): String = try {
            prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(payload))
        } catch (e: Exception) {
            payload
        }
    }
}

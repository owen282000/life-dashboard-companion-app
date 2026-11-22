package com.owen282000.lifedashboard.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers the source URL the way the Home Assistant integration 0.7.0 does, without Home
 * Assistant: checks the request signature, takes the acks and failures out of its queue, and
 * frames an answer signed with the derived response key (life-dashboard-ha, payload.py,
 * `response_body` and `frame_answer`).
 *
 * Independent of the app on purpose. Requests are read as plain JSON with literal field names
 * (`writeback`, `types`, `ack`, `failed`, `id`, `code`), never through WriteBackReport or
 * WriteBackPayload, and the signatures come from [Hmac], which is anchored to the vectors of
 * the integration's own tests. A field renamed or a signature computed differently in the app
 * then fails here instead of being wrong in the same way on both sides.
 *
 * With [signedBody] it answers like 1.3.0 (`frame_signed_answer`): it names `signed_body` in
 * every answer, and a request with `"answer": "body"` in its `writeback` block gets the answer
 * as text inside the body with its signature next to it, the header then over the body as
 * sent. Without it the request field is ignored, as every integration before 1.3.0 does.
 */
class FakeIntegration(
    private val secret: String,
    private val version: String = "0.7.0",
    private val signedBody: Boolean = false
) {

    init {
        Hmac.check()
    }

    /** What arrived at the source URL, as the integration saw it. */
    class Seen(val body: JsonObject, val signatureValid: Boolean, val writeback: JsonObject?) {
        fun ack(): List<String> = (writeback?.get("ack") as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        fun failed(): List<Pair<String, String>> = (writeback?.get("failed") as? JsonArray).orEmpty().map {
            val entry = it.jsonObject
            (entry["id"] as JsonPrimitive).content to (entry["code"] as JsonPrimitive).content
        }
    }

    private val queue = mutableListOf<JsonObject>()
    val seen = CopyOnWriteArrayList<Seen>()

    /** The types the integration says it has a mapping for, in `configured`. */
    var configured: List<String> = listOf("weight", "blood_pressure")

    fun offer(vararg readings: JsonObject) = synchronized(queue) { queue += readings }

    fun pendingIds(): List<String> = synchronized(queue) { queue.map { it.id() } }

    /** Ways to answer wrongly, one per rejection rule of the app (protocol section 8.1). */
    enum class Mode { HONEST, NO_SIGNATURE, SIGNED_WITH_SECRET, WRONG_IN_REPLY_TO, STALE, TOO_MANY, TOO_LARGE, PROTOCOL_2, BEFORE_THE_PROTOCOL }

    @Volatile
    var mode: Mode = Mode.HONEST

    /**
     * Answers the way Home Assistant Cloud hands an answer over (`async_webhook_message` in its
     * cloud client): the status, the body and the Content-Type, and no other header.
     */
    @Volatile
    var cloud: Boolean = false

    /** When set, the integration answers with this status and no body, like a Home Assistant that is down. */
    @Volatile
    var failWith: Int? = null

    /** When set, called for every request before it is answered; a test's hook to change the world mid-sync. */
    @Volatile
    var onRequest: (() -> Unit)? = null

    /** When set, every answer says more is waiting and offers one new reading from here. */
    @Volatile
    var endless: ((round: Int) -> JsonObject)? = null

    /**
     * Offers readings of types the request did not ask for too. The real integration never
     * does; the app still has to refuse them, as it would a type revoked between the request
     * and the answer.
     */
    @Volatile
    var offerUnrequested: Boolean = false

    private var lastSignature: String? = null

    fun handle(request: RecordedRequest): MockResponse {
        val raw = request.body?.toByteArray() ?: ByteArray(0)
        val presented = request.headers["X-Signature"]
        val valid = presented != null && MessageDigest.isEqual(presented.toByteArray(), Hmac.requestSignature(secret, raw).toByteArray())
        val data = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        val block = data["writeback"] as? JsonObject
        seen += Seen(data, valid, block)
        onRequest?.invoke()
        if (!valid) return MockResponse(code = 401)
        failWith?.let { return MockResponse(code = it) }
        if (mode == Mode.BEFORE_THE_PROTOCOL) return MockResponse(code = 200)

        val types = (block?.get("types") as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
        val pending = synchronized(queue) {
            if (block != null) {
                val acked = Seen(data, true, block).ack().toSet()
                // A permanent failure takes the reading out like an ack does; rate_limited and
                // hc_unavailable leave it in for the next round (protocol section 11.3).
                val dropped = Seen(data, true, block).failed().filter { it.second !in RETRYABLE }.map { it.first }.toSet()
                queue.removeAll { it.id() in acked || it.id() in dropped }
            }
            endless?.let { queue += it(seen.size) }
            queue.filter { offerUnrequested || (it["type"] as JsonPrimitive).content in types }
        }
        val offered = when (mode) {
            Mode.TOO_MANY -> List(MAX_READINGS + 1) { i -> weight("sensor.too_many@$i", 70.0, Instant.now().minusSeconds(600L + i)) }
            else -> pending.take(MAX_READINGS)
        }
        val answer = buildJsonObject {
            putJsonObject("life_dashboard") {
                put("version", version)
                put("writeback", if (mode == Mode.PROTOCOL_2) 2 else 1)
                if (signedBody) put("signed_body", 1)
            }
            putJsonObject("writeback") {
                put("in_reply_to", if (mode == Mode.WRONG_IN_REPLY_TO) lastSignature ?: "sha256=00" else presented)
                val issued = Instant.now().truncatedTo(ChronoUnit.SECONDS).let { if (mode == Mode.STALE) it.minusSeconds(11 * 60) else it }
                put("issued_at", issued.toString())
                putJsonArray("configured") { configured.forEach { add(JsonPrimitive(it)) } }
                if (types.isNotEmpty()) {
                    put("pending", JsonArray(offered))
                    put("more", endless != null || pending.size > MAX_READINGS)
                }
                if (mode == Mode.TOO_LARGE) put("padding", "x".repeat(262_145))
            }
        }
        lastSignature = presented
        // Compact, like json.dumps(separators=(",", ":")), and signed over exactly these bytes.
        val text = answer.toString()
        fun sign(signed: ByteArray): String? = when (mode) {
            Mode.NO_SIGNATURE -> null
            Mode.SIGNED_WITH_SECRET -> Hmac.requestSignature(secret, signed)
            else -> Hmac.responseSignature(secret, signed)
        }
        val asked = signedBody && (block?.get("answer") as? JsonPrimitive)?.takeIf { it.isString }?.content == "body"
        val bytes = if (asked) {
            // The answer as text, signed as it stands; the header below signs the body around it.
            buildJsonObject {
                put("life_dashboard_signed", 1)
                put("answer", text)
                sign(text.toByteArray(Charsets.UTF_8))?.let { put("signature", it) }
            }.toString().toByteArray(Charsets.UTF_8)
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        val signature = if (cloud) null else sign(bytes)
        val headers = listOfNotNull("Content-Type" to "application/json", signature?.let { "X-Signature" to it })
        return MockResponse.Builder()
            .code(200)
            .headers(headersOf(*headers.flatMap { listOf(it.first, it.second) }.toTypedArray()))
            .body(Buffer().write(bytes))
            .build()
    }

    companion object {
        private const val MAX_READINGS = 200
        private val RETRYABLE = setOf("rate_limited", "hc_unavailable")

        private fun JsonObject.id(): String = (this["id"] as JsonPrimitive).content

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

        /** Any reading in the wire form, with its value fields as given (for the refusal cases). */
        fun reading(id: String, type: String, time: Instant, values: Map<String, Double>, version: Long = 1): JsonObject = buildJsonObject {
            put("id", id)
            put("version", version)
            put("type", type)
            values.forEach { (field, value) -> put(field, value) }
            put("time", time.truncatedTo(ChronoUnit.SECONDS).toString())
            put("recording_method", "auto")
        }

        /** A weight reading in the wire form of writeback_queue.py `to_wire`. */
        fun weight(
            id: String,
            kilograms: Double,
            time: Instant,
            version: Long = 1,
            zoneOffset: String = "+02:00",
            recordingMethod: String = "auto",
            manufacturer: String? = "Xiaomi",
            model: String? = "Mi Body Composition Scale 2"
        ): JsonObject = buildJsonObject {
            put("id", id)
            put("version", version)
            put("type", "weight")
            put("kilograms", kilograms)
            put("time", time.truncatedTo(ChronoUnit.SECONDS).toString())
            put("zone_offset", zoneOffset)
            put("recording_method", recordingMethod)
            putJsonObject("device") {
                put("type", "scale")
                manufacturer?.let { put("manufacturer", it) }
                model?.let { put("model", it) }
            }
        }

        /** A blood pressure reading in the same wire form. */
        fun bloodPressure(
            id: String,
            systolic: Double,
            diastolic: Double,
            time: Instant,
            version: Long = 1,
            zoneOffset: String = "+02:00",
            bodyPosition: String = "sitting_down",
            measurementLocation: String = "left_upper_arm",
            manufacturer: String = "Omron"
        ): JsonObject = buildJsonObject {
            put("id", id)
            put("version", version)
            put("type", "blood_pressure")
            put("systolic", systolic)
            put("diastolic", diastolic)
            put("time", time.truncatedTo(ChronoUnit.SECONDS).toString())
            put("zone_offset", zoneOffset)
            put("recording_method", "active")
            putJsonObject("device") {
                put("type", "unknown")
                put("manufacturer", manufacturer)
            }
            put("body_position", bodyPosition)
            put("measurement_location", measurementLocation)
        }

        /** Every string in [array], for asserting on lists of ids and types. */
        fun strings(array: JsonArray?): List<String> = array?.map { (it as JsonPrimitive).contentOrNull.orEmpty() }.orEmpty()
    }
}

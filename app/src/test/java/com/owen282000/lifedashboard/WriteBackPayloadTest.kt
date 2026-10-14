package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The write-back protocol (issue #62) as the app checks it: the response signature and its
 * binding to the request, the clock window, the limits, and the validation of every type.
 */
class WriteBackPayloadTest {

    private val secret = "0123456789abcdef0123456789abcdef"
    private val requestSignature = "sha256=" + "ab".repeat(32)
    private val now: Instant = Instant.parse("2026-09-27T06:35:30Z")
    private val allTypes = WriteBackType.entries.toSet()

    private fun response(
        pending: String = "[]",
        inReplyTo: String = requestSignature,
        issuedAt: String = "2026-09-27T06:35:01Z",
        protocol: String = "1",
        more: Boolean = false,
        configured: String = """["weight", "blood_pressure"]"""
    ): String = """
        {
          "life_dashboard": {"version": "0.7.0", "writeback": $protocol},
          "writeback": {
            "in_reply_to": "$inReplyTo",
            "issued_at": "$issuedAt",
            "configured": $configured,
            "pending": $pending,
            "more": $more
          }
        }
    """.trimIndent()

    private fun signed(body: String): WriteBackResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return WriteBackPayload.verify(bytes, WebhookSupport.responseSignature(bytes, secret), secret, requestSignature, now)
    }

    private fun reading(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun validate(json: String, acceptOlder: Boolean = false, granted: Set<WriteBackType> = allTypes) =
        WriteBackPayload.validate(reading(json), requested = allTypes, granted = granted, now = now, acceptOlder = acceptOlder)

    private fun refusal(outcome: ReadingOutcome): WriteBackFailure = (outcome as ReadingOutcome.Refused).failure

    private fun ready(outcome: ReadingOutcome): PendingReading = (outcome as ReadingOutcome.Ready).reading

    // Signature and binding

    @Test
    fun `the response key is derived from the secret and never the secret itself`() {
        val body = "{}".toByteArray()
        val responseSignature = WebhookSupport.responseSignature(body, secret)
        assertTrue(responseSignature.startsWith("sha256="))
        assertFalse("a request signature must not verify as a response", responseSignature == WebhookSupport.signature("{}", secret))
        assertEquals(32, WebhookSupport.responseKey(secret).size)
    }

    @Test
    fun `the response key and signature match the integration's test vector`() {
        // The same vector the Life Dashboard integration asserts in its own tests, so both
        // sides derive the response key the same way: secret "key", the fox sentence as body.
        val body = "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.UTF_8)
        assertEquals(
            "231a58ff1a4b95092f9b57457cebbb954207bafa208a00982abe19b95a0303c8",
            WebhookSupport.responseKey("key").joinToString("") { "%02x".format(it) }
        )
        assertEquals(
            "sha256=1f5e6e7bf7761bb81dcbbb34f09b6ba176523cdeba9e80d9a28dce3f34791e9b",
            WebhookSupport.responseSignature(body, "key")
        )
    }

    @Test
    fun `a well signed response bound to this request is accepted`() {
        val accepted = signed(response()) as WriteBackResponse.Accepted
        assertEquals("0.7.0", accepted.integrationVersion)
        assertEquals(listOf("weight", "blood_pressure"), accepted.configured)
        assertTrue(accepted.readings.isEmpty())
        assertFalse(accepted.more)
    }

    @Test
    fun `a missing signature on a protocol body is rejected, and an empty body is an old integration`() {
        val body = response().toByteArray()
        assertEquals(
            WriteBackResponse.Rejected(WriteBackRejection.MISSING_SIGNATURE),
            WriteBackPayload.verify(body, null, secret, requestSignature, now)
        )
        assertEquals(WriteBackResponse.Incompatible, WriteBackPayload.verify(ByteArray(0), null, secret, requestSignature, now))
        assertEquals(
            "a receiver that is not the integration is not an error either",
            WriteBackResponse.Incompatible,
            WriteBackPayload.verify("{\"ok\":true}".toByteArray(), null, secret, requestSignature, now)
        )
    }

    @Test
    fun `a signature under the wrong key or over another body is rejected before anything is read`() {
        val body = response().toByteArray()
        val underRequestKey = WebhookSupport.signature(String(body), secret)
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.BAD_SIGNATURE), WriteBackPayload.verify(body, underRequestKey, secret, requestSignature, now))
        val otherBody = WebhookSupport.responseSignature(response(more = true).toByteArray(), secret)
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.BAD_SIGNATURE), WriteBackPayload.verify(body, otherBody, secret, requestSignature, now))
    }

    @Test
    fun `more is read as a boolean and nothing else`() {
        assertTrue((signed(response(more = true)) as WriteBackResponse.Accepted).more)
        assertFalse((signed(response(more = false)) as WriteBackResponse.Accepted).more)
        val asString = response().replace("\"more\": false", "\"more\": \"true\"")
        assertFalse((signed(asString) as WriteBackResponse.Accepted).more)
    }

    @Test
    fun `a response to another request is rejected`() {
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.NOT_IN_REPLY), signed(response(inReplyTo = "sha256=" + "cd".repeat(32))))
    }

    @Test
    fun `issued_at must be within ten minutes of the phone's clock`() {
        assertTrue(signed(response(issuedAt = "2026-09-27T06:26:00Z")) is WriteBackResponse.Accepted)
        assertTrue(signed(response(issuedAt = "2026-09-27T06:44:00Z")) is WriteBackResponse.Accepted)
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.STALE), signed(response(issuedAt = "2026-09-27T06:25:00Z")))
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.STALE), signed(response(issuedAt = "2026-09-27T06:46:00Z")))
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.MALFORMED), signed(response(issuedAt = "yesterday")))
    }

    @Test
    fun `only protocol 1 is accepted and a body without the block is an old integration`() {
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.UNSUPPORTED_PROTOCOL), signed(response(protocol = "2")))
        val bytes = "{\"life_dashboard\":{\"version\":\"0.6.0\"}}".toByteArray()
        assertEquals(WriteBackResponse.Incompatible, WriteBackPayload.verify(bytes, WebhookSupport.responseSignature(bytes, secret), secret, requestSignature, now))
        val garbage = "not json".toByteArray()
        assertEquals(
            WriteBackResponse.Rejected(WriteBackRejection.MALFORMED),
            WriteBackPayload.verify(garbage, WebhookSupport.responseSignature(garbage, secret), secret, requestSignature, now)
        )
    }

    @Test
    fun `the body and the number of readings are capped`() {
        val big = ByteArray(WriteBackPayload.MAX_BODY_BYTES + 1)
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.TOO_LARGE), WriteBackPayload.verify(big, "sha256=x", secret, requestSignature, now))
        assertEquals(
            "the transport says so when it stopped reading",
            WriteBackResponse.Rejected(WriteBackRejection.TOO_LARGE),
            WriteBackPayload.verify(ByteArray(10), "sha256=x", secret, requestSignature, now, oversized = true)
        )
        val tooMany = (1..WriteBackPayload.MAX_READINGS + 1).joinToString(",", "[", "]") { "{\"id\":\"s@$it\"}" }
        assertEquals(WriteBackResponse.Rejected(WriteBackRejection.TOO_MANY), signed(response(pending = tooMany)))
        val justEnough = (1..WriteBackPayload.MAX_READINGS).joinToString(",", "[", "]") { "{\"id\":\"s@$it\"}" }
        assertEquals(WriteBackPayload.MAX_READINGS, (signed(response(pending = justEnough)) as WriteBackResponse.Accepted).readings.size)
    }

    // The request block

    @Test
    fun `the request block names the types, the history switch, the acks and the failures`() {
        val block = WriteBackPayload.requestBlock(
            types = setOf(WriteBackType.BLOOD_PRESSURE, WriteBackType.WEIGHT),
            history = true,
            report = WriteBackReport(ack = listOf("sensor.w@1"), failed = listOf(FailedReading("sensor.f@2", "permission_denied")))
        ).toString()
        assertEquals(
            """{"protocol":1,"types":["weight","blood_pressure"],"history":true,"ack":["sensor.w@1"],"failed":[{"id":"sensor.f@2","code":"permission_denied"}]}""",
            block
        )
    }

    @Test
    fun `a follow-up request is made only after a round that succeeded with more waiting`() {
        assertTrue(WriteBackPayload.followUpAfter(more = true, roundFailed = false))
        assertFalse("nothing more waiting", WriteBackPayload.followUpAfter(more = false, roundFailed = false))
        // A round that failed with a retryable code leaves the same readings pending; asking
        // again in the same sync would only repeat the failure.
        for (failure in listOf(WriteBackFailure.HC_UNAVAILABLE, WriteBackFailure.RATE_LIMITED)) {
            assertTrue(failure.retryable)
            assertFalse("no follow-up after ${failure.code}", WriteBackPayload.followUpAfter(more = true, roundFailed = true))
        }
        assertFalse(WriteBackPayload.followUpAfter(more = false, roundFailed = true))
    }

    @Test
    fun `write-back is active only with the switch, a source URL still in the section and a secret`() {
        val urls = listOf("https://ha.example/api/webhook/abc")
        assertTrue(WriteBackPayload.isActive(true, urls[0], urls, secret))
        assertFalse(WriteBackPayload.isActive(false, urls[0], urls, secret))
        assertFalse(WriteBackPayload.isActive(true, null, urls, secret))
        assertFalse("the source URL was removed from the section", WriteBackPayload.isActive(true, "https://gone/api/webhook/x", urls, secret))
        assertFalse(WriteBackPayload.isActive(true, urls[0], urls, ""))
        assertFalse(WriteBackPayload.isActive(true, urls[0], urls, null))
    }

    @Test
    fun `the source URL is the one that looks like a Home Assistant webhook`() {
        assertEquals(SourceUrlChoice.None, WriteBackPayload.sourceUrlChoice(emptyList()))
        assertEquals(SourceUrlChoice.None, WriteBackPayload.sourceUrlChoice(listOf("https://grafana.example/hook")))
        assertEquals(
            SourceUrlChoice.One("http://homeassistant.local:8123/api/webhook/abc"),
            WriteBackPayload.sourceUrlChoice(listOf("https://grafana.example/hook", "http://homeassistant.local:8123/api/webhook/abc"))
        )
        assertEquals(
            SourceUrlChoice.Several(listOf("https://a/api/webhook/1", "https://b/api/webhook/2")),
            WriteBackPayload.sourceUrlChoice(listOf("https://a/api/webhook/1", "https://b/api/webhook/2", "https://a/api/webhook/1"))
        )
    }

    // Validation per reading

    @Test
    fun `a weight reading becomes a pending reading with every field carried over`() {
        val outcome = validate(
            """
            {"id": "sensor.zejulio_weight@1758955800000", "version": 1, "type": "weight", "kilograms": 81.35,
             "time": "2026-09-27T06:30:00Z", "zone_offset": "+02:00", "recording_method": "auto",
             "device": {"type": "scale", "manufacturer": "Xiaomi", "model": "Mi Body Composition Scale 2"},
             "time_source": "state", "something_new": 42}
            """
        )
        val reading = ready(outcome)
        assertEquals("sensor.zejulio_weight@1758955800000", reading.id)
        assertEquals("sensor.zejulio_weight", reading.entityId)
        assertEquals(1L, reading.version)
        assertEquals(WriteBackType.WEIGHT, reading.type)
        assertEquals(81.35, reading.value, 0.0)
        assertNull(reading.diastolic)
        assertEquals(Instant.parse("2026-09-27T06:30:00Z"), reading.time)
        assertEquals(ZoneOffset.of("+02:00"), reading.zoneOffset)
        assertEquals(RecordingMethod.AUTO, reading.recordingMethod)
        assertEquals(ReadingDevice("scale", "Xiaomi", "Mi Body Composition Scale 2"), reading.device)
        assertEquals("state", reading.timeSource)
    }

    @Test
    fun `a blood pressure reading carries both values and its enum strings`() {
        val reading = ready(
            validate(
                """
                {"id": "sensor.omron_systolic@1758955920000", "version": 1, "type": "blood_pressure",
                 "systolic": 128.0, "diastolic": 82.0, "time": "2026-09-27T06:32:00Z", "recording_method": "active",
                 "body_position": "sitting_down", "measurement_location": "left_upper_arm",
                 "device": {"type": "unknown", "manufacturer": "Omron", "model": "M7 Intelli IT"}}
                """
            )
        )
        assertEquals(128.0, reading.value, 0.0)
        assertEquals(82.0, reading.diastolic)
        assertEquals(RecordingMethod.ACTIVE, reading.recordingMethod)
        assertEquals("sitting_down", reading.bodyPosition)
        assertEquals("left_upper_arm", reading.measurementLocation)
        assertNull("no zone offset means the phone's zone, decided later", reading.zoneOffset)
    }

    @Test
    fun `every type reads its own field and refuses the wrong one`() {
        val time = "\"time\": \"2026-09-27T06:30:00Z\""
        fun json(type: String, field: String, value: Double) = """{"id": "s@1", "version": 1, "type": "$type", "$field": $value, $time}"""

        assertEquals(1.82, ready(validate(json("height", "meters", 1.82))).value, 0.0)
        assertEquals(18.5, ready(validate(json("body_fat", "percentage", 18.5))).value, 0.0)
        assertEquals(61.5, ready(validate(json("lean_body_mass", "kilograms", 61.5))).value, 0.0)
        assertEquals(3.2, ready(validate(json("bone_mass", "kilograms", 3.2))).value, 0.0)
        assertEquals(42.0, ready(validate(json("body_water_mass", "kilograms", 42.0))).value, 0.0)
        assertEquals(WriteBackFailure.INVALID, refusal(validate(json("weight", "meters", 80.0))))
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": 1, "type": "blood_pressure", "systolic": 120, $time}""")))
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": 1, "type": "weight", "kilograms": "80", $time}""")))
    }

    @Test
    fun `the bounds refuse zero and anything outside them, inclusive of the edges`() {
        val time = "\"time\": \"2026-09-27T06:30:00Z\""
        fun json(type: String, field: String, value: Double) = """{"id": "s@1", "version": 1, "type": "$type", "$field": $value, $time}"""

        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("weight", "kilograms", 0.0))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("weight", "kilograms", 0.9))))
        assertTrue(validate(json("weight", "kilograms", 1.0)) is ReadingOutcome.Ready)
        assertTrue(validate(json("weight", "kilograms", 500.0)) is ReadingOutcome.Ready)
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("weight", "kilograms", 500.1))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("height", "meters", 0.29))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("height", "meters", 2.81))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("body_fat", "percentage", 80.5))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("lean_body_mass", "kilograms", 301.0))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("bone_mass", "kilograms", 0.05))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(json("body_water_mass", "kilograms", 0.0))))

        fun bp(systolic: Double, diastolic: Double) =
            """{"id": "s@1", "version": 1, "type": "blood_pressure", "systolic": $systolic, "diastolic": $diastolic, $time}"""
        assertTrue(validate(bp(120.0, 80.0)) is ReadingOutcome.Ready)
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(bp(301.0, 80.0))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(bp(120.0, 9.0))))
        assertEquals("diastolic must stay below systolic", WriteBackFailure.OUT_OF_RANGE, refusal(validate(bp(80.0, 80.0))))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, refusal(validate(bp(80.0, 90.0))))
        assertTrue(WriteBackPayload.inRange(WriteBackType.BLOOD_PRESSURE, 30.0, 10.0))
    }

    @Test
    fun `the time window refuses the future and, without the switch, anything older than thirty days`() {
        fun at(time: Instant) = """{"id": "s@1", "version": 1, "type": "weight", "kilograms": 80, "time": "$time"}"""
        assertTrue(validate(at(now.plus(Duration.ofMinutes(4)))) is ReadingOutcome.Ready)
        assertEquals(WriteBackFailure.INVALID, refusal(validate(at(now.plus(Duration.ofMinutes(6))))))
        assertTrue(validate(at(now.minus(Duration.ofDays(29)))) is ReadingOutcome.Ready)
        assertEquals(WriteBackFailure.TOO_OLD, refusal(validate(at(now.minus(Duration.ofDays(31))))))
        assertTrue("the switch opens the window", validate(at(now.minus(Duration.ofDays(400))), acceptOlder = true) is ReadingOutcome.Ready)
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": 1, "type": "weight", "kilograms": 80}""")))
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": 1, "type": "weight", "kilograms": 80, "time": "soon"}""")))
    }

    @Test
    fun `an unknown type, a missing permission and a bad version are refused with their own codes`() {
        val base = "\"kilograms\": 80, \"time\": \"2026-09-27T06:30:00Z\""
        assertEquals(WriteBackFailure.UNSUPPORTED_TYPE, refusal(validate("""{"id": "s@1", "version": 1, "type": "heart_rate", $base}""")))
        assertEquals(WriteBackFailure.UNSUPPORTED_TYPE, refusal(validate("""{"id": "s@1", "version": 1, $base}""")))
        assertEquals(
            WriteBackFailure.PERMISSION_DENIED,
            refusal(validate("""{"id": "s@1", "version": 1, "type": "weight", $base}""", granted = setOf(WriteBackType.HEIGHT)))
        )
        assertEquals(
            "a type the request did not ask for is refused even when its permission is held",
            WriteBackFailure.PERMISSION_DENIED,
            refusal(WriteBackPayload.validate(reading("""{"id": "s@1", "version": 1, "type": "weight", $base}"""), setOf(WriteBackType.HEIGHT), allTypes, now, false))
        )
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": 0, "type": "weight", $base}""")))
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "version": "1", "type": "weight", $base}""")))
        assertEquals(WriteBackFailure.INVALID, refusal(validate("""{"id": "s@1", "type": "weight", $base}""")))
    }

    @Test
    fun `a reading without an id cannot be reported back and is refused with no id`() {
        val outcome = validate("""{"version": 1, "type": "weight", "kilograms": 80, "time": "2026-09-27T06:30:00Z"}""") as ReadingOutcome.Refused
        assertNull(outcome.id)
        assertEquals(WriteBackFailure.INVALID, outcome.failure)
    }

    @Test
    fun `automatic, the word the app's own metadata uses, is read as auto like the integration's auto`() {
        val reading = ready(
            validate(
                """{"id": "s@1", "version": 1, "type": "weight", "kilograms": 81.35, "time": "2026-09-27T06:30:00Z",
                    "recording_method": "automatic"}"""
            )
        )
        assertEquals(RecordingMethod.AUTO, reading.recordingMethod)
        assertEquals(RecordingMethod.AUTO, RecordingMethod.fromKey("auto"))
        assertEquals(RecordingMethod.AUTO, RecordingMethod.fromKey("automatic"))
        assertEquals(RecordingMethod.ACTIVE, RecordingMethod.fromKey("active"))
        assertEquals(RecordingMethod.MANUAL, RecordingMethod.fromKey("manual"))
        assertEquals(RecordingMethod.AUTO, RecordingMethod.fromKey(null))
    }

    @Test
    fun `a refusal carries what the log needs and unknown enum strings fall back to unknown`() {
        val refused = validate("""{"id": "s@1", "version": 1, "type": "weight", "kilograms": 0, "time": "2026-09-27T06:30:00Z"}""") as ReadingOutcome.Refused
        assertEquals("s@1", refused.id)
        assertEquals("weight", refused.typeKey)
        assertEquals(Instant.parse("2026-09-27T06:30:00Z"), refused.time)

        val reading = ready(
            validate(
                """{"id": "s@1", "version": 1, "type": "blood_pressure", "systolic": 120, "diastolic": 80, "time": "2026-09-27T06:30:00Z",
                    "recording_method": "telepathy", "zone_offset": "Mars/Olympus", "device": {"type": "toaster"}}"""
            )
        )
        assertEquals(RecordingMethod.AUTO, reading.recordingMethod)
        assertNull(reading.zoneOffset)
        assertEquals("toaster", reading.device?.type)
        assertEquals("unknown", reading.bodyPosition)
    }

    @Test
    fun `Health Connect's exceptions map onto the closed set of codes`() {
        assertEquals(WriteBackFailure.PERMISSION_DENIED, WriteBackPayload.failureFor(SecurityException("no WRITE_WEIGHT")))
        assertEquals(WriteBackFailure.OUT_OF_RANGE, WriteBackPayload.failureFor(IllegalArgumentException("weight out of bounds")))
        assertEquals(WriteBackFailure.RATE_LIMITED, WriteBackPayload.failureFor(RuntimeException("Rate limited request quota has been exceeded")))
        assertEquals(WriteBackFailure.HC_UNAVAILABLE, WriteBackPayload.failureFor(RuntimeException("binder died")))
        assertEquals(WriteBackFailure.HC_UNAVAILABLE, WriteBackPayload.failureFor(RuntimeException()))
        assertTrue(WriteBackFailure.RATE_LIMITED.retryable)
        assertTrue(WriteBackFailure.HC_UNAVAILABLE.retryable)
        assertFalse(WriteBackFailure.PERMISSION_DENIED.retryable)
    }

    @Test
    fun `the writable types have their protocol keys and permissions`() {
        assertEquals(
            listOf("weight", "height", "body_fat", "lean_body_mass", "bone_mass", "body_water_mass", "blood_pressure"),
            WriteBackType.entries.map { it.key }
        )
        assertEquals(WriteBackType.BODY_FAT, WriteBackType.fromKey("body_fat"))
        assertNull(WriteBackType.fromKey("heart_rate"))
        assertEquals("android.permission.health.WRITE_BLOOD_PRESSURE", WriteBackType.BLOOD_PRESSURE.writePermission)
        assertEquals("kg", WriteBackType.BONE_MASS.unit)
        assertEquals("mmHg", WriteBackType.BLOOD_PRESSURE.unit)
    }
}

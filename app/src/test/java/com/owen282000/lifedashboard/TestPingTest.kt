package com.owen282000.lifedashboard

import io.github.optimumcode.json.schema.JsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** The test ping: what it carries, and that the published schema accepts it. */
class TestPingTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun thePingCarriesWhatEveryPayloadCarries() {
        val ping = Json.parseToJsonElement(TestPing.payload(LogType.HEALTH_CONNECT, "1.24.0", now)).jsonObject
        assertEquals(true, ping.getValue("test").jsonPrimitive.boolean)
        assertEquals(TestPing.MESSAGE, ping.getValue("message").jsonPrimitive.content)
        assertEquals("2026-10-03T12:00:00Z", ping.getValue("timestamp").jsonPrimitive.content)
        assertEquals("1.24.0", ping.getValue("app_version").jsonPrimitive.content)
        assertEquals("health_connect", ping.getValue("source").jsonPrimitive.content)
        assertEquals(setOf("test", "message", "timestamp", "app_version", "source", "writeback"), ping.keys)
    }

    @Test
    fun thePingAsksForTheSignedBodyAndNothingElse() {
        // The same bytes as the integration's vector "test-ping-that-asks" has for this block.
        for (logType in LogType.entries) {
            assertTrue(TestPing.payload(logType, "1.32.0", now).endsWith(""","writeback":{"answer":"body"}}"""))
        }
    }

    private val secret = "0123456789abcdef0123456789abcdef"
    private val requestSignature = "sha256=" + "ab".repeat(32)

    private fun answer(inReplyTo: String? = requestSignature, announce: String = "\"version\":\"1.3.0\",\"writeback\":1,\"signed_body\":1"): String {
        val reply = inReplyTo?.let { "\"in_reply_to\":\"$it\"," }.orEmpty()
        return """{"life_dashboard":{$announce},"writeback":{$reply"configured":[]}}"""
    }

    private fun signedBody(text: String, signature: String = WebhookSupport.responseSignature(text.toByteArray(), secret)): ByteArray {
        val quoted = Json.encodeToString(kotlinx.serialization.serializer<String>(), text)
        return """{"life_dashboard_signed":1,"answer":$quoted,"signature":"$signature"}""".toByteArray()
    }

    private fun ping(body: ByteArray, header: String? = null) = WriteBackPayload.verifyPing(body, header, secret, requestSignature)

    private fun withHeader(text: String) = ping(text.toByteArray(), WebhookSupport.responseSignature(text.toByteArray(), secret))

    @Test
    fun aSignedAnswerInReplyToThePingConfirmsIt() {
        assertEquals(PingAnswer.CONFIRMED, ping(signedBody(answer())))
        assertEquals(PingAnswer.CONFIRMED, withHeader(answer(announce = "\"version\":\"1.2.0\",\"writeback\":1")))
    }

    @Test
    fun thePingDoesNotLookAtTheProtocolVersionOrTheAgeOfTheAnswer() {
        // The Life Dashboard Stack answers without writeback: 1 and without issued_at.
        assertEquals(PingAnswer.CONFIRMED, withHeader(answer(announce = "\"version\":\"stack\"")))
        assertEquals(PingAnswer.CONFIRMED, withHeader("""{"writeback":{"in_reply_to":"$requestSignature"}}"""))
        val old = """{"life_dashboard":{"writeback":2},"writeback":{"in_reply_to":"$requestSignature","issued_at":"2001-01-01T00:00:00Z"}}"""
        assertEquals(PingAnswer.CONFIRMED, ping(signedBody(old)))
    }

    @Test
    fun aSignedAnswerToAnotherRequestOrToNoneDoesNotConfirm() {
        assertEquals(PingAnswer.NOT_IN_REPLY, ping(signedBody(answer(inReplyTo = "sha256=" + "cd".repeat(32)))))
        assertEquals(PingAnswer.NOT_IN_REPLY, withHeader(answer(inReplyTo = null)))
        assertEquals(PingAnswer.NOT_IN_REPLY, withHeader("not json"))
    }

    @Test
    fun aSignatureThatDoesNotMatchOrShouldBeThereDoesNotConfirm() {
        assertEquals(PingAnswer.BAD_SIGNATURE, ping(signedBody(answer(), WebhookSupport.signature(answer(), secret))))
        assertEquals(PingAnswer.BAD_SIGNATURE, ping(answer().toByteArray(), "sha256=" + "00".repeat(32)))
        // An integration that names signed_body and sent neither the signed body nor a header.
        assertEquals(PingAnswer.BAD_SIGNATURE, ping(answer().toByteArray()))
        assertEquals(PingAnswer.BAD_SIGNATURE, ping("""{"life_dashboard_signed":1,"answer":"{}"}""".toByteArray()))
        assertEquals(PingAnswer.BAD_SIGNATURE, ping("""{"life_dashboard_signed":2,"answer":"{}","signature":"sha256=00"}""".toByteArray()))
    }

    @Test
    fun anUnsignedAnswerStandsAsItAlwaysDid() {
        // A receiver that is not the integration: any body, no signature.
        assertEquals(PingAnswer.UNSIGNED, ping(ByteArray(0)))
        assertEquals(PingAnswer.UNSIGNED, ping("""{"ok":true}""".toByteArray()))
        assertEquals(PingAnswer.UNSIGNED, ping("<html>".toByteArray()))
        assertEquals(PingAnswer.UNSIGNED, ping(ByteArray(WriteBackPayload.MAX_BODY_BYTES + 1)))
        assertEquals(PingAnswer.UNSIGNED, WriteBackPayload.verifyPing(ByteArray(0), null, secret, requestSignature, oversized = true))
        // An integration before 1.3.0 behind Home Assistant Cloud: it pairs, and Receive asks for the update.
        assertEquals(PingAnswer.SIGNATURE_LOST, ping(answer(announce = "\"version\":\"1.2.0\",\"writeback\":1").toByteArray()))
    }

    @Test
    fun thePingAgreesWithTheSharedVectors() {
        val cases = Json.parseToJsonElement(
            TestPingTest::class.java.classLoader!!.getResourceAsStream("protocol/answers.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        ).jsonObject.getValue("cases") as kotlinx.serialization.json.JsonArray
        for (element in cases) {
            val case = element.jsonObject
            val response = case.getValue("response").jsonObject
            val expect = case.getValue("expect").jsonObject
            val result = WriteBackPayload.verifyPing(
                body = response.getValue("body").jsonPrimitive.content.toByteArray(),
                signatureHeader = response.getValue("headers").jsonObject["X-Signature"]?.jsonPrimitive?.content,
                secret = case.getValue("secret").jsonPrimitive.content,
                requestSignature = case.getValue("request_signature").jsonPrimitive.content
            )
            val reason = expect["reason"]?.jsonPrimitive?.content
            val expected = when {
                // Too old for Receive, and still a signed answer to this ping.
                expect.getValue("accept").jsonPrimitive.boolean || reason == "issued_at" -> PingAnswer.CONFIRMED
                reason == "in_reply_to" -> PingAnswer.NOT_IN_REPLY
                reason == "unsigned" && expect.getValue("update_integration").jsonPrimitive.boolean -> PingAnswer.SIGNATURE_LOST
                else -> PingAnswer.BAD_SIGNATURE
            }
            assertEquals(case.getValue("name").jsonPrimitive.content, expected, result)
        }
    }

    @Test
    fun onlyAnAnswerThatDoesNotCheckOutFailsThePing() {
        assertTrue(TestPing.answerProblem(PingAnswer.BAD_SIGNATURE)!!.contains("signature"))
        assertTrue(TestPing.answerProblem(PingAnswer.NOT_IN_REPLY)!!.contains("another request"))
        for (stands in listOf(PingAnswer.CONFIRMED, PingAnswer.UNSIGNED, PingAnswer.SIGNATURE_LOST)) {
            assertEquals(stands.name, null, TestPing.answerProblem(stands))
        }
    }

    @Test
    fun thePingNamesTheSectionItTests() {
        assertEquals("health_connect", TestPing.source(LogType.HEALTH_CONNECT))
        assertEquals("screen_time", TestPing.source(LogType.SCREEN_TIME))
    }

    @Test
    fun thePublishedSchemaAcceptsThePing() {
        val schema = JsonSchema.fromDefinition(File("../docs/webhook-schema.json").readText())
        for (logType in LogType.entries) {
            val errors = mutableListOf<String>()
            schema.validate(Json.parseToJsonElement(TestPing.payload(logType, "1.24.0", now))) { errors += "${it.objectPath}: ${it.message}" }
            assertTrue("$logType: $errors", errors.isEmpty())
        }
    }

    @Test
    fun theSchemaRefusesAPingWithoutAppVersion() {
        // What the ping sent before: the schema requires app_version, so it was refused.
        val schema = JsonSchema.fromDefinition(File("../docs/webhook-schema.json").readText())
        val old = """{"test":true,"message":"${TestPing.MESSAGE}","timestamp":"2026-10-03T12:00:00Z","source":"health_connect"}"""
        val errors = mutableListOf<String>()
        schema.validate(Json.parseToJsonElement(old)) { errors += it.message }
        assertTrue(errors.isNotEmpty())
    }
}

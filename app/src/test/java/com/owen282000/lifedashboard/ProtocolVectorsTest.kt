package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The shared protocol vectors of the Life Dashboard integration (life-dashboard-ha,
 * protocol/vectors), copied unchanged into src/test/resources/protocol: the same bytes and the
 * same signatures the integration's own tests and the iOS app are held to. Every body in them
 * is a JSON string whose UTF-8 encoding is what is on the wire, so nothing here parses and
 * writes a body again before it is signed or checked.
 */
class ProtocolVectorsTest {

    private fun vectors(name: String): JsonObject =
        ProtocolVectorsTest::class.java.classLoader!!.getResourceAsStream("protocol/$name")!!.use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.cases(): List<JsonObject> = getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `the copies are the version this test was written for`() {
        // A copy that fell behind the integration's is noticed here when it is refreshed.
        assertEquals(VERSION, vectors("answers.json").getValue("version").jsonPrimitive.int)
        assertEquals(VERSION, vectors("exchanges.json").getValue("version").jsonPrimitive.int)
    }

    @Test
    fun `the request signature of every exchange is the app's own`() {
        val cases = vectors("exchanges.json").cases().filter { it.getValue("expect").jsonObject.getValue("status").jsonPrimitive.int != 401 }
        assertTrue(cases.size >= 8)
        for (case in cases) {
            val request = case.getValue("request").jsonObject
            assertEquals(case.text("name"), request.text("signature"), WebhookSupport.signature(request.text("body"), case.text("secret")))
        }
    }

    @Test
    fun `the answer of every exchange carries the signatures the app computes`() {
        val exchanges = vectors("exchanges.json")
        assertEquals(WebhookSupport.RESPONSE_KEY_LABEL, exchanges.text("response_key_label"))
        for (case in exchanges.cases()) {
            val expect = case.getValue("expect").jsonObject
            if (expect.getValue("status").jsonPrimitive.int != 200) continue
            val secret = case.text("secret")
            val name = case.text("name")
            assertEquals(name, expect.text("answer_signature"), WebhookSupport.responseSignature(expect.text("answer").toByteArray(), secret))
            assertEquals(name, expect.text("x_signature"), WebhookSupport.responseSignature(expect.text("body").toByteArray(), secret))
        }
    }

    @Test
    fun `the answer to a request that asks is the signed body, and the app takes it without the header`() {
        // What the integration answers, handed over the way Home Assistant Cloud does: the body alone.
        val asking = vectors("exchanges.json").cases().filter {
            it.getValue("expect").jsonObject["signed_body"]?.jsonPrimitive?.boolean == true
        }
        assertTrue(asking.size >= 4)
        val now = Instant.parse(vectors("exchanges.json").getValue("integration").jsonObject.text("now"))
        for (case in asking) {
            val expect = case.getValue("expect").jsonObject
            val verified = WriteBackPayload.verify(
                body = expect.text("body").toByteArray(),
                signatureHeader = null,
                secret = case.text("secret"),
                requestSignature = case.getValue("request").jsonObject.text("signature"),
                now = now
            )
            assertTrue("${case.text("name")}: $verified", verified is WriteBackResponse.Accepted)
            assertEquals("1.3.0", (verified as WriteBackResponse.Accepted).integrationVersion)
        }
    }

    @Test
    fun `the answer key is the one the vectors name`() {
        val key = vectors("answers.json").getValue("response_key").jsonObject
        assertEquals(key.text("hex"), WebhookSupport.responseKey(key.text("secret")).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `every answer that reaches the phone is taken or refused as the vectors say`() {
        val cases = vectors("answers.json").cases()
        assertTrue(cases.size >= 13)
        val seen = mutableSetOf<String>()
        for (case in cases) {
            val name = case.text("name")
            val response = case.getValue("response").jsonObject
            assertEquals(name, 200, response.getValue("status").jsonPrimitive.int)
            val headers = response.getValue("headers").jsonObject
            val verified = WriteBackPayload.verify(
                body = response.text("body").toByteArray(Charsets.UTF_8),
                signatureHeader = headers["X-Signature"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                secret = case.text("secret"),
                requestSignature = case.text("request_signature"),
                now = Instant.parse(case.text("now"))
            )
            val expect = case.getValue("expect").jsonObject
            if (expect.getValue("accept").jsonPrimitive.boolean) {
                assertTrue("$name: $verified", verified is WriteBackResponse.Accepted)
                // What was taken is the answer the vector names: the readings are the ones in that text.
                val answer = Json.parseToJsonElement(expect.text("answer")).jsonObject
                val pending = answer.getValue("writeback").jsonObject["pending"]?.jsonArray.orEmpty().map { it.jsonObject }
                assertEquals(name, pending, (verified as WriteBackResponse.Accepted).readings)
                assertEquals(name, answer.getValue("life_dashboard").jsonObject.text("version"), verified.integrationVersion)
                seen += "accept"
            } else {
                val reason = expect.text("reason")
                val update = expect.getValue("update_integration").jsonPrimitive.boolean
                val expected = when {
                    reason == "unsigned" && update -> WriteBackResponse.SignatureLost
                    reason == "unsigned" -> WriteBackResponse.Rejected(WriteBackRejection.MISSING_SIGNATURE)
                    reason == "signature" -> WriteBackResponse.Rejected(WriteBackRejection.BAD_SIGNATURE)
                    reason == "protocol" -> WriteBackResponse.Rejected(WriteBackRejection.UNSUPPORTED_PROTOCOL)
                    reason == "in_reply_to" -> WriteBackResponse.Rejected(WriteBackRejection.NOT_IN_REPLY)
                    reason == "issued_at" -> WriteBackResponse.Rejected(WriteBackRejection.STALE)
                    else -> throw AssertionError("$name: a reason this test does not know: $reason")
                }
                assertEquals(name, expected, verified)
                seen += if (update) "update" else reason
            }
        }
        assertEquals(
            "every outcome of version 1 is exercised",
            setOf("accept", "update", "unsigned", "signature", "protocol", "in_reply_to", "issued_at"),
            seen
        )
    }

    @Test
    fun `a reading with escapes in its text comes through as the integration wrote it`() {
        val case = vectors("answers.json").cases().single { it.text("name") == "signed-body-with-a-reading" }
        val verified = WriteBackPayload.verify(
            body = case.getValue("response").jsonObject.text("body").toByteArray(),
            signatureHeader = null,
            secret = case.text("secret"),
            requestSignature = case.text("request_signature"),
            now = Instant.parse(case.text("now"))
        ) as WriteBackResponse.Accepted
        val device = verified.readings.single().getValue("device").jsonObject
        assertEquals("Société Balance", device.text("manufacturer"))
        assertEquals("Modèle \"S\" 2", device.text("model"))
    }

    private companion object {
        /** The `version` of protocol/vectors these copies were taken at. */
        const val VERSION = 1
    }
}

package com.owen282000.lifedashboard

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * OkHttp never follows a redirect itself. A delivery follows one on the same host, with the
 * same POST, body, signature and headers; a redirect to another host is not followed, and the
 * delivery fails with a message that names it. Runs the client WebhookManager builds (without
 * a certificate) against two local servers, which OkHttp sees as two hosts: same address,
 * another port.
 */
class WebhookRedirectTest {

    private val receiver = MockWebServer()
    private val elsewhere = MockWebServer()

    @Before
    fun start() {
        receiver.start(InetAddress.getByName("127.0.0.1"), 0)
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        receiver.close()
        elsewhere.close()
    }

    private fun post(): okhttp3.Response {
        val request = Request.Builder()
            .url(receiver.url("/hook"))
            .post("""{"test":true}""".toRequestBody())
            .header("Authorization", "Bearer token")
            .header(WebhookSupport.SIGNATURE_HEADER, "sha256=abc")
            .build()
        return WebhookManager.baseClientBuilder().build().newCall(request).execute()
    }

    @Test
    fun aTemporaryRedirectToAnotherHostIsNotFollowed() {
        // Another origin: the same address on another port, as far as OkHttp is concerned.
        val target = elsewhere.url("/stolen").toString()
        receiver.enqueue(MockResponse.Builder().code(307).addHeader("Location", target).build())

        post().use { response ->
            assertEquals(307, response.code)
            assertFalse(response.isSuccessful)
            val message = WebhookManager.failureMessage(response)
            assertTrue(message, message.startsWith("HTTP 307"))
            assertTrue(message, message.contains("to ${elsewhere.hostName} "))
        }
        assertEquals(1, receiver.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun aPermanentRedirectIsNotFollowedEither() {
        receiver.enqueue(MockResponse.Builder().code(308).addHeader("Location", "/moved").build())

        post().use { response ->
            assertEquals(308, response.code)
            // A relative Location resolves against the configured URL.
            val message = WebhookManager.failureMessage(response)
            assertTrue(message, message.contains("to ${receiver.hostName} "))
        }
        assertEquals(1, receiver.requestCount)
        // Not retried and not a refusal of the payload: the outbox waits for a corrected address.
        assertFalse(WebhookSupport.isRetryable(308))
        assertFalse(WebhookSupport.refusesPayload(308))
    }

    @Test
    fun anOrdinaryFailureKeepsItsStatusLine() {
        receiver.enqueue(MockResponse.Builder().code(401).build())

        post().use { response ->
            assertEquals("HTTP 401: Client Error", WebhookManager.failureMessage(response))
        }
    }

    /** The manager as a sync builds it, but allowed to reach the local http:// servers. */
    private fun manager(urls: List<String>, urlsWithoutHeaders: Set<String> = emptySet()) = WebhookManager(
        destinations = Destinations.derive(LogType.HEALTH_CONNECT, urls, "s3cret", mapOf("X-Api-Key" to "secret-key"), urlsWithoutHeaders),
        allowHttpOverride = true
    )

    @Test
    fun postDataSendsNoCustomHeaderToAPairedUrl() = runTest {
        val typed = receiver.url("/typed").toString()
        val paired = elsewhere.url("/paired").toString()
        receiver.enqueue(MockResponse.Builder().code(200).build())
        elsewhere.enqueue(MockResponse.Builder().code(200).build())

        // Migrated from 1.27.0, where pairing marked the address instead of giving it headers of its own.
        assertTrue(manager(listOf(typed, paired), urlsWithoutHeaders = setOf(paired)).postData("{}").isSuccess)

        assertEquals("secret-key", receiver.takeRequest().headers["X-Api-Key"])
        val pairedRequest = elsewhere.takeRequest()
        assertNull(pairedRequest.headers["X-Api-Key"])
        // The signature is the receiver's own business and still goes along.
        assertTrue(pairedRequest.headers[WebhookSupport.SIGNATURE_HEADER] != null)
    }

    @Test
    fun aHeaderThatCannotBeSentFailsItsUrlWithTheHeaderNamedAndSendsNothingThere() = runTest {
        val typed = receiver.url("/typed").toString()
        val paired = elsewhere.url("/paired").toString()
        elsewhere.enqueue(MockResponse.Builder().code(200).build())
        val manager = WebhookManager(
            destinations = listOf(
                Destination(id = "typed", url = typed, headers = mapOf("Bad Header" to "secret-key")),
                Destination(id = "paired", url = paired)
            ),
            allowHttpOverride = true
        )

        // The paired URL has no headers, so it still takes the payload.
        val outcome = manager.postData("{}")
        assertTrue(outcome.isSuccess)
        assertEquals(listOf(typed), outcome.getOrNull()?.missedUrls)
        assertEquals(0, receiver.requestCount)
        assertEquals(1, elsewhere.requestCount)

        val failure = WebhookManager(
            destinations = destinationsOf(listOf(typed), headers = mapOf("Bad Header" to "secret-key")),
            allowHttpOverride = true
        ).postData("{}").exceptionOrNull()
        assertTrue(failure is InvalidHeaderException)
        assertEquals("Invalid header name \"Bad Header\", so nothing was sent", FailureReason.of(failure))
        assertEquals(0, receiver.requestCount)
    }

    @Test
    fun postDataFailsOnARedirectWithoutFollowingOrRetryingIt() = runTest {
        receiver.enqueue(
            MockResponse.Builder().code(307).addHeader("Location", elsewhere.url("/stolen").toString()).build()
        )

        val result = manager(listOf(receiver.url("/hook").toString())).postData("{}")

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull()
        assertFalse(failure is PayloadRefusedException)
        assertTrue(failure?.message, failure?.message?.contains("to ${elsewhere.hostName} ") == true)
        assertEquals(1, receiver.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun postDataFollowsAPermanentRedirectOnTheSameHostWithTheSamePost() = runTest {
        receiver.enqueue(MockResponse.Builder().code(308).addHeader("Location", "/moved").build())
        receiver.enqueue(MockResponse.Builder().code(200).build())

        assertTrue(manager(listOf(receiver.url("/hook").toString())).postData("""{"a":1}""").isSuccess)

        val first = receiver.takeRequest()
        val second = receiver.takeRequest()
        assertEquals("/moved", second.url.encodedPath)
        assertEquals("POST", second.method)
        assertEquals("""{"a":1}""", second.body?.utf8())
        assertEquals(first.headers[WebhookSupport.SIGNATURE_HEADER], second.headers[WebhookSupport.SIGNATURE_HEADER])
        assertEquals("secret-key", second.headers["X-Api-Key"])
    }

    @Test
    fun postDataKeepsThePostOnAMovedPermanently() = runTest {
        // OkHttp would turn this into a GET without a body and report the 200 as a success.
        receiver.enqueue(MockResponse.Builder().code(301).addHeader("Location", receiver.url("/hook/").toString()).build())
        receiver.enqueue(MockResponse.Builder().code(200).build())

        assertTrue(manager(listOf(receiver.url("/hook").toString())).postData("{}").isSuccess)

        receiver.takeRequest()
        val second = receiver.takeRequest()
        assertEquals("POST", second.method)
        assertEquals("{}", second.body?.utf8())
    }

    /** The manager with the two local servers standing in for Apps Script and its answer host. */
    private fun appsScriptManager(url: String) = WebhookManager(
        destinations = destinationsOf(listOf(url), secret = "s3cret", headers = mapOf("X-Api-Key" to "secret-key")),
        allowHttpOverride = true,
        appsScriptRule = WebhookSupport.AppsScriptRule(
            scheme = "http",
            scriptHost = "${receiver.hostName}:${receiver.port}",
            answerHost = "${elsewhere.hostName}:${elsewhere.port}"
        )
    )

    @Test
    fun anAppsScriptDeliveryReadsTheAnswerWithAPlainGet() = runTest {
        val exec = receiver.url("/macros/s/abc/exec").toString()
        receiver.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/macros/echo?user_content_key=k").toString()).build()
        )
        elsewhere.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        val result = appsScriptManager(exec).postData("""{"a":1}""")

        assertTrue(result.isSuccess)
        val post = receiver.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("""{"a":1}""", post.body?.utf8())
        // Only the URL the user entered gets the payload, its signature and its headers.
        val answer = elsewhere.takeRequest()
        assertEquals("GET", answer.method)
        assertEquals("/macros/echo?user_content_key=k", answer.target)
        assertEquals(0L, answer.bodySize)
        assertNull(answer.headers[WebhookSupport.SIGNATURE_HEADER])
        assertNull(answer.headers["X-Api-Key"])
        assertEquals(1, receiver.requestCount)
    }

    @Test
    fun anAppsScriptAnswerThatFailsDoesNotRepeatThePost() = runTest {
        val exec = receiver.url("/macros/s/abc/exec").toString()
        receiver.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/macros/echo?k=1").toString()).build()
        )
        elsewhere.enqueue(MockResponse.Builder().code(500).build())

        // The script ran on the POST; running it again would write its rows twice.
        assertTrue(appsScriptManager(exec).postData("{}").isSuccess)
        assertEquals(1, receiver.requestCount)
        assertEquals(1, elsewhere.requestCount)
    }

    @Test
    fun anAppsScriptRedirectThatKeepsThePostIsNotFollowed() = runTest {
        val exec = receiver.url("/macros/s/abc/exec").toString()
        receiver.enqueue(
            MockResponse.Builder().code(307).addHeader("Location", elsewhere.url("/macros/echo?k=1").toString()).build()
        )

        assertTrue(appsScriptManager(exec).postData("{}").isFailure)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun aRedirectToTheAnswerHostFromAnotherPathIsNotFollowed() = runTest {
        val notExec = receiver.url("/hook").toString()
        receiver.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", elsewhere.url("/macros/echo?k=1").toString()).build()
        )

        assertTrue(appsScriptManager(notExec).postData("{}").isFailure)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun postDataStopsFollowingAfterTheLimit() = runTest {
        repeat(WebhookSupport.MAX_REDIRECTS + 1) {
            receiver.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/loop").build())
        }

        val result = manager(listOf(receiver.url("/hook").toString())).postData("{}")

        assertTrue(result.isFailure)
        assertEquals(WebhookSupport.MAX_REDIRECTS + 1, receiver.requestCount)
    }
}

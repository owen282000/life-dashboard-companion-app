package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The test ping: one small signed POST that proves an address and a secret work, before any
 * data goes there. Sent from the tabs, from the wizard, and right after a pairing.
 *
 * It carries what every payload carries (`timestamp`, `app_version`, `source`, so it passes
 * docs/webhook-schema.json) plus `test: true`, which tells a receiver there is no data in it.
 * The iOS app sends the same shape.
 *
 * It also asks for the integration's answer in the signed body (`writeback.answer`), like every
 * request of Receive, and checks that answer when the destination has a secret: see [answerProblem].
 */
object TestPing {

    const val MESSAGE = "Test ping from Life Dashboard"

    /** The source a ping names for [logType]: the section it tests, like a sync of it would. */
    fun source(logType: LogType): String = when (logType) {
        LogType.HEALTH_CONNECT -> "health_connect"
        LogType.SCREEN_TIME -> "screen_time"
    }

    fun payload(logType: LogType, appVersion: String, now: Instant = Instant.now()): String =
        buildJsonObject {
            put("test", true)
            put("message", MESSAGE)
            put("timestamp", now.toString())
            put("app_version", appVersion)
            put("source", source(logType))
            put("writeback", WriteBackPayload.pingBlock())
        }.toString()

    /**
     * What is wrong with the answer to a ping, or null when the ping stands. It fails on an
     * answer that is signed and does not check out, or that should be signed and is not (an
     * integration that names `signed_body` and sent neither form): then the address, or
     * something on the way to it, is not the integration that holds this secret. A receiver
     * that is not the integration stands as before, and so does an older integration whose
     * signature was lost on the way, which Receive has its own message for.
     */
    fun answerProblem(answer: PingAnswer): String? = when (answer) {
        PingAnswer.BAD_SIGNATURE -> "The receiver took the ping, but its answer's signature is missing or wrong"
        PingAnswer.NOT_IN_REPLY -> "The receiver took the ping, but its answer is to another request"
        PingAnswer.CONFIRMED, PingAnswer.UNSIGNED, PingAnswer.SIGNATURE_LOST -> null
    }

    /**
     * Checks the answer of [destination] and keeps what it says about Receive: an integration
     * before 1.3.0 whose signature was lost on the way (Home Assistant Cloud) is remembered for
     * the Receive page, and a signed answer lifts that again.
     */
    private fun checkAnswer(context: Context, destination: Destination, response: SourceResponse): String? {
        val secret = destination.secret?.takeIf { it.isNotBlank() } ?: return null
        val answer = WriteBackPayload.verifyPing(
            body = response.body,
            signatureHeader = response.signature,
            secret = secret,
            requestSignature = response.requestSignature ?: "",
            oversized = response.oversized
        )
        when (answer) {
            PingAnswer.SIGNATURE_LOST -> context.appPreferences().setReceiveSignatureLost(destination.url, true)
            PingAnswer.CONFIRMED -> context.appPreferences().setReceiveSignatureLost(destination.url, false)
            else -> Unit
        }
        return answerProblem(answer)
    }

    /** The installed version, as the sync payloads name it. */
    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "1.0"
    }

    /**
     * Sends one ping to [destinations], each signed with its own secret and with its own
     * headers, and logged in the Logs tab like any delivery. The failure carries the reason the
     * user sees. Never queued: a ping that did not arrive has nothing to keep.
     */
    suspend fun send(context: Context, destinations: List<Destination>, logType: LogType): Result<Unit> = try {
        WebhookManager(
            destinations = destinations,
            context = context,
            dataType = "test",
            recordCount = 0,
            logType = logType,
            answerCheck = { destination, response -> checkAnswer(context, destination, response) }
        ).postData(payload(logType, appVersion(context))).map { }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** One ping to [url], signed with [secret] and without headers: the wizard's check before anything is stored. */
    suspend fun send(context: Context, url: String, secret: String?, logType: LogType): Result<Unit> =
        send(context, listOf(Destination(id = Destinations.newId(), url = url.trim(), secret = secret?.trim()?.ifBlank { null })), logType)
}

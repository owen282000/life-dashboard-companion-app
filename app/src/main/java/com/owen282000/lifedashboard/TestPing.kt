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
 */
object TestPing {

    const val MESSAGE = "Test ping from Life Dashboard Companion"

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
        }.toString()

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
            logType = logType
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

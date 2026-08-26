package com.owen282000.lifedashboard

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class WebhookManager(
    private val webhookUrls: List<String>,
    private val context: Context? = null,
    private val dataType: String? = null,
    private val recordCount: Int? = null,
    private val logType: LogType = LogType.HEALTH_CONNECT,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val signingSecret: String? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Posts the payload to EVERY configured webhook. The sync counts as delivered when at
     * least one webhook accepted it; per-URL outcomes are visible in the webhook logs.
     */
    suspend fun postData(jsonPayload: String): Result<Unit> {
        if (webhookUrls.isEmpty()) {
            return Result.failure(IllegalStateException("No webhook URLs configured"))
        }

        var anySuccess = false
        var lastFailure: Exception? = null

        for (url in webhookUrls) {
            val result = postToUrl(url, jsonPayload)
            if (result.isSuccess) {
                anySuccess = true
            } else {
                lastFailure = result.exceptionOrNull() as? Exception ?: Exception("Unknown error")
            }
        }

        return if (anySuccess) {
            Result.success(Unit)
        } else {
            Result.failure(lastFailure ?: IOException("All webhook posts failed"))
        }
    }

    private suspend fun postToUrl(url: String, jsonPayload: String): Result<Unit> {
        val timestamp = System.currentTimeMillis()

        return try {
            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
            customHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }
            if (!signingSecret.isNullOrBlank()) {
                requestBuilder.header(
                    WebhookSupport.SIGNATURE_HEADER,
                    WebhookSupport.signature(jsonPayload, signingSecret)
                )
            }
            val request = requestBuilder.build()

            var lastException: Exception? = null
            var statusCode: Int? = null
            var errorMessage: String? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    client.newCall(request).execute().use { response ->
                        statusCode = response.code
                        if (response.isSuccessful) {
                            val note = if (attempt > 1) "Recovered on attempt $attempt of $MAX_RETRIES" else null
                            logWebhookCall(url, timestamp, statusCode, true, null, jsonPayload, note)
                            return Result.success(Unit)
                        }
                        lastException = IOException("HTTP ${response.code}: ${response.message}")
                        errorMessage = "HTTP ${response.code}: ${response.message}"
                    }
                    // Client errors (401, 404, ...) will not change on retry; fail fast so the
                    // sync is not delayed by pointless backoff.
                    if (!WebhookSupport.isRetryable(statusCode)) {
                        logWebhookCall(
                            url, timestamp, statusCode, false,
                            "$errorMessage (permanent error, not retried)", jsonPayload
                        )
                        return Result.failure(lastException ?: IOException("Webhook post failed"))
                    }
                } catch (e: IOException) {
                    lastException = e
                    statusCode = null
                    errorMessage = e.message ?: e.javaClass.simpleName
                }

                if (attempt < MAX_RETRIES) {
                    // Exponential backoff between transient failures
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt - 1).toLong())
                    kotlinx.coroutines.delay(delayMs)
                }
            }

            logWebhookCall(
                url, timestamp, statusCode, false,
                "Failed after $MAX_RETRIES attempts (transient errors): $errorMessage", jsonPayload
            )
            Result.failure(lastException ?: IOException("Max retries exceeded"))
        } catch (e: Exception) {
            logWebhookCall(url, timestamp, null, false, e.message, jsonPayload)
            Result.failure(e)
        }
    }

    private fun logWebhookCall(
        url: String,
        timestamp: Long,
        statusCode: Int?,
        success: Boolean,
        errorMessage: String?,
        rawPayload: String?,
        note: String? = null
    ) {
        context?.let {
            if (success) {
                LifetimeStats.recordDelivery(it, recordCount ?: 0, rawPayload?.length ?: 0)
            }
            val preferencesManager = PreferencesManager(it)
            val log = WebhookLog(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                url = url,
                statusCode = statusCode,
                success = success,
                errorMessage = errorMessage,
                dataType = dataType,
                recordCount = recordCount,
                rawPayload = rawPayload,
                logType = logType.name,
                note = note
            )
            preferencesManager.addWebhookLog(log)
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }
}

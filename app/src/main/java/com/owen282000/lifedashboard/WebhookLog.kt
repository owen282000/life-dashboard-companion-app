package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable

enum class LogType {
    HEALTH_CONNECT,
    SCREEN_TIME
}

@Serializable
data class WebhookLog(
    val id: String,
    val timestamp: Long,
    val url: String,
    val statusCode: Int?,
    val success: Boolean,
    val errorMessage: String?,
    val dataType: String?,
    val recordCount: Int?,
    val rawPayload: String? = null,
    val logType: String = LogType.HEALTH_CONNECT.name // "HEALTH_CONNECT" or "SCREEN_TIME"
)

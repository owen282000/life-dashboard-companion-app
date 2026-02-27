package com.owen282000.lifedashboard

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ExportManager(private val context: Context) {

    private val prettyJson = Json { prettyPrint = true }

    fun exportAsJson(logs: List<WebhookLog>): String {
        val payloads = logs.mapNotNull { log ->
            log.rawPayload?.let { payload ->
                try {
                    Json.parseToJsonElement(payload)
                } catch (e: Exception) {
                    null
                }
            }
        }

        val jsonArray = buildJsonArray {
            payloads.forEach { add(it) }
        }

        return prettyJson.encodeToString(JsonElement.serializer(), jsonArray)
    }

    fun exportAsCsv(logs: List<WebhookLog>): String {
        val sb = StringBuilder()
        sb.appendLine("timestamp,log_type,url,status_code,success,data_type,record_count,error_message")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        logs.forEach { log ->
            val timestamp = formatter.format(Instant.ofEpochMilli(log.timestamp))
            val logType = when (log.logType) {
                LogType.HEALTH_CONNECT.name -> "health_connect"
                LogType.SCREEN_TIME.name -> "screen_time"
                else -> "unknown"
            }
            sb.appendLine(
                "${csvEscape(timestamp)}," +
                "${csvEscape(logType)}," +
                "${csvEscape(log.url)}," +
                "${log.statusCode ?: ""}," +
                "${log.success}," +
                "${csvEscape(log.dataType ?: "")}," +
                "${log.recordCount ?: ""}," +
                csvEscape(log.errorMessage ?: "")
            )
        }

        return sb.toString()
    }

    fun shareFile(content: String, filename: String, mimeType: String) {
        val cacheDir = File(context.cacheDir, "exports")
        cacheDir.mkdirs()

        val file = File(cacheDir, filename)
        file.writeText(content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, "Export logs")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

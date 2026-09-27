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
        sb.appendLine("timestamp,log_type,direction,url,status_code,success,data_type,record_count,error_message")

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
                "${csvEscape(log.direction.lowercase())}," +
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

    fun shareFile(content: String, filename: String, mimeType: String, title: String = "Export logs") {
        val cacheDir = exportsDir(context)
        cacheDir.mkdirs()
        // One export at a time: the previous one has been handed to its share target already,
        // and left alone it would sit in the cache with health data until Android clears it.
        deleteExportsOlderThan(cacheDir, cutoffMillis = Long.MAX_VALUE)

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

        val chooserIntent = Intent.createChooser(shareIntent, title)
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    }

    companion object {
        /** How long an export may outlive its share, for a target that reads it late. */
        private const val MAX_EXPORT_AGE_MILLIS = 24 * 60 * 60 * 1000L

        private fun exportsDir(context: Context) = File(context.cacheDir, "exports")

        /**
         * Removes exports older than a day; called at app start, so an export that is never
         * followed by another does not stay behind either.
         */
        fun removeStaleExports(context: Context) {
            deleteExportsOlderThan(exportsDir(context), cutoffMillis = System.currentTimeMillis() - MAX_EXPORT_AGE_MILLIS)
        }

        /** Deletes every file in [dir] last written before [cutoffMillis]; a missing dir is fine. */
        internal fun deleteExportsOlderThan(dir: File, cutoffMillis: Long) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffMillis) file.delete()
            }
        }
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

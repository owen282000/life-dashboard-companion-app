package com.owen282000.lifedashboard.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.PayloadPreview
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WebhookLogRow
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.screens.exportFileName
import com.owen282000.lifedashboard.screens.ld.LogStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class LogsState(
    val rows: List<WebhookLogRow>,
    val stats: LogStats,
    val pending: Int
)

enum class LogExportFormat { Json, Csv }

/**
 * The log as the Logs tab reads it. It outlives the list leaving the composition for a pushed page,
 * so coming back finds the rows, and with them the scroll position, right away. All of it runs off
 * the main thread: a log holds up to 5 MB of payloads.
 */
class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val preferences = application.appPreferences()

    private val _state = MutableStateFlow<LogsState?>(null)

    /** Null until the first read is back, so the list does not flash its empty state. */
    val state: StateFlow<LogsState?> = _state.asStateFlow()

    private val _isPulling = MutableStateFlow(false)
    val isPulling: StateFlow<Boolean> = _isPulling.asStateFlow()

    private var loading: Job? = null

    fun refresh(pulled: Boolean = false) {
        if (pulled) _isPulling.value = true
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                _state.value = withContext(Dispatchers.IO) { read() }
            } finally {
                _isPulling.value = false
            }
        }
    }

    private fun read(): LogsState {
        val rows = preferences.getWebhookLogRows()
        return LogsState(rows, LogStats.of(rows.map { it.log }), PendingSyncStore.forContext(app).size())
    }

    fun delete(ids: Set<String>) {
        loading?.cancel()
        _state.value = _state.value?.let { state ->
            val rows = state.rows.filterNot { it.log.id in ids }
            state.copy(rows = rows, stats = LogStats.of(rows.map { it.log }))
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { preferences.deleteWebhookLogs(ids) }
            refresh()
        }
    }

    /** The logs in [ids] through the share sheet. */
    fun export(ids: Set<String>, format: LogExportFormat) = sharing {
        val logs = preferences.getWebhookLogs(null).filter { it.id in ids }
        val manager = ExportManager(app)
        val title = app.getString(R.string.logs_export_logs)
        when (format) {
            LogExportFormat.Json -> manager.shareFile(manager.exportAsJson(logs), exportFileName("logs", "json"), "application/json", title)
            LogExportFormat.Csv -> manager.shareFile(manager.exportAsCsv(logs), exportFileName("logs", "csv"), "text/csv", title)
        }
    }

    /** A payload the store cut short is no JSON any more, so it is shared as plain text. */
    fun sharePayload(id: String) = sharing {
        val payload = preferences.getWebhookLog(id)?.rawPayload ?: return@sharing
        val stored = PayloadPreview.stored(payload)
        val whole = stored.length == payload.length
        ExportManager(app).shareFile(
            stored,
            exportFileName("payload", if (whole) "json" else "txt"),
            if (whole) "application/json" else "text/plain",
            app.getString(R.string.payload_share)
        )
    }

    private fun sharing(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: IOException) {
                Toast.makeText(app, R.string.sync_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** One log with its payload, for the page of a delivery. */
    suspend fun log(id: String): WebhookLog? = withContext(Dispatchers.IO) { preferences.getWebhookLog(id) }
}

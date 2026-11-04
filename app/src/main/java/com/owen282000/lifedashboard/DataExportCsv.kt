package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A data export's payload as one table, the same as on iOS: a row per record, `data_type` first and
 * a column for every field any record has, empty where a record lacks it. Numbers and booleans are
 * written as the JSON has them, a nested value (sleep stages, nutrients, a day's apps) as compact
 * JSON in its cell. What is not a list of records (timestamp, app_version, source) stays in the
 * JSON export.
 */
object DataExportCsv {
    /** Leading columns, when the payload has them; the rest follow in alphabetical order. */
    private val leadingColumns = listOf("uuid", "date", "time", "start_time", "end_time", "session_end_time", "source")

    fun fromPayload(payload: String): String {
        val root = Json.parseToJsonElement(payload) as? JsonObject ?: return header(emptyList())
        val rows = root.keys.sorted().flatMap { type ->
            records(root.getValue(type)).map { type to it }
        }
        val fields = rows.flatMapTo(mutableSetOf()) { (_, record) -> record.keys }
        val leading = leadingColumns.filter { it in fields }
        val columns = leading + (fields - leading.toSet()).sorted()

        return buildString {
            append(header(columns))
            rows.forEach { (type, record) ->
                appendLine((listOf(type) + columns.map { cell(record[it]) }).joinToString(",", transform = ::field))
            }
        }
    }

    private fun header(columns: List<String>) = (listOf("data_type") + columns).joinToString(",", transform = ::field) + "\n"

    /** The objects of a list of records. Anything else, a list of ids included, is no table. */
    private fun records(value: JsonElement): List<JsonObject> =
        (value as? JsonArray)?.takeIf { list -> list.all { it is JsonObject } }?.map { it as JsonObject }.orEmpty()

    private fun cell(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

package com.owen282000.lifedashboard

import java.time.Duration
import java.time.Instant

/** Result of reading all pages of one record type within a time window. */
data class PagedResult<T>(
    val records: List<T>,
    val pageCount: Int,
    val skippedWindows: Int = 0
)

/**
 * Pure sync/read logic, kept free of Health Connect types so it can be unit tested on the JVM.
 */
object ResilientReadLogic {

    val MIN_BISECT_WINDOW: Duration = Duration.ofMinutes(5)

    /**
     * Caps [records] to [maxLimit], keeping the OLDEST records, then extends the batch with
     * every record sharing the boundary timestamp. This guarantees that every dropped record is
     * strictly newer than every kept one, so advancing lastSync to the kept batch's maximum
     * timestamp and filtering with a strict '>' never skips a dropped record (issue #38: without
     * the tie extension, records sharing the boundary lastModifiedTime that fell just past the
     * cap were above the cap but not above the watermark, and were skipped forever).
     */
    fun <T> capOldestFirst(records: List<T>, maxLimit: Int, timeOf: (T) -> Instant): List<T> {
        if (records.size <= maxLimit) return records
        val sorted = records.sortedBy(timeOf)
        val boundary = timeOf(sorted[maxLimit - 1])
        var end = maxLimit
        while (end < sorted.size && timeOf(sorted[end]) == boundary) end++
        return sorted.take(end)
    }

    /**
     * Caps sample-carrying records (heart rate, skin temperature) oldest-first at RECORD
     * granularity: whole records are included until the running sample count reaches
     * [maxSamples], then the batch is extended with every record sharing the boundary
     * timestamp. A record is either fully delivered or fully deferred, and the same
     * strict-'>' watermark guarantee as [capOldestFirst] holds.
     */
    fun <T> capRecordsBySamples(
        records: List<T>,
        maxSamples: Int,
        samplesOf: (T) -> Int,
        timeOf: (T) -> Instant
    ): List<T> {
        val sorted = records.sortedBy(timeOf)
        val included = mutableListOf<T>()
        var sampleCount = 0
        for (record in sorted) {
            if (sampleCount >= maxSamples && timeOf(record) != timeOf(included.last())) break
            included += record
            sampleCount += samplesOf(record)
        }
        return included
    }

    /**
     * Reads a window via [read], falling back to recursive bisection when the reader throws
     * "startTime must be before endTime". Some source apps (e.g. Zepp for Amazfit devices) write
     * interval records with startTime == endTime; the Health Connect client rejects such a record
     * while materializing the read response, which would otherwise fail the entire type
     * (issue #12). Only the smallest sub-window still containing a malformed record is dropped,
     * so one bad record costs at most [minWindow] of data instead of the whole read.
     *
     * Records overlapping a split point are returned by both halves; [idOf] dedupes them.
     */
    suspend fun <T> readResilient(
        startTime: Instant,
        endTime: Instant,
        minWindow: Duration = MIN_BISECT_WINDOW,
        idOf: (T) -> Any,
        read: suspend (Instant, Instant) -> PagedResult<T>
    ): PagedResult<T> {
        return try {
            read(startTime, endTime)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("startTime must be before endTime") != true) throw e
            if (Duration.between(startTime, endTime) <= minWindow) {
                return PagedResult(emptyList(), 0, skippedWindows = 1)
            }
            val mid = startTime.plus(Duration.between(startTime, endTime).dividedBy(2))
            val first = readResilient(startTime, mid, minWindow, idOf, read)
            val second = readResilient(mid, endTime, minWindow, idOf, read)
            PagedResult(
                records = (first.records + second.records).distinctBy(idOf),
                pageCount = first.pageCount + second.pageCount,
                skippedWindows = first.skippedWindows + second.skippedWindows
            )
        }
    }
}

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
     * Caps [records] to at most [maxLimit], keeping the OLDEST records. This guarantees that
     * every dropped record is newer than every kept one, so advancing lastSync to the kept
     * batch's maximum timestamp never skips a dropped record; later syncs catch up in batches.
     */
    fun <T> capOldestFirst(records: List<T>, maxLimit: Int, timeOf: (T) -> Instant): List<T> =
        if (records.size > maxLimit) records.sortedBy(timeOf).take(maxLimit) else records

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

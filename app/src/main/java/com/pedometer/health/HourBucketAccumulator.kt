package com.pedometer.health

/**
 * Buckets step-detector events into (date, hour) counts, flushing when the hour rolls over,
 * when [flushEvery] events have piled up, or on demand. Pure — the caller supplies the clock
 * and does the persisting.
 */
class HourBucketAccumulator(
    private val flushEvery: Int = 25,
    private val onFlush: (date: String, hour: Int, steps: Int) -> Unit,
) {
    private var date: String? = null
    private var hour: Int = -1
    private var count: Int = 0

    fun add(eventDate: String, eventHour: Int, steps: Int = 1) {
        if (date != null && (eventDate != date || eventHour != hour)) flush()
        date = eventDate
        hour = eventHour
        count += steps
        if (count >= flushEvery) flush()
    }

    fun flush() {
        val d = date
        if (d != null && count > 0) onFlush(d, hour, count)
        count = 0
    }
}

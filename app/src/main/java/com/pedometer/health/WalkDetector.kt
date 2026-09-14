package com.pedometer.health

/** One minute of step counts from the minute_steps table (phone detector). */
data class WalkMinute(val first: Long, val steps: Int)

/** A detected walking bout. */
data class WalkSegment(
    val startMinute: Long,
    val endMinute: Long,
    val activeMinutes: Int,
    val steps: Int,
)

/**
 * Segments a per-minute step series into walking bouts.
 *
 * Definitions (agreed with the user, 2026-09-14):
 *  - an "active" minute is >= [ACTIVE_STEPS_PER_MIN] steps (standing/shuffling is not walking);
 *  - a gap between consecutive active minutes of more than [GAP_TOLERANCE_MINUTES]+1
 *    wall-clock minutes (i.e. more than 3 paused minutes) ends the bout;
 *  - a bout qualifies as a walk when it contains >= [MIN_WALK_MINUTES] active minutes.
 *
 * Pure: the caller reads the database and owns the clock.
 */
object WalkDetector {
    private const val MIN_WALK_MINUTES = 10
    private const val GAP_TOLERANCE_MINUTES = 3
    private const val ACTIVE_STEPS_PER_MIN = 30

    fun detect(minutes: List<WalkMinute>): List<WalkSegment> {
        val active = minutes.filter { it.steps >= ACTIVE_STEPS_PER_MIN }.sortedBy { it.first }
        val segments = mutableListOf<WalkSegment>()
        var group = mutableListOf<WalkMinute>()

        fun flush() {
            if (group.size >= MIN_WALK_MINUTES) {
                segments.add(
                    WalkSegment(
                        startMinute = group.first().first,
                        endMinute = group.last().first,
                        activeMinutes = group.size,
                        steps = group.sumOf { it.steps },
                    ),
                )
            }
            group = mutableListOf()
        }

        for (m in active) {
            if (group.isNotEmpty()) {
                val gapMin = (m.first - group.last().first) / 60_000L
                if (gapMin > GAP_TOLERANCE_MINUTES + 1) flush()
            }
            group.add(m)
        }
        flush()
        return segments
    }

    /** Convenience overload for raw (minute-start, steps) pairs straight from the DAO. */
    @JvmName("detectPairs")
    fun detect(minutes: List<Pair<Long, Int>>): List<WalkSegment> =
        detect(minutes.map { WalkMinute(first = it.first, steps = it.second) })
}

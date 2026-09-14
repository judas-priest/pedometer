package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkDetectorTest {

    private fun minutes(vararg pairs: Pair<Long, Int>) =
        pairs.map { WalkMinute(first = it.first, steps = it.second) }
    private val M = 60_000L // minute in ms, minute keys are minute-start timestamps

    @Test
    fun `short burst is not a walk`() {
        val input = minutes(0L * M to 80, 1L * M to 90, 2L * M to 85, 5L * M to 0, 6L * M to 0)
        assertEquals(emptyList<WalkSegment>(), WalkDetector.detect(input))
    }

    @Test
    fun `ten active minutes form a walk`() {
        val input = (0L..9L).flatMap { listOf(it * M to 60 + it.toInt()) }
        val walks = WalkDetector.detect(input)
        assertEquals(1, walks.size)
        assertEquals(0L * M, walks[0].startMinute)
        assertEquals(9L * M, walks[0].endMinute)
        assertEquals(10, walks[0].activeMinutes)
    }

    @Test
    fun `gap under tolerance does not break the walk`() {
        // 6 active minutes, 2-minute pause (traffic light), 6 more active minutes
        val input = (0L..5L).map { it * M to 70 } +
            listOf(6L * M to 0, 7L * M to 0) +
            (8L..13L).map { it * M to 70 }
        val walks = WalkDetector.detect(input)
        assertEquals(1, walks.size)
        assertEquals(12, walks[0].activeMinutes)
    }

    @Test
    fun `gap over tolerance splits into two walks`() {
        // 10 active minutes, 5-minute pause (shop), 10 active minutes
        val input = (0L..9L).map { it * M to 70 } +
            (10L..14L).map { it * M to 0 } +
            (15L..24L).map { it * M to 70 }
        val walks = WalkDetector.detect(input)
        assertEquals(2, walks.size)
        assertEquals(0L * M, walks[0].startMinute)
        assertEquals(15L * M, walks[1].startMinute)
    }

    @Test
    fun `standing minutes are not walking`() {
        // 15 minutes at 5 steps/min — shuffling around an apartment is not a walk
        val input = (0L..14L).map { it * M to 5 }
        assertEquals(emptyList<WalkSegment>(), WalkDetector.detect(input))
    }

    @Test
    fun `total steps are summed over active minutes`() {
        val input = (0L..9L).map { it * M to 60 }
        assertEquals(600, WalkDetector.detect(input)[0].steps)
    }
}

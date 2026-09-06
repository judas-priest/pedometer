package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HourBucketAccumulatorTest {

    private data class Flushed(val date: String, val hour: Int, val steps: Int)

    @Test
    fun `events in the same hour accumulate without flushing`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)

        assertEquals(emptyList<Flushed>(), out)
    }

    @Test
    fun `crossing into a new hour flushes the previous bucket`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 11)

        assertEquals(listOf(Flushed("2026-08-09", 10, 2)), out)
    }

    @Test
    fun `crossing midnight flushes the previous day`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 23)
        acc.add("2026-08-10", 0)

        assertEquals(listOf(Flushed("2026-08-09", 23, 1)), out)
    }

    @Test
    fun `explicit flush emits and resets`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.flush()
        acc.flush()

        assertEquals(listOf(Flushed("2026-08-09", 10, 1)), out)
    }

    @Test
    fun `flush with nothing pending emits nothing`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.flush()

        assertEquals(emptyList<Flushed>(), out)
    }

    @Test
    fun `flushEvery forces a flush once the threshold is reached`() {
        val out = mutableListOf<Flushed>()
        val acc = HourBucketAccumulator(flushEvery = 2) { d, h, s -> out.add(Flushed(d, h, s)) }

        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)
        acc.add("2026-08-09", 10)

        assertEquals(listOf(Flushed("2026-08-09", 10, 2)), out)
    }
}

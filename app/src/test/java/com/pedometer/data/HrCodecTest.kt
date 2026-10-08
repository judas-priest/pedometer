package com.pedometer.data

import com.pedometer.data.HrCodec.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrCodecTest {

    @Test
    fun `hr codec roundtrip is lossless`() {
        val samples = (0 until 1440).map {
            HrCodec.Sample(it * 60_000L + (if (it % 7 == 0) 300 else 0), 95 + (it % 40))
        }
        val blob = HrCodec.compress(samples)
        assertTrue("size=${blob.size}", blob.size < 6 * 1024) // ~40 KB raw -> < 6 KB
        val back = HrCodec.decompress(blob)
        assertEquals(samples.size, back.size)
        assertEquals(samples, back)
    }

    @Test
    fun `hr codec handles empty and single sample`() {
        assertEquals(0, HrCodec.compress(emptyList()).size)
        assertEquals(emptyList<HrCodec.Sample>(), HrCodec.decompress(ByteArray(0)))
        val one = listOf(HrCodec.Sample(1_700_000_000_000L, 112))
        assertEquals(one, HrCodec.decompress(HrCodec.compress(one)))
    }

    @Test
    fun `mergeSamples dedupes by timestamp keeping last and sorts`() {
        val existing = listOf(Sample(100L, 90), Sample(200L, 91), Sample(300L, 92))
        val incoming = listOf(Sample(300L, 95), Sample(250L, 99), Sample(400L, 93))
        // 300L exists in both — incoming wins (fresher delivery)
        val merged = HrCodec.mergeSamples(existing, incoming)
        assertEquals(
            listOf(Sample(100L, 90), Sample(200L, 91), Sample(250L, 99), Sample(300L, 95), Sample(400L, 93)),
            merged,
        )
        // duplicates INSIDE incoming collapse too
        val dup = HrCodec.mergeSamples(emptyList(), listOf(Sample(10L, 80), Sample(10L, 81)))
        assertEquals(listOf(Sample(10L, 81)), dup)
        // roundtrip: merged result compresses and restores identically
        val blob = HrCodec.compress(merged)
        assertEquals(merged, HrCodec.decompress(blob))
    }
}

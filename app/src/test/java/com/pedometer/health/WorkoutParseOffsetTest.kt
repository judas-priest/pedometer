package com.pedometer.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the workout-summary byte layout against REAL captured payloads (written
 * by ActivitySync's debug dump to <filesDir>/workout_dumps/ — everything after
 * the 8-byte file header + the 9-byte v5 header, INCLUDING the leading V2
 * workout-type short), so the HR offset can never silently drift again.
 *
 * Ground truth walk 1 (evening 2026-09-27, sport 0x16 outdoor walking v2,
 * fileId version 5): distance 8111 m, calories 660, duration field 5995 s
 * (end−start = 6003), steps 10097, HR 114 avg / 145 max / 85 min.
 *
 * Layout, byte offsets within this body (LE):
 *
 *   0  V2 type short (02 00)
 *   2  start u32, 6 end u32, 10 duration u32 (5995; end−start = 6003),
 *  14  unknown4 (=duration)
 *  18  distance u32 (8111 m)
 *  22  totalCal u16 (660), 24 activeCal u16 (skipped)
 *  26  pace_avg s/km i16 (739 ≈ 6003 s / 8.111 km), 28 reserved i16 (0)
 *  30  pace_max i32 (537), 34 pace_min i32 (2800)
 *  38  speed_avg km/h f32 (4.93), 42 speed_max f32 (6.73)
 *  46  steps u32 (10097)
 *  50  step_len cm u16 (76), 52 step_rate_avg u16 (101), 54 step_rate_max u16 (137)
 *  56  HR avg/max/min (114 / 145 / 85)
 *
 * I.e. after the two calorie shorts exactly 30 bytes precede HR — the extra
 * 2 vs the pre-2026-09 layout is the reserved short at 28 next to pace_avg.
 */
class WorkoutParseOffsetTest {

    private val walkingV5Body = hex(
        "02 00 f5 aa ba 6a 68 c2 ba 6a 6b 17 " +
        "00 00 6b 17 00 00 af 1f 00 00 94 02 " +
        "b2 01 e3 02 00 00 19 02 00 00 f0 0a " +
        "00 00 0a d7 9b 40 7b 14 d6 40 71 27 " +
        "00 00 4c 00 65 00 89 00 72 91 55 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 39 00 00 00 ee " +
        "0a 00 00 20 0b 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 08 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 e5 ec f8 65"
    )

    // Second real walk (dump_3414145386, 2026-09-27, short 5-min stroll,
    // also 0x16 v5): steps 167, HR 103/119/92 — independent confirmation
    // of the same layout.
    private val walkingV5Body2 = hex(
        "02 00 cb 7f b9 6a 04 81 b9 6a 30 01 " +
        "00 00 30 01 00 00 75 00 00 00 20 00 " +
        "14 00 26 0a 00 00 fb 01 00 00 7c 15 " +
        "00 00 d7 a3 b0 3f 48 e1 e2 40 a7 00 " +
        "00 00 4b 00 20 00 8a 00 67 77 5c 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 0f " +
        "00 00 00 fc 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 " +
        "00 00 00 00 00 00 df 10 fb a4"
    )

    @Test
    fun `real walking v5 payload yields correct HR and layout fields`() {
        assertEquals(178, walkingV5Body.size)

        val bb = ByteBuffer.wrap(walkingV5Body).order(ByteOrder.LITTLE_ENDIAN)
        val f = parseWorkoutBody(0x16, 5, bb)
        println("DBG steps=" + f!!.steps + " hrAvg=" + f.hrAvg + " pos=" + bb.position() +
            " bytes40=" + walkingV5Body.copyOfRange(40, 60).joinToString(" ") { "%02x".format(it) } +
            " dur=" + f.durationSec + " dist=" + f.distanceM + " cal=" + f.calories)

        assertNotNull(f)
        assertEquals(5995, f!!.durationSec) // duration field; end−start = 6003
        assertEquals(8111, f.distanceM)
        assertEquals(660, f.calories)
        assertEquals(10097, f.steps)
        assertEquals(114, f.hrAvg)
        assertEquals(145, f.hrMax)
        assertEquals(85, f.hrMin)
    }

    @Test
    fun `second real walking v5 payload confirms layout`() {
        val bb = ByteBuffer.wrap(walkingV5Body2).order(ByteOrder.LITTLE_ENDIAN)
        val f = parseWorkoutBody(0x16, 5, bb)

        assertNotNull(f)
        assertEquals(304, f!!.durationSec)
        assertEquals(117, f.distanceM)
        assertEquals(32, f.calories)
        assertEquals(167, f.steps)
        assertEquals(103, f.hrAvg)
        assertEquals(119, f.hrMax)
        assertEquals(92, f.hrMin)
    }

    @Test
    fun `off-by-two skip would read step rate as HR - guards regression`() {
        // If anyone moves skipToHr for 0x16 v5 (e.g. 30 -> 32), the parser would
        // read step_rate_max as hrAvg: 85/0/0 instead of 114/145/85. The HR
        // assertions above fail on such a change; here we also pin the exact
        // HR byte positions in the raw payload for diagnosis.
        assertEquals(114, walkingV5Body[56].toInt() and 0xFF)
        assertEquals(145, walkingV5Body[57].toInt() and 0xFF)
        assertEquals(85, walkingV5Body[58].toInt() and 0xFF)
        // and what sits right before HR: step-rate fields, NOT HR
        assertEquals(101, u16(52))
        assertEquals(137, u16(54))
    }

    private fun u16(offset: Int) =
        (walkingV5Body[offset].toInt() and 0xFF) or ((walkingV5Body[offset + 1].toInt() and 0xFF) shl 8)

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}

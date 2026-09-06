package com.pedometer.data

/**
 * Returns every version step in [from, to) that no migration spans — i.e. the points where
 * an upgrading user's database would be destroyed if destructive fallback were on.
 *
 * A migration (start, end) covers every step v where start <= v < end, so a single 7→9
 * migration covers both 7 and 8.
 *
 * Pure function so it can be unit-tested without Room or a device.
 */
fun missingMigrationSteps(
    steps: List<Pair<Int, Int>>,
    from: Int,
    to: Int,
): List<Int> = (from until to).filter { version ->
    steps.none { (start, end) -> start <= version && version < end }
}

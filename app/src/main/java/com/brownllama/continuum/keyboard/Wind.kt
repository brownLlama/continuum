// The wind that sways the grass under the keys: when it blows, and how far each pixel leans.
package com.brownllama.continuum.keyboard

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A gust every [PERIOD_MS], lasting [GUST_MS], sweeping across the grass left to right ([SWEEP_MS]
 * for the whole width). Between gusts the grass is still and nothing is redrawn, so the wind costs
 * battery only while it blows (about a third of the time), and then at [FRAME_MS] steps, in the
 * pixel grid's whole-pixel moves, over the grass's own area only. Everything is a pure function of
 * the time, so there is no state and no memory to keep.
 */
internal object Wind {
    const val PERIOD_MS = 9_000L
    const val GUST_MS = 3_200L
    const val SWEEP_MS = 1_600L
    const val FRAME_MS = 125L
    /** Columns that lean together, so neighbours stay joined up and it looks like grass, not static. */
    const val SEGMENT = 10

    /** How hard the wind blows at [col] of [columns] at [now] (uptime ms): 0 (calm) to 1 (the gust's peak). */
    fun strength(col: Int, columns: Int, now: Long): Float {
        val delay = SWEEP_MS * col / maxOf(1, columns)
        val local = Math.floorMod(now - delay, PERIOD_MS)
        return if (local >= GUST_MS) 0f else sin(PI * local / GUST_MS).toFloat()
    }

    /**
     * How many grass pixels the pixel in [col], [row] (0: the ground) of a field [columns] × [rows]
     * leans to the right (negative: left) at [now]. The ground stays put and the tips lean most;
     * each stretch of [SEGMENT] columns flutters a little on its own.
     */
    fun lean(col: Int, row: Int, columns: Int, rows: Int, now: Long): Int {
        if (rows < 3) return 0
        val seg = col / SEGMENT
        val s = strength(seg * SEGMENT, columns, now)
        if (s == 0f) return 0
        val h = row / (rows - 1f)
        val max = if (rows >= 9) 2f else 1f
        val flutter = 0.45f * sin(now / 230.0 + seg * 1.7).toFloat()
        return (h * h * max * s * (1f + flutter)).roundToInt()
    }

    /** Whether any part of the grass of [columns] columns moves at [now]. */
    fun blowing(now: Long): Boolean = Math.floorMod(now, PERIOD_MS) < GUST_MS + SWEEP_MS

    /** Milliseconds until the grass next needs redrawing: the next frame in a gust, else when the next gust starts. */
    fun nextIn(now: Long): Long {
        val m = Math.floorMod(now, PERIOD_MS)
        return if (m < GUST_MS + SWEEP_MS) FRAME_MS else PERIOD_MS - m
    }
}

/** What leans where: [at] is how many grass pixels the pixel in [col], [row] is moved right. */
internal fun interface Sway {
    fun at(col: Int, row: Int): Int
}

// The wind that sways the grass under the keys: when it blows, how far, and how far each pixel leans.
package com.brownllama.continuum.keyboard

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gusts at random. Time is cut into slots of [SLOT_MS]; each slot holds at most one gust, or none
 * (about one slot in ten is calm), starting at a random moment in the slot and lasting a random
 * time. Every gust has its own random strength (how many pixels the tips lean, [lean]), direction
 * (mostly right, sometimes left), and reach (it may sweep the whole grass or only a stretch of it),
 * and sweeps across that stretch like a real gust. Everything comes from a hash of the slot, so the
 * wind is a pure function of the time: no state, no memory, and the same every time it is asked.
 *
 * Between gusts nothing is redrawn ([nextIn]), so the wind costs battery only while it blows, and
 * then at [FRAME_MS] steps over the grass's own strip.
 */
internal object Wind {
    const val SLOT_MS = 6_000L
    const val FRAME_MS = 125L
    /** Columns that lean together, so neighbours stay joined up and it looks like grass, not static. */
    const val SEGMENT = 10
    private const val MAX_GUST_MS = 3_400L
    private const val MAX_SWEEP_MS = 1_800L
    private const val CALM_SHARE = 0.1f

    /** One gust, in a slot: when it starts (ms into the slot) and how long, its reach, strength and direction. */
    internal class Gust(
        val startMs: Long, val durationMs: Long, val sweepMs: Long,
        /** The stretch of the grass it blows over, as fractions of the width. */
        val from: Float, val to: Float,
        /** 0.5..1: how much of the most the tips lean. */
        val force: Float, val dir: Int,
    ) {
        val endMs get() = startMs + durationMs + sweepMs
    }

    /** The gust of slot [k], or null if the slot is calm. */
    internal fun gust(k: Long): Gust? {
        fun n(salt: Int) = GrassField.noise((k xor (k ushr 32)).toInt(), 700 + salt)
        if (n(0) < CALM_SHARE) return null
        val duration = 1_600L + (n(1) * (MAX_GUST_MS - 1_600L)).toLong()
        val sweep = 800L + (n(2) * (MAX_SWEEP_MS - 800L)).toLong()
        val start = (n(3) * (SLOT_MS - duration - sweep - 200L)).toLong()
        val span = 0.4f + 0.6f * n(4)
        val from = n(5) * (1f - span)
        return Gust(start, duration, sweep, from, from + span, 0.5f + 0.5f * n(6), if (n(7) < 0.25f) -1 else 1)
    }

    /** How many pixels the tips lean at most, in grass [rows] tall (for the strongest gust): about a third of its height, up to 3. */
    internal fun maxLean(rows: Int) = (rows * 0.3f).coerceIn(1f, 3f)

    /** How hard the wind blows at [col] of [columns] at [now] (uptime ms, 0: calm .. 1: the gust's peak, times its force). */
    fun strength(col: Int, columns: Int, now: Long): Float {
        val k = Math.floorDiv(now, SLOT_MS)
        val g = gust(k) ?: return 0f
        val at = col / maxOf(1, columns).toFloat()
        if (at < g.from || at > g.to) return 0f
        val local = now - k * SLOT_MS - g.startMs - (g.sweepMs * (at - g.from) / (g.to - g.from)).toLong()
        return if (local < 0 || local >= g.durationMs) 0f else g.force * sin(PI * local / g.durationMs).toFloat()
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
        val g = gust(Math.floorDiv(now, SLOT_MS)) ?: return 0
        val h = row / (rows - 1f)
        val flutter = 0.45f * sin(now / 230.0 + seg * 1.7).toFloat()
        return g.dir * (h * h * maxLean(rows) * s * (1f + flutter)).roundToInt()
    }

    /** Whether any part of the grass may be moving at [now]. */
    fun blowing(now: Long): Boolean {
        val k = Math.floorDiv(now, SLOT_MS)
        val g = gust(k) ?: return false
        val local = now - k * SLOT_MS
        return local >= g.startMs && local < g.endMs
    }

    /** Milliseconds until the grass next needs redrawing: the next frame in a gust, else when the next gust starts. */
    fun nextIn(now: Long): Long {
        if (blowing(now)) return FRAME_MS
        var k = Math.floorDiv(now, SLOT_MS)
        repeat(8) {
            val g = gust(k)
            if (g != null) {
                val start = k * SLOT_MS + g.startMs
                if (start > now) return start - now
            }
            k++
        }
        return SLOT_MS
    }
}

/** What leans where: [at] is how many grass pixels the pixel in [col], [row] is moved right. */
internal fun interface Sway {
    fun at(col: Int, row: Int): Int
}

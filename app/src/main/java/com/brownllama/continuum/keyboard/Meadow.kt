// The flowers in the grass under the keys, which the visiting animals stop to eat.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.random.Random

/**
 * A pixel flower in the grass, on the same grid as the grass and the animals: its stem stands in
 * column [col] (from the left), its head is four petals round a centre, [row] pixels above the
 * ground, with a leaf on the stem ([leafLeft] or right). [kind] picks its colours.
 *
 * While an animal eats it, [bites] counts up: 1, the top petal and the one facing the animal ([from])
 * go; 2, the other petals; 3, the centre and the top of the stem. Then it is [gone] until the
 * meadow grows again ([Meadow.regrow]).
 */
internal class Flower(val col: Int, val row: Int, val kind: Int, val leafLeft: Boolean) {
    var bites = 0
    var gone = false
    /** The way the animal eating it faces (1: right, -1: left). */
    var from = 1

    companion object {
        /** Bites until it is gone. */
        const val BITES = 4
    }
}

/**
 * The flowers in the grass. They grow in random places, a few per screen width and well apart
 * ([fit]), their heads in the grass a few pixels above the animals' feet, where a grazing animal's
 * mouth reaches. Eaten ones grow back somewhere else the next time the keyboard opens ([regrow]).
 */
internal class Meadow(private val random: Random = Random.Default) {
    val flowers = ArrayList<Flower>()
    private var columns = 0
    private var rows = 0
    private var feet = 0

    /**
     * The grass is [columns] × [rows] pixels, the animals' feet [feet] pixels above its ground. A
     * different size than before grows the flowers afresh; the same size keeps them.
     */
    fun fit(columns: Int, rows: Int, feet: Int) {
        if (columns == this.columns && rows == this.rows && feet == this.feet) return
        this.columns = columns
        this.rows = rows
        this.feet = feet
        flowers.clear()
        grow()
    }

    /** The eaten flowers grow back, each somewhere new. */
    fun regrow() {
        if (flowers.none { it.gone }) return
        flowers.removeAll { it.gone }
        grow()
    }

    /**
     * The flower (not eaten yet) a mouth comes to, moving from column [from] to [to] facing [dir]
     * (1: right, -1: left): the first one on its way, or null.
     */
    fun reached(from: Int, to: Int, dir: Int): Flower? {
        var best: Flower? = null
        for (f in flowers) {
            if (f.gone || f.bites > 0) continue
            val on = if (dir > 0) f.col in from + 1..to else f.col in to until from
            if (on && (best == null || (f.col - best.col) * dir < 0)) best = f
        }
        return best
    }

    /** Whether column [col] holds part of a flower (its stem or petals), which grass in front leaves clear. */
    fun covers(col: Int): Boolean = flowers.any { !it.gone && abs(it.col - col) <= 1 }

    private fun grow() {
        // Room for a whole flower (stem, petals) inside the grass, and away from the edges.
        if (rows < MIN_ROWS || columns < 2 * EDGE + MIN_GAP) return
        val count = maxOf(1, columns / COLUMNS_PER_FLOWER)
        var tries = 0
        while (flowers.size < count && tries++ < 100) {
            val col = EDGE + random.nextInt(columns - 2 * EDGE)
            if (flowers.any { abs(it.col - col) < MIN_GAP }) continue
            val row = (feet + HEAD_ABOVE_FEET + random.nextInt(HEAD_SPREAD)).coerceIn(MIN_HEAD_ROW, rows - 2)
            flowers += Flower(col, row, random.nextInt(KINDS), random.nextBoolean())
        }
    }

    /**
     * Draws the flowers on grass whose ground is at [bottom], [unit] px per pixel: petals and centres
     * in [petals] and [centres] by kind, stems in [stem] and leaves in [leaf].
     */
    fun draw(c: Canvas, paint: Paint, unit: Float, bottom: Float, petals: IntArray, centres: IntArray, stem: Int, leaf: Int, sway: Sway? = null) {
        fun pixel(col: Int, row: Int, color: Int, rows: Int = 1) {
            if (rows <= 0) return
            paint.color = color
            val x = (col + (sway?.at(col, row) ?: 0)) * unit
            c.drawRect(x, bottom - (row + rows) * unit, x + unit, bottom - row * unit, paint)
        }
        for (f in flowers) {
            if (f.gone) continue
            val r = f.row
            // The stem up to the lowest petal; once the centre is eaten, a pixel shorter.
            pixel(f.col, 0, stem, rows = if (f.bites >= 3) r - 2 else r - 1)
            if (r >= 4) pixel(f.col + if (f.leafLeft) -1 else 1, r - 3, leaf)
            if (f.bites >= 3) continue
            val petal = petals[f.kind]
            if (f.bites < 2) {
                pixel(f.col - f.from, r, petal)
                pixel(f.col, r - 1, petal)
            }
            if (f.bites < 1) {
                pixel(f.col + f.from, r, petal)
                pixel(f.col, r + 1, petal)
            }
            pixel(f.col, r, centres[f.kind])
        }
    }

    companion object {
        /** Flower colour sets (see [draw]'s petals and centres). */
        const val KINDS = 4
        /** About one flower for this many columns of grass (three or so across a phone). */
        private const val COLUMNS_PER_FLOWER = 60
        /** Columns kept clear at each edge, and at least between two flowers. */
        private const val EDGE = 4
        private const val MIN_GAP = 12
        /** The head's centre: this many pixels above the animals' feet, plus up to [HEAD_SPREAD] - 1 more. */
        private const val HEAD_ABOVE_FEET = 5
        private const val HEAD_SPREAD = 3
        /** The lowest a head's centre can be (its stem, leaf and lowest petal under it) and the grass it needs. */
        private const val MIN_HEAD_ROW = 4
        private const val MIN_ROWS = 6
    }
}

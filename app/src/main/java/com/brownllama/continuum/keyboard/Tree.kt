// The tree on the right of the grass: pick it up and drag it to move the cursor.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A pixel tree in the grass, in the same chunky pixels ([unit] screen pixels each) as the grass and
 * the animals: a round leafy crown, a few red apples, and a trunk, with the dark outline everything
 * here has. It stands at the right of the grass, above Android's gesture bar, its top no higher than
 * the keys; the grass is only as tall as the setting says, so the tree is drawn to fit ([fit]).
 *
 * A finger picks it up ([pickUp]); it hangs from the finger ([carry]) until it is let go ([drop]),
 * and then it slides back to where it stands. Its picture is drawn once into a bitmap for its size and
 * colours, so a frame only copies that.
 */
internal class Tree(private val unit: Float) {
    /** Home: the top left of the standing tree (keyboard coordinates), and its size in sprite pixels (0: no tree fits). */
    var homeLeft = 0f
        private set
    var homeTop = 0f
        private set
    var cols = 0
        private set
    var rows = 0
        private set

    var carried = false
        private set
    /** Sliding back home since [backAt], from ([fromLeft], [fromTop]). */
    var returning = false
        private set
    private var backAt = 0L
    private var fromLeft = 0f
    private var fromTop = 0f
    private var carryX = 0f
    private var carryY = 0f

    private var image: Bitmap? = null
    private var builtKey = 0L
    private val paint = Paint()

    val present get() = rows > 0
    val width get() = cols * unit
    val height get() = rows * unit

    /**
     * Places the tree for a keyboard [viewWidth] wide: its base at [baseY] (just above Android's
     * gesture bar), no higher than [topLimit] (the bottom of the keys), [rightGap] px from the right edge.
     */
    fun fit(viewWidth: Float, baseY: Float, topLimit: Float, rightGap: Float) {
        val fits = ((baseY - topLimit) / unit).toInt().coerceAtMost(MAX_ROWS)
        val r = if (fits >= MIN_ROWS) fits else 0
        val c = if (r == 0) 0 else crownWidth(r)
        if (r != rows || c != cols) image = null
        rows = r
        cols = c
        if (r == 0) return
        homeLeft = kotlin.math.floor((viewWidth - rightGap - c * unit) / unit) * unit
        homeTop = kotlin.math.ceil((baseY - r * unit) / unit) * unit
    }

    /** Whether (x, y) is on the standing tree, widened by [slack] px, and within [from]..[to] (so not over the keys or the gesture bar). */
    fun hit(x: Float, y: Float, slack: Float, from: Float, to: Float): Boolean =
        present && !carried && !returning && x >= homeLeft - slack && x <= homeLeft + width + slack &&
            y >= max(homeTop, from) && y <= min(homeTop + height + slack, to)

    fun pickUp(x: Float, y: Float) {
        carried = true
        returning = false
        carryX = x
        carryY = y
    }

    fun carry(x: Float, y: Float) {
        carryX = x
        carryY = y
    }

    /** Let go: it slides back home. */
    fun drop(now: Long) {
        if (!carried) return
        carried = false
        returning = true
        backAt = now
        fromLeft = carryX - width / 2f
        fromTop = carryY - height * HELD_AT
    }

    /**
     * Draws the tree: standing, hanging from the finger, or sliding home. Returns how many ms until it
     * needs drawing again (while it slides), or [REST].
     */
    fun draw(c: Canvas, now: Long, theme: Palette): Long {
        if (!present) return REST
        var left = homeLeft
        var top = homeTop
        var next = REST
        when {
            carried -> {
                left = carryX - width / 2f
                top = carryY - height * HELD_AT
            }
            returning -> {
                val t = ((now - backAt).toFloat() / BACK_MS).coerceIn(0f, 1f)
                if (t >= 1f) {
                    returning = false
                } else {
                    val e = 1f - (1f - t) * (1f - t)
                    left = fromLeft + (homeLeft - fromLeft) * e
                    top = fromTop + (homeTop - fromTop) * e
                    next = 16L
                }
            }
        }
        // Whole screen pixels, so its own pixels stay crisp.
        c.drawBitmap(imageFor(theme), left.roundToInt().toFloat(), top.roundToInt().toFloat(), paint)
        return next
    }

    /** The colours the tree is drawn in (the grass's greens, a trunk, apples and the outline). */
    class Palette(val outline: Int, val dark: Int, val mid: Int, val light: Int, val highlight: Int, val trunk: Int, val trunkDark: Int, val apple: Int)

    private fun imageFor(p: Palette): Bitmap {
        val key = (p.mid.toLong() shl 20) xor (p.trunk.toLong() shl 4) xor (rows.toLong() shl 40) xor cols.toLong()
        image?.takeIf { builtKey == key }?.let { return it }
        val grid = build(rows, cols)
        val bmp = createBitmap(max(1, (cols * unit).roundToInt()), max(1, (rows * unit).roundToInt()))
        val canvas = Canvas(bmp)
        for (r in 0 until rows) for (col in 0 until cols) {
            val slot = grid[r * cols + col]
            if (slot == NONE) continue
            paint.color = when (slot) {
                OUTLINE -> p.outline
                DARK -> p.dark
                MID -> p.mid
                LIGHT -> p.light
                HIGHLIGHT -> p.highlight
                TRUNK -> p.trunk
                TRUNK_DARK -> p.trunkDark
                else -> p.apple
            }
            canvas.drawRect(col * unit, r * unit, (col + 1) * unit, (r + 1) * unit, paint)
        }
        image = bmp
        builtKey = key
        return bmp
    }

    companion object {
        /** What [draw] returns when nothing needs redrawing until something happens. */
        const val REST = -2L
        const val MIN_ROWS = 8
        const val MAX_ROWS = 18
        private const val BACK_MS = 220L
        /** Where on the tree the finger holds it (0 top .. 1 base). */
        private const val HELD_AT = 0.55f

        const val NONE = 0
        const val OUTLINE = 1
        const val DARK = 2
        const val MID = 3
        const val LIGHT = 4
        const val HIGHLIGHT = 5
        const val TRUNK = 6
        const val TRUNK_DARK = 7
        const val APPLE = 8

        /** The crown's width for a tree [rows] tall: round, a little wider than the crown is tall, and odd (a trunk in the middle). */
        fun crownWidth(rows: Int): Int {
            val crown = crownRows(rows)
            return (crown + 3).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(7)
        }

        private fun crownRows(rows: Int) = (rows * 0.68f).roundToInt().coerceIn(4, rows - 3)

        /**
         * The tree as a grid of colour slots, [rows] × [cols]: an elliptical crown shaded lighter at the top
         * left and darker underneath, with apples, a two-pixel trunk, and a one-pixel outline round it all.
         */
        internal fun build(rows: Int, cols: Int): IntArray {
            val g = IntArray(rows * cols)
            val crown = crownRows(rows)
            val mid = cols / 2
            // Crown: rows 1..crown (row 0 is left empty for the outline above it).
            for (r in 0 until crown) {
                val ry = (r + 0.5f - crown / 2f) / (crown / 2f)
                val half = ((cols - 2) / 2f * sqrt(max(0f, 1f - ry * ry)) + 0.5f).toInt().coerceAtLeast(1)
                for (x in mid - half..mid + half) {
                    if (x !in 1 until cols - 1) continue
                    val u = (x - mid).toFloat() / half
                    val light = (-ry * 0.6f - u * 0.5f)
                    g[(r + 1) * cols + x] = when {
                        light > 0.75f -> HIGHLIGHT
                        light > 0.2f -> LIGHT
                        light > -0.35f -> MID
                        else -> DARK
                    }
                }
            }
            // Apples, in fixed places on the crown.
            for ((fx, fy) in listOf(0.32f to 0.55f, 0.7f to 0.4f, 0.55f to 0.8f)) {
                val x = (1 + fx * (cols - 2)).toInt()
                val r = 1 + (fy * (crown - 1)).toInt()
                if (g[r * cols + x] != NONE && crown >= 5) g[r * cols + x] = APPLE
            }
            // Trunk: two pixels wide, down from the crown's lowest row.
            for (r in crown until rows) {
                g[r * cols + mid] = if (r == rows - 1) TRUNK_DARK else TRUNK
                g[r * cols + mid - 1] = TRUNK_DARK
            }
            // Outline: every empty pixel beside a coloured one.
            val src = g.copyOf()
            fun filled(x: Int, y: Int) = x in 0 until cols && y in 0 until rows && src[y * cols + x] != NONE
            for (y in 0 until rows) for (x in 0 until cols) {
                if (src[y * cols + x] != NONE) continue
                if (filled(x - 1, y) || filled(x + 1, y) || filled(x, y - 1) || filled(x, y + 1)) g[y * cols + x] = OUTLINE
            }
            return g
        }
    }
}

// Where every pixel of the grass under the keys goes, and in which shade (drawn by Grass).
package com.brownllama.continuum.keyboard

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The grass as a grid of pixels, [columns] wide and [rows] tall, row 0 the ground: which shade each
 * pixel is ([at], a colour slot, 0 for none), and which pixels stand in front of a passing animal
 * ([frontAt]). Pixel art in the animals' style: blades in a few shades with a dark outline round
 * them, as the animals have.
 *
 * Three layers, back to front:
 * - **back**: a short dark blade in every column, so the ground is covered;
 * - **middle**: tufts of three to five blades fanning out from one spot, each curving a little
 *   towards its tip, the middle one tallest, light at the tip (now and then a dry, yellower tuft);
 * - **front**: lighter tufts, only in some stretches of the grass. A passing animal walks behind
 *   these ([frontAt]: they are drawn again over it) and in front of the rest.
 *
 * Every blade is darker where it meets the ground. Everything comes from a hash of the column, so
 * the grass looks the same every time.
 */
internal class GrassField(val columns: Int, val rows: Int) {
    private val cells = ByteArray(columns * rows)
    private val fronts = ByteArray(columns * rows)

    /** The colour slot of the pixel in [col], [row] (row 0: the ground), or 0 for none. */
    fun at(col: Int, row: Int): Int = if (inside(col, row)) cells[row * columns + col].toInt() else 0

    /** The slot of the pixel in [col], [row] if it stands in front of an animal (a front tuft or its outline), else 0. */
    fun frontAt(col: Int, row: Int): Int = if (inside(col, row)) fronts[row * columns + col].toInt() else 0

    /** Whether anything in column [col] stands in front of an animal. */
    fun hasFront(col: Int): Boolean = (0 until rows).any { frontAt(col, it) != 0 }

    init {
        if (columns > 0 && rows > 0) grow()
    }

    private fun grow() {
        // Back: a short, dark blade in every column.
        for (col in 0 until columns) {
            val h = height(0.3f, 0.65f, noise(col, BACK_HEIGHT))
            blade(col, h, lean(h, noise(col, BACK_LEAN) - 0.5f), BACK, front = false, thick = false)
        }
        // Middle: tufts every two to four columns.
        var col = -2
        while (col < columns + 2) {
            tuft(col, MIDDLE, 0.55f, 1f, front = false)
            col += 2 + (noise(col, MIDDLE_GAP) * 3).toInt()
        }
        // Front: tufts every four to seven columns, in the stretches where grass stands before the animals.
        col = -2
        while (col < columns + 2) {
            if (wave(col, FRONT_STRETCH, FRONT_PLACE) > 0.5f) tuft(col, FRONT, 0.45f, 0.9f, front = true)
            col += 4 + (noise(col, FRONT_GAP) * 4).toInt()
        }
        outline()
    }

    /** Three to five blades from about [col], fanning out; heights between [low] and [high] of the grass. */
    private fun tuft(col: Int, layer: Int, low: Float, high: Float, front: Boolean) {
        val salt = layer * 1000
        val count = 3 + (noise(col, salt + TUFT_COUNT) * 3).toInt()
        val tallest = height(low, high, noise(col, salt + TUFT_HEIGHT))
        val dry = layer == MIDDLE && noise(col, DRY_PLACE) < DRY_SHARE
        for (k in 0 until count) {
            val side = k - count / 2
            val base = col + (if (side < 0) -1 else 1) * (abs(side) + 1) / 2
            val h = (tallest * (1f - 0.17f * abs(side) - 0.12f * noise(col + k, salt + BLADE_HEIGHT))).roundToInt().coerceAtLeast(1)
            val bend = side * (0.7f + 0.6f * noise(col + k, salt + BLADE_LEAN)) + 0.4f * (noise(col, salt + TUFT_LEAN) - 0.5f)
            blade(base, h, lean(h, bend), if (dry) DRY_LAYER else layer, front, thick = side == 0)
        }
    }

    /**
     * A blade standing in [base], [h] pixels tall, its tip [lean] pixels to the side (curving more
     * towards the tip); two pixels wide near the ground if [thick]. Shaded by [layer] (see [shade]).
     */
    private fun blade(base: Int, h: Int, lean: Int, layer: Int, front: Boolean, thick: Boolean) {
        for (r in 0 until minOf(h, rows)) {
            val t = if (h <= 1) 0f else r / (h - 1f)
            val x = base + (lean * t * t).roundToInt()
            val slot = shade(layer, r, h)
            put(x, r, slot, front)
            if (thick && r < maxOf(1, h / 4)) put(x + 1, r, slot, front)
        }
    }

    /** The colour slot of a pixel [r] rows up a blade [h] tall in [layer]: dark at the ground, light at the tip. */
    private fun shade(layer: Int, r: Int, h: Int): Int {
        val root = r < maxOf(1, h / 4)
        val tip = h >= 3 && r == h - 1
        return when (layer) {
            BACK -> if (r == 0) SHADOW else BACK_GREEN
            MIDDLE -> when { r == 0 -> SHADOW; root -> BACK_GREEN; tip -> LIGHT; else -> MID_GREEN }
            DRY_LAYER -> when { r == 0 -> SHADOW; root -> MID_GREEN; tip -> DRY_TIP; else -> DRY }
            else -> when { r == 0 -> BACK_GREEN; root -> MID_GREEN; tip -> HIGHLIGHT; else -> LIGHT }
        }
    }

    private fun put(col: Int, row: Int, slot: Int, front: Boolean) {
        if (!inside(col, row)) return
        cells[row * columns + col] = slot.toByte()
        // A later blade in front of an earlier one hides it; only a front blade stands before an animal.
        fronts[row * columns + col] = if (front) slot.toByte() else 0
    }

    /** The outline: every empty pixel beside a blade (left, right or above it) becomes the dark outline. */
    private fun outline() {
        val grass = cells.copyOf()
        val front = fronts.copyOf()
        fun filled(a: ByteArray, c: Int, r: Int) = inside(c, r) && a[r * columns + c] != 0.toByte()
        for (r in 0 until rows) for (c in 0 until columns) {
            if (filled(grass, c, r)) continue
            if (filled(grass, c - 1, r) || filled(grass, c + 1, r) || filled(grass, c, r - 1)) {
                cells[r * columns + c] = OUTLINE.toByte()
                if (filled(front, c - 1, r) || filled(front, c + 1, r) || filled(front, c, r - 1)) {
                    fronts[r * columns + c] = OUTLINE.toByte()
                }
            }
        }
    }

    private fun inside(col: Int, row: Int) = col in 0 until columns && row in 0 until rows

    /** A blade height between [low] and [high] of the grass, by [n] (0..1), at least one pixel. */
    private fun height(low: Float, high: Float, n: Float) = (rows * (low + (high - low) * n)).roundToInt().coerceIn(1, rows)

    /** How far the tip of a blade [h] tall leans, for a bend of [bend] (in pixels), kept so the blade stays joined up. */
    private fun lean(h: Int, bend: Float) = bend.roundToInt().coerceIn(-(h - 1) / 2, (h - 1) / 2)

    companion object {
        /** Colour slots (0: no pixel), darkest to lightest, then the dry tuft's two. */
        const val OUTLINE = 1
        const val SHADOW = 2
        const val BACK_GREEN = 3
        const val MID_GREEN = 4
        const val LIGHT = 5
        const val HIGHLIGHT = 6
        const val DRY = 7
        const val DRY_TIP = 8
        const val SLOTS = 9

        /** Layers. */
        private const val BACK = 0
        private const val MIDDLE = 1
        private const val FRONT = 2
        private const val DRY_LAYER = 3

        /** Hash salts. */
        private const val BACK_HEIGHT = 11
        private const val BACK_LEAN = 13
        private const val MIDDLE_GAP = 17
        private const val FRONT_GAP = 19
        private const val FRONT_PLACE = 23
        private const val TUFT_COUNT = 29
        private const val TUFT_HEIGHT = 31
        private const val TUFT_LEAN = 37
        private const val BLADE_HEIGHT = 41
        private const val BLADE_LEAN = 43
        private const val DRY_PLACE = 47

        /** About how many columns a stretch with (or without) grass in front of the animals spans. */
        private const val FRONT_STRETCH = 9f
        /** The share of middle tufts that are dry. */
        private const val DRY_SHARE = 0.12f

        /** The greens by slot (index 0 unused), for a light keyboard or a dark one (before softening, see Grass). */
        fun palette(light: Boolean): IntArray = if (light) {
            intArrayOf(0, 0xFF27482A.toInt(), 0xFF3F7337.toInt(), 0xFF4F8A43.toInt(), 0xFF64A350.toInt(), 0xFF80BC64.toInt(),
                0xFFA9D788.toInt(), 0xFFA3A24E.toInt(), 0xFFC9C477.toInt())
        } else {
            intArrayOf(0, 0xFF13251A.toInt(), 0xFF1D3823.toInt(), 0xFF27492D.toInt(), 0xFF335E37.toInt(), 0xFF467A45.toInt(),
                0xFF66A05A.toInt(), 0xFF6C6E3A.toInt(), 0xFF8E8D52.toInt())
        }

        /** A smooth wave through random values every [period] columns, 0..1. */
        fun wave(col: Int, period: Float, salt: Int): Float {
            val at = col / period
            val i = floor(at).toInt()
            val t = (at - i).let { it * it * (3 - 2 * it) }
            return noise(i, salt) * (1 - t) + noise(i + 1, salt) * t
        }

        /** A number in 0..1 that looks random but is always the same for the same [i] and [salt]. */
        fun noise(i: Int, salt: Int): Float {
            var h = i * 374761393 + salt * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }
    }
}

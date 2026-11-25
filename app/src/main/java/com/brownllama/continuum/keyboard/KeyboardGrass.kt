// The grass under the keys: pixel art in the same pixels as the visiting animals, who walk on it.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.brownllama.continuum.prediction.Mode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel-art grass, in the same chunky pixels as the animals ([unit] screen pixels each, on the same
 * grid, so their pixels line up). It stands on the bottom of the keyboard's window, which from
 * Android 15 is the bottom of the screen, behind Android's gesture handle or navigation buttons,
 * and grows up to the keys. The setting "Space below keyboard" sets how far up the keys are, so
 * how tall the grass is; at 0 there is none.
 *
 * Every column of pixels is one blade. Its height comes from a slow wave (patches of taller and
 * shorter grass) plus a hash of the column (so neighbours differ, as blades do); now and then its
 * tip bends over onto the next column. A blade is dark at the root, one of two greens along the
 * stem, and light at the tip. It is drawn once into a bitmap for its size and colours, so a frame
 * only copies that bitmap; nothing moves.
 *
 * Depth: in some stretches of the grass ([front]) the blades stand in front of a passing animal,
 * their lower part drawn again over its legs ([drawFront]); elsewhere it walks in front of them.
 */
internal class Grass(private val unit: Float) {
    private var image: Bitmap? = null
    private var builtLight = false
    private var builtBackground = 0
    /** No anti-aliasing: hard pixel edges, like the animals. */
    private val paint = Paint()

    // The blades of the grass last built: each column's own height (after a bent tip moved on), and
    // whether it stands in front of an animal; the greens; the size.
    private var heights = IntArray(0)
    private var front = BooleanArray(0)
    private var colors = IntArray(0)
    private var builtWidth = 0
    private var builtHeight = 0

    /** The size of the bitmap last drawn, for tests (0 × 0 before the first). */
    internal val imageWidth get() = image?.width ?: 0
    internal val imageHeight get() = image?.height ?: 0

    /** Whether the blade in [col] stands in front of a passing animal (for tests). */
    internal fun inFront(col: Int) = col in front.indices && front[col]

    /** The colour of [slot] (see [grassColors]) in the grass last built. */
    internal fun color(slot: Int) = colors[slot]

    /** The flowers' petal and centre colours by kind, for the keyboard the grass was last built for. */
    internal var petals = IntArray(0)
        private set
    internal var centres = IntArray(0)
        private set

    /** Draws the grass [width] × [height] px with its top at [top], in the greens for a [light] or dark [background]. */
    fun draw(c: Canvas, width: Int, top: Float, height: Int, light: Boolean, background: Int) {
        if (width <= 0 || height <= 0) return
        c.drawBitmap(imageFor(width, height, light, background), 0f, top, null)
    }

    /**
     * Draws again, over an animal whose sprite covers columns [fromCol]..[toCol], the lower [capRows]
     * pixels of the blades there that stand in front of it ([front]), except where a flower of
     * [meadow] grows. The grass must have been drawn ([draw]) with its top at [top].
     */
    fun drawFront(c: Canvas, top: Float, fromCol: Int, toCol: Int, capRows: Int, meadow: Meadow) {
        if (image == null) return
        val bottom = top + builtHeight
        for (col in maxOf(0, fromCol)..minOf(heights.size - 1, toCol)) {
            if (front[col] && !meadow.covers(col)) blade(c, col, heights[col], bottom, capRows)
        }
    }

    private fun imageFor(width: Int, height: Int, light: Boolean, background: Int): Bitmap {
        image?.takeIf {
            it.width == width && it.height == height && builtLight == light && builtBackground == background
        }?.let { return it }
        colors = grassColors(light, background)
        petals = petalColors(light, background)
        centres = centreColors(light, background)
        val bmp = createBitmap(width, height)
        val c = Canvas(bmp)
        builtWidth = width
        builtHeight = height
        val rows = ceil(height / unit).toInt()
        val columns = ceil(width / unit).toInt()
        heights = IntArray(columns) { bladeHeight(it, rows) }
        front = BooleanArray(columns) { wave(it, FRONT_COLUMNS, FRONT) > 0.5f && noise(it, FRONT + 1) < 0.8f }
        val bottom = height.toFloat()
        for (col in 0 until columns) {
            val h = heights[col]
            // Bent over: the top pixel sits on the next column, where that blade is lower.
            if (h >= 4 && noise(col, BEND) < BEND_SHARE) {
                val next = col + if (noise(col, BEND + 1) < 0.5f) -1 else 1
                if (next in 0 until columns && bladeHeight(next, rows) < h - 1) {
                    heights[col] = h - 1
                    pixels(c, next, h - 1, 1, TIP, bottom)
                }
            }
            blade(c, col, heights[col], bottom)
        }
        image = bmp
        builtLight = light
        builtBackground = background
        return bmp
    }

    /** The blade in [col], [h] pixels tall on the ground at [bottom], only its lowest [cap] pixels. */
    private fun blade(c: Canvas, col: Int, h: Int, bottom: Float, cap: Int = Int.MAX_VALUE) {
        val root = maxOf(1, h / 4)
        val tip = if (h >= 3) 1 else 0
        pixels(c, col, 0, root, SHADOW, bottom, cap)
        pixels(c, col, root, h - root - tip, if (noise(col, SHADE) < 0.5f) STEM else STEM_LIGHT, bottom, cap)
        pixels(c, col, h - tip, tip, TIP, bottom, cap)
    }

    /** [count] pixels of colour [slot] in column [col] from [row] up (0: the ground row), none from [cap] up. */
    private fun pixels(c: Canvas, col: Int, row: Int, count: Int, slot: Int, bottom: Float, cap: Int = Int.MAX_VALUE) {
        val n = min(count, cap - row)
        if (n <= 0) return
        paint.color = colors[slot]
        val left = col * unit
        c.drawRect(left, bottom - (row + n) * unit, min(builtWidth.toFloat(), left + unit), bottom - row * unit, paint)
    }

    /** How many rows tall the blade in [col] is: the patch it is in, and a little of its own. */
    private fun bladeHeight(col: Int, rows: Int): Int {
        val share = 0.3f + 0.3f * wave(col, PATCH_COLUMNS, PATCH) + 0.4f * noise(col, HEIGHT)
        return (rows * share).roundToInt().coerceIn(1, rows)
    }

    companion object {
        /** Colour slots: the root, the stem's two greens, the tip. */
        const val SHADOW = 0
        const val STEM = 1
        const val STEM_LIGHT = 2
        const val TIP = 3

        /** Hash salts, one per decision. */
        private const val HEIGHT = 11
        private const val PATCH = 23
        private const val SHADE = 37
        private const val BEND = 51
        private const val FRONT = 67

        /** About how wide (in columns) a patch of taller or shorter grass is, and a stretch in front of the animals or behind. */
        private const val PATCH_COLUMNS = 7f
        private const val FRONT_COLUMNS = 9f
        /** The share of tall blades whose tip bends over. */
        private const val BEND_SHARE = 0.22f

        /**
         * The greens, by colour slot. Under a dark keyboard they are deep and pulled towards its
         * background, so the grass stays calm; under a light one, fresh but soft.
         */
        fun grassColors(light: Boolean, background: Int): IntArray =
            if (light) {
                intArrayOf(0xFF4C8741.toInt(), 0xFF64A050.toInt(), 0xFF78B45E.toInt(), 0xFFA3D07D.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.1f) }.toIntArray()
            } else {
                intArrayOf(0xFF1F3B24.toInt(), 0xFF2D5631.toInt(), 0xFF386A3A.toInt(), 0xFF5B9150.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.25f) }.toIntArray()
            }

        /** The flowers' petals and centres, by kind (white, yellow, pink, lilac), a little softer under a dark keyboard. */
        fun petalColors(light: Boolean, background: Int): IntArray =
            soften(intArrayOf(0xFFF4F1E8.toInt(), 0xFFF2CB4C.toInt(), 0xFFEE8FB0.toInt(), 0xFFB8A1E6.toInt()), light, background)

        fun centreColors(light: Boolean, background: Int): IntArray =
            soften(intArrayOf(0xFFF2C14E.toInt(), 0xFFA8692E.toInt(), 0xFFF6D75F.toInt(), 0xFFF6D75F.toInt()), light, background)

        private fun soften(colors: IntArray, light: Boolean, background: Int) =
            if (light) colors else colors.map { ColorUtils.blendARGB(it, background, 0.18f) }.toIntArray()

        /** A smooth wave through random values every [period] columns, 0..1. */
        private fun wave(col: Int, period: Float, salt: Int): Float {
            val at = col / period
            val i = floor(at).toInt()
            val t = (at - i).let { it * it * (3 - 2 * it) }
            return noise(i, salt) * (1 - t) + noise(i + 1, salt) * t
        }

        /** A number in 0..1 that looks random but is always the same for the same [i] and [salt]. */
        private fun noise(i: Int, salt: Int): Float {
            var h = i * 374761393 + salt * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }
    }
}

/** The bottom of the grass, in keyboard coordinates: the bottom of the view (from Android 15, of the screen). */
internal val KeyboardView.grassBottom: Float get() = height - overlayH - gifHeaderH

/** The top of the grass, in keyboard coordinates: the bottom of the keys. */
internal val KeyboardView.grassTop: Float get() = keyboardHeight

/**
 * How tall the grass is, in px: from the bottom of the screen up to the keys, so the setting "Space
 * below keyboard" plus Android's bar under it. 0 (no grass) when the setting is 0.
 */
internal val KeyboardView.grassHeight: Int
    get() = if (bottomSpaceDp <= 0f) 0 else (grassBottom - grassTop).roundToInt().coerceAtLeast(0)

/**
 * The grass under the keys and its flowers. Its top is placed so that its bottom is the view's last
 * pixel: whole screen pixels, so its own pixels stay crisp.
 */
internal fun KeyboardView.drawGrass(c: Canvas) {
    val h = grassHeight
    if (h <= 0) return
    grass.draw(c, width, grassBottom - h, h, theme.isLight, theme.background)
    meadow.fit(ceil(width / spritePx).toInt(), ceil(h / spritePx).toInt(), animalFeetRows())
    meadow.draw(c, fill, spritePx, grassBottom, grass.petals, grass.centres, grass.color(Grass.STEM), grass.color(Grass.TIP))
}

/** The blades that stand in front of a visiting animal, drawn again over its legs (after it). */
internal fun KeyboardView.drawGrassFront(c: Canvas) {
    val h = grassHeight
    if (h <= 0 || !animal.visiting) return
    val from = animal.gridLeft
    grass.drawFront(c, grassBottom - h, from, from + Species.W - 1, animalFeetRows() + FRONT_ROWS, meadow)
}

/** How many sprite pixels above the bottom of the grass the animals walk, so there is grass under their feet. */
internal const val ANIMAL_FEET_ROWS = 3

/** How many pixels of a blade in front of an animal stand over it, from the ground: up past its feet and legs. */
private const val FRONT_ROWS = 4

/** How many sprite pixels above the ground of the grass the animals' feet are: [ANIMAL_FEET_ROWS], or half in grass shorter than twice that. */
internal fun KeyboardView.animalFeetRows(): Int {
    val h = grassHeight
    return if (h >= 2 * ANIMAL_FEET_ROWS * spritePx) ANIMAL_FEET_ROWS else (h / spritePx / 2).toInt()
}

/**
 * Where a visiting animal's feet are (keyboard coordinates). With grass, on it: [animalFeetRows]
 * pixels above its bottom, on its pixel grid. Without, along the letter row in mini mode, or along
 * one of the four rows, picked at random, in full mode.
 */
internal fun KeyboardView.animalFeet(): Float {
    if (grassHeight > 0) return grassBottom - animalFeetRows() * spritePx
    return if (mode == Mode.MINI) {
        letterRect.bottom - dp(4f)
    } else {
        barH + fullRowH * (1 + kotlin.random.Random.nextInt(4)) - dp(3f)
    }
}

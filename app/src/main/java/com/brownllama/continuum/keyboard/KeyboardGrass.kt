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
 */
internal class Grass(private val unit: Float) {
    private var image: Bitmap? = null
    private var builtLight = false
    private var builtBackground = 0
    /** No anti-aliasing: hard pixel edges, like the animals. */
    private val paint = Paint()

    /** The size of the bitmap last drawn, for tests (0 × 0 before the first). */
    internal val imageWidth get() = image?.width ?: 0
    internal val imageHeight get() = image?.height ?: 0

    /** Draws the grass [width] × [height] px with its top at [top], in the greens for a [light] or dark [background]. */
    fun draw(c: Canvas, width: Int, top: Float, height: Int, light: Boolean, background: Int) {
        if (width <= 0 || height <= 0) return
        c.drawBitmap(imageFor(width, height, light, background), 0f, top, null)
    }

    private fun imageFor(width: Int, height: Int, light: Boolean, background: Int): Bitmap {
        image?.takeIf {
            it.width == width && it.height == height && builtLight == light && builtBackground == background
        }?.let { return it }
        val bmp = createBitmap(width, height)
        paintBlades(Canvas(bmp), width, height, colors(light, background))
        image = bmp
        builtLight = light
        builtBackground = background
        return bmp
    }

    /** Every blade, columns of [unit] from the left, rows of [unit] from the bottom up. */
    private fun paintBlades(c: Canvas, width: Int, height: Int, colors: IntArray) {
        val rows = ceil(height / unit).toInt()
        val columns = ceil(width / unit).toInt()
        val heights = IntArray(columns) { bladeHeight(it, rows) }

        /** The pixel in [col], [row] (0: the bottom row), [count] rows tall, in [color]. */
        fun pixels(col: Int, row: Int, count: Int, color: Int) {
            if (count <= 0) return
            paint.color = color
            val left = col * unit
            c.drawRect(left, height - (row + count) * unit, min(width.toFloat(), left + unit), height - row * unit, paint)
        }

        for (col in 0 until columns) {
            var h = heights[col]
            // Bent over: the top pixel sits on the next column, where that blade is lower.
            if (h >= 4 && noise(col, BEND) < BEND_SHARE) {
                val next = col + if (noise(col, BEND + 1) < 0.5f) -1 else 1
                if (next in 0 until columns && heights[next] < h - 1) {
                    h--
                    pixels(next, h, 1, colors[TIP])
                }
            }
            val root = maxOf(1, h / 4)
            val tip = if (h >= 3) 1 else 0
            pixels(col, 0, root, colors[SHADOW])
            pixels(col, root, h - root - tip, colors[if (noise(col, SHADE) < 0.5f) STEM else STEM_LIGHT])
            pixels(col, h - tip, tip, colors[TIP])
        }
    }

    /** How many rows tall the blade in [col] is: the patch it is in, and a little of its own. */
    private fun bladeHeight(col: Int, rows: Int): Int {
        val at = col / PATCH_COLUMNS
        val i = floor(at).toInt()
        val t = (at - i).let { it * it * (3 - 2 * it) }
        val patch = noise(i, PATCH) * (1 - t) + noise(i + 1, PATCH) * t
        val share = 0.3f + 0.3f * patch + 0.4f * noise(col, HEIGHT)
        return (rows * share).roundToInt().coerceIn(1, rows)
    }

    private companion object {
        /** Colour slots: the root, the stem's two greens, the tip. */
        const val SHADOW = 0
        const val STEM = 1
        const val STEM_LIGHT = 2
        const val TIP = 3

        /** Hash salts, one per decision. */
        const val HEIGHT = 11
        const val PATCH = 23
        const val SHADE = 37
        const val BEND = 51

        /** About how wide (in columns) a patch of taller or shorter grass is. */
        const val PATCH_COLUMNS = 7f
        /** The share of tall blades whose tip bends over. */
        const val BEND_SHARE = 0.22f

        /**
         * The greens, by colour slot. Under a dark keyboard they are deep and pulled towards its
         * background, so the grass stays calm; under a light one, fresh but soft.
         */
        fun colors(light: Boolean, background: Int): IntArray =
            if (light) {
                intArrayOf(0xFF4C8741.toInt(), 0xFF64A050.toInt(), 0xFF78B45E.toInt(), 0xFFA3D07D.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.1f) }.toIntArray()
            } else {
                intArrayOf(0xFF1F3B24.toInt(), 0xFF2D5631.toInt(), 0xFF386A3A.toInt(), 0xFF5B9150.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.25f) }.toIntArray()
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
 * The grass under the keys. Its top is placed so that its bottom is the view's last pixel: whole
 * screen pixels, so its own pixels stay crisp.
 */
internal fun KeyboardView.drawGrass(c: Canvas) {
    val h = grassHeight
    if (h <= 0) return
    grass.draw(c, width, grassBottom - h, h, theme.isLight, theme.background)
}

/** How many sprite pixels above the bottom of the grass the animals walk, so there is grass under their feet. */
internal const val ANIMAL_FEET_ROWS = 3

/**
 * Where a visiting animal's feet are (keyboard coordinates). With grass, on it: [ANIMAL_FEET_ROWS]
 * pixels above its bottom, on its pixel grid (in grass shorter than twice that, half its height).
 * Without, along the letter row in mini mode, or along one of the four rows, picked at random, in
 * full mode.
 */
internal fun KeyboardView.animalFeet(): Float {
    val h = grassHeight
    if (h > 0) {
        val rows = if (h >= 2 * ANIMAL_FEET_ROWS * spritePx) ANIMAL_FEET_ROWS else (h / spritePx / 2).toInt()
        return grassBottom - rows * spritePx
    }
    return if (mode == Mode.MINI) {
        letterRect.bottom - dp(4f)
    } else {
        barH + fullRowH * (1 + kotlin.random.Random.nextInt(4)) - dp(3f)
    }
}

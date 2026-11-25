// The grass under the keys: pixel art in the same pixels as the visiting animals, who walk on it.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.brownllama.continuum.prediction.Mode
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The grass under the keys, drawn: pixel art in the same chunky pixels as the animals ([unit]
 * screen pixels each, on the same grid, so their pixels line up), laid out by [GrassField]. It
 * stands on the bottom of the keyboard's window, which from Android 15 is the bottom of the screen,
 * behind Android's gesture handle or navigation buttons, and grows up to the keys. The setting
 * "Space below keyboard" sets how far up the keys are, so how tall the grass is; at 0 there is none.
 *
 * It is drawn once into a bitmap for its size and colours, so a frame only copies that bitmap;
 * nothing moves. The tufts that stand in front of a passing animal are drawn again over it
 * ([drawFront]), so it walks through the grass there, its body hidden behind them.
 */
internal class Grass(private val unit: Float) {
    private var image: Bitmap? = null
    private var builtLight = false
    private var builtBackground = 0
    /** No anti-aliasing: hard pixel edges, like the animals. */
    private val paint = Paint()
    private var field = GrassField(0, 0)
    private var colors = IntArray(GrassField.SLOTS)
    private var builtWidth = 0
    private var builtHeight = 0

    /** The size of the bitmap last drawn, for tests (0 × 0 before the first). */
    internal val imageWidth get() = image?.width ?: 0
    internal val imageHeight get() = image?.height ?: 0

    /** Whether any grass in column [col] stands in front of a passing animal (for tests). */
    internal fun inFront(col: Int) = field.hasFront(col)

    /** The flowers' stems and leaves, in the grass's own greens. */
    internal val stemColor get() = colors[GrassField.MID_GREEN]
    internal val leafColor get() = colors[GrassField.LIGHT]

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
     * Draws again, over an animal whose sprite covers columns [fromCol]..[toCol], the grass there
     * that stands in front of it, except where a flower of [meadow] grows. The grass must have been
     * drawn ([draw]) with its top at [top].
     */
    fun drawFront(c: Canvas, top: Float, fromCol: Int, toCol: Int, meadow: Meadow) {
        if (image == null) return
        val bottom = top + builtHeight
        for (col in maxOf(0, fromCol)..minOf(field.columns - 1, toCol)) {
            if (!meadow.covers(col)) column(c, col, bottom) { field.frontAt(col, it) }
        }
    }

    private fun imageFor(width: Int, height: Int, light: Boolean, background: Int): Bitmap {
        image?.takeIf {
            it.width == width && it.height == height && builtLight == light && builtBackground == background
        }?.let { return it }
        val rows = ceil(height / unit).toInt()
        val columns = ceil(width / unit).toInt()
        if (field.columns != columns || field.rows != rows) field = GrassField(columns, rows)
        colors = grassColors(light, background)
        petals = petalColors(light, background)
        centres = centreColors(light, background)
        val bmp = createBitmap(width, height)
        builtWidth = width
        builtHeight = height
        val c = Canvas(bmp)
        for (col in 0 until columns) column(c, col, height.toFloat()) { field.at(col, it) }
        image = bmp
        builtLight = light
        builtBackground = background
        return bmp
    }

    /** Column [col] of pixels on the ground at [bottom], by the slot [slotAt] gives each row (0: none), a rectangle per run. */
    private inline fun column(c: Canvas, col: Int, bottom: Float, slotAt: (Int) -> Int) {
        val left = col * unit
        val right = min(builtWidth.toFloat(), left + unit)
        var r = 0
        while (r < field.rows) {
            val slot = slotAt(r)
            var end = r + 1
            while (end < field.rows && slotAt(end) == slot) end++
            if (slot != 0) {
                paint.color = colors[slot]
                c.drawRect(left, bottom - end * unit, right, bottom - r * unit, paint)
            }
            r = end
        }
    }

    companion object {
        /** The greens by slot: under a dark keyboard pulled a little towards its background, so the grass stays calm. */
        fun grassColors(light: Boolean, background: Int): IntArray =
            GrassField.palette(light).map { if (it == 0) 0 else ColorUtils.blendARGB(it, background, if (light) 0.08f else 0.2f) }
                .toIntArray()

        /** The flowers' petals and centres, by kind (white, yellow, pink, lilac), a little softer under a dark keyboard. */
        fun petalColors(light: Boolean, background: Int): IntArray =
            soften(intArrayOf(0xFFF4F1E8.toInt(), 0xFFF2CB4C.toInt(), 0xFFEE8FB0.toInt(), 0xFFB8A1E6.toInt()), light, background)

        fun centreColors(light: Boolean, background: Int): IntArray =
            soften(intArrayOf(0xFFF2C14E.toInt(), 0xFFA8692E.toInt(), 0xFFF6D75F.toInt(), 0xFFF6D75F.toInt()), light, background)

        private fun soften(colors: IntArray, light: Boolean, background: Int) =
            if (light) colors else colors.map { ColorUtils.blendARGB(it, background, 0.18f) }.toIntArray()
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
    meadow.draw(c, fill, spritePx, grassBottom, grass.petals, grass.centres, grass.stemColor, grass.leafColor)
}

/** The tufts that stand in front of a visiting animal, drawn again over it (after it), so it walks through them. */
internal fun KeyboardView.drawGrassFront(c: Canvas) {
    val h = grassHeight
    if (h <= 0 || !animal.visiting) return
    val from = animal.gridLeft
    grass.drawFront(c, grassBottom - h, from, from + Species.W - 1, meadow)
}

/** How many sprite pixels above the bottom of the grass the animals walk, so there is grass under their feet. */
internal const val ANIMAL_FEET_ROWS = 3

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

// The grass under the keys: pixel art in the same pixels as the visiting animals, who walk on it.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.brownllama.continuum.R
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
internal class Grass(private val unit: Float) : Sway {
    /** When the wind is sampled (uptime ms), or -1 for none: the grass stands still. */
    internal var windAt = -1L
    private val src = Rect()
    private val dst = RectF()
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

    /** The tree's colours: the grass's greens, a brown trunk, red apples and the grass's outline. */
    internal fun treePalette(): Tree.Palette = treePal ?: Tree.Palette(
        outline = colors[GrassField.OUTLINE], dark = colors[GrassField.BACK_GREEN], mid = colors[GrassField.MID_GREEN],
        light = colors[GrassField.LIGHT], highlight = colors[GrassField.HIGHLIGHT],
        trunk = 0xFF8A5A33.toInt(), trunkDark = 0xFF5E3B20.toInt(), trunkLight = 0xFFB07A4A.toInt(), apple = 0xFFD6303F.toInt(),
    ).also { treePal = it }
    /** Kept (and dropped when the colours change), so a frame allocates nothing for it. */
    private var treePal: Tree.Palette? = null

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
        val bmp = imageFor(width, height, light, background)
        if (!blowing) c.drawBitmap(bmp, 0f, top, null) else drawSwaying(c, bmp, top)
    }

    /** How far the wind moves the pixel in [col], [row] (0: the ground), in grass pixels; 0 when still. */
    override fun at(col: Int, row: Int): Int =
        if (windAt < 0) 0 else Wind.lean(col, row, field.columns, field.rows, windAt)

    /** Whether the wind is moving the grass now. */
    private val blowing get() = windAt >= 0 && Wind.blowing(windAt)

    /**
     * The grass with its rows (and each stretch of [Wind.SEGMENT] columns) moved by the wind: the
     * same bitmap drawn in strips, each read from a little to the side, so nothing is allocated or
     * rebuilt. The strips tile the width, so no gap opens, whichever way a neighbour leans.
     */
    private fun drawSwaying(c: Canvas, bmp: Bitmap, top: Float) {
        val w = bmp.width
        val segW = Wind.SEGMENT * unit
        var seg = 0
        while (seg * segW < w) {
            val x0 = seg * segW
            val x1 = min(w.toFloat(), x0 + segW)
            val col = seg * Wind.SEGMENT
            var r = field.rows - 1
            while (r >= 0) {
                val shift = at(col, r)
                var low = r
                while (low > 0 && at(col, low - 1) == shift) low--
                // Rows low..r, measured up from the bottom of the bitmap.
                val yTop = maxOf(0f, bmp.height - (r + 1) * unit)
                val yBottom = bmp.height - low * unit
                var sx0 = x0 - shift * unit
                var sx1 = x1 - shift * unit
                var dx0 = x0
                var dx1 = x1
                if (sx0 < 0f) { dx0 -= sx0; sx0 = 0f }
                if (sx1 > w) { dx1 -= sx1 - w; sx1 = w.toFloat() }
                if (dx1 > dx0 && yBottom > yTop) {
                    src.set(sx0.roundToInt(), yTop.roundToInt(), sx1.roundToInt(), yBottom.roundToInt())
                    dst.set(dx0, top + yTop, dx1, top + yBottom)
                    c.drawBitmap(bmp, src, dst, null)
                }
                r = low - 1
            }
            seg++
        }
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
            if (meadow.covers(col)) continue
            if (!blowing) {
                column(c, col, bottom) { field.frontAt(col, it) }
                continue
            }
            // In the wind these tufts lean with the rest of the grass, a pixel at a time.
            for (r in 0 until field.rows) {
                val slot = field.frontAt(col, r)
                if (slot == 0) continue
                paint.color = colors[slot]
                val left = (col + at(col, r)) * unit
                c.drawRect(left, bottom - (r + 1) * unit, min(builtWidth.toFloat(), left + unit), bottom - r * unit, paint)
            }
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
        treePal = null
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
    fitTree(h)
    if (h <= 0) return
    // The wind blows only while the keyboard is showing and animations are on; the system's battery saver stops it too.
    val now = SystemClock.uptimeMillis()
    val wind = animating && isShown && !(context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false)
    grass.windAt = if (wind) now else -1L
    grass.draw(c, width, grassBottom - h, h, theme.isLight, theme.background)
    meadow.fit(ceil(width / spritePx).toInt(), ceil(h / spritePx).toInt(), animalFeetRows())
    meadow.draw(c, fill, spritePx, grassBottom, grass.petals, grass.centres, grass.stemColor, grass.leafColor, if (wind) grass else null)
    if (wind) scheduleWind(Wind.nextIn(now), h)
}

/**
 * Where the tree stands: at the right of the grass, its base just above Android's gesture bar (swipes
 * there are Android's), rising [TREE_HEIGHT_DP] over the keys (hiding them a little) but never into
 * the suggestion bar. None without grass, or where the grass leaves no ground for it above the bar.
 */
internal fun KeyboardView.fitTree(grassH: Int = grassHeight) {
    val base = treeBase()
    if (grassH <= 0 || base < grassTop + 2 * spritePx) return tree.fit(0f, 0f, 0f, 0f, 0)
    tree.fit(width.toFloat(), base, barH + dpu(4f), dpu(TREE_RIGHT_GAP_DP), (dpu(TREE_HEIGHT_DP) / spritePx).roundToInt())
}

/** The bottom of the tree, and of the part of the grass a finger can pick it up from (keyboard coordinates). */
internal fun KeyboardView.treeBase() = grassBottom - systemBottom - dpu(GESTURE_BAR_MARGIN_DP)

/**
 * The tree, over the keys and before the animals. It redraws only while it shakes (after a single tap);
 * the rest of the time it is part of the frame like anything else.
 */
internal fun KeyboardView.drawTree(c: Canvas) {
    if (!tree.present || panel.big || gifSearching) return
    val next = tree.draw(c, SystemClock.uptimeMillis(), grass.treePalette())
    if (next >= 0) postInvalidateOnAnimation()
}

/**
 * The cursor mode is night. A deep blue veil hides the suggestion bar and the keys all but a little
 * (and tints the grass and everything in it like dusk), a few stars come out, the tree glows, and
 * fireflies drift about; sliding a finger, in any direction, brings them round it in a swirl and the
 * finger leaves sparks behind. It is drawn over everything. (How to use it is in the app's Guide.)
 */
internal fun KeyboardView.drawCursorMode(c: Canvas) {
    if (!cursorMode || panel.big || gifSearching) return
    val now = SystemClock.uptimeMillis()
    fill.color = ColorUtils.setAlphaComponent(NIGHT, CURSOR_VEIL_ALPHA)
    c.drawRect(0f, 0f, width.toFloat(), keyboardHeight, fill)
    fill.color = ColorUtils.setAlphaComponent(NIGHT, DUSK_ALPHA)
    c.drawRect(0f, keyboardHeight, width.toFloat(), grassBottom, fill)
    // Stars over the keys, in fixed places, a few twinkling.
    val u = spritePx
    for (k in 0 until STARS) {
        val sx = kotlin.math.floor(GrassField.noise(k, 301) * width / u) * u
        val sy = kotlin.math.floor(GrassField.noise(k, 302) * keyboardHeight * 0.92f / u) * u
        val bright = (k * 7 + now / 650) % 4L == 0L
        fill.color = ColorUtils.setAlphaComponent(0xFFFFFF, if (bright) 220 else 90)
        c.drawRect(sx, sy, sx + u, sy + u, fill)
    }
    // The tree, glowing, above the night; then the fireflies above that.
    drawTree(c)
    val down = pointers.values.any { it.area == Area.CURSOR }
    fireflies.step(now, down, cursorFingerX, cursorFingerY)
    fireflies.draw(c, fill, now)
    // A frame at a time while it lasts (a few seconds at most); with the system's animations off, only as fingers move.
    if (animating) postInvalidateDelayed(FIREFLY_FRAME_MS)
}

/**
 * Redraws just the grass's strip after [delay] ms: one pending redraw at most (a new one replaces
 * the old), so typing, which draws often, cannot pile up redraws. Nothing is scheduled while still.
 */
private fun KeyboardView.scheduleWind(delay: Long, grassH: Int) {
    timer.removeCallbacks(windTick)
    windDirty.set(0, (overlayH + gifHeaderH + grassBottom - grassH).toInt(), width, height)
    timer.postDelayed(windTick, delay)
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

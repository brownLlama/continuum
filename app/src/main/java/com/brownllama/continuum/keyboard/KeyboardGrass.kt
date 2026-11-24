// The strip of grass under the keyboard, where the visiting animals walk.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Path
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-art grass, drawn in the same chunky pixels as the animals ([unit] px each): blades of grass
 * standing on the ground line. The blades are tufts of one to three pointed blades of every height up to
 * the grass height, with a lighter tip, stubble between them and a few tiny flowers. Below the ground line
 * (where Android's navigation bar is) the grass's own dark ground carries on, with lighter and darker
 * patches. Where things fall comes from a hash of the column, so the strip has no repeating pattern, and
 * is the same every time it is drawn. The shapes are paths built once for a size, so drawing it is a few
 * fills. [drawFront] draws the taller blades again, after an animal, so its feet stand in the grass.
 */
internal class Grass(private val unit: Float) {
    private val blades = Path()
    private val bases = Path()
    private val tips = Path()
    private val flowers = Path()
    private val tall = Path()
    private val tallTips = Path()
    private val lighter = Path()
    private val darker = Path()
    private var builtWidth = -1f
    private var builtRows = -1
    private var builtGround = -1

    /**
     * Draws the strip [top]..[bottom] (keyboard coordinates), [light] for a light keyboard: the blades
     * standing on the ground line [groundFrom] (measured from [top]; the grass height) and growing up to
     * [top] at the most, and the ground from that line down to [bottom].
     */
    fun draw(
        c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int,
        groundFrom: Float? = null,
    ) {
        val strip = bottom - top
        if (strip < 1f || width < 1f) return
        val ground = if (groundFrom != null) max(0, (groundFrom / unit).toInt()) else DEFAULT_GROUND_ROW
        build(width, ceil(strip / unit).toInt(), ground)
        val p = palette(light, background)
        c.withTranslation(0f, top) {
            clipRect(0f, 0f, width, strip)
            // The ground under the blades (under Android's bar), with lighter and darker patches.
            if (strip > ground * unit) {
                paint.color = p.ground
                drawRect(0f, ground * unit, width, strip, paint)
                paint.color = p.lighter
                drawPath(lighter, paint)
                paint.color = p.darker
                drawPath(darker, paint)
            }
            // No blades at all when the grass height is 0.
            if (ground == 0) return@withTranslation
            paint.color = p.blade
            drawPath(blades, paint)
            paint.color = p.base
            drawPath(bases, paint)
            paint.color = p.tip
            drawPath(tips, paint)
            paint.color = p.flower
            drawPath(flowers, paint)
        }
    }

    /** The taller blades again, over whatever stands in the grass. */
    fun drawFront(c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int) {
        if (bottom - top < 1f || width < 1f || builtWidth != width) return
        val p = palette(light, background)
        c.withTranslation(0f, top) {
            paint.color = p.blade
            drawPath(tall, paint)
            paint.color = p.tip
            drawPath(tallTips, paint)
        }
    }

    private fun build(width: Float, rows: Int, ground: Int) {
        if (width == builtWidth && rows == builtRows && ground == builtGround) return
        builtWidth = width
        builtRows = rows
        builtGround = ground
        for (p in listOf(blades, bases, tips, flowers, tall, tallTips, lighter, darker)) p.reset()
        val columns = ceil(width / unit).toInt()

        fun cell(path: Path, col: Int, row: Int, w: Int = 1, h: Int = 1) {
            val left = col * unit
            path.addRect(left, row * unit, min(width, left + w * unit), (row + h) * unit, Path.Direction.CW)
        }

        if (ground > 0) {
            // Blades stand on the ground line (row [ground]) and grow up, at most [ground] pixels tall:
            // tufts of one to three, the middle one tallest, with stubble between.
            val lowest = min(ground, max(2, ground * 2 / 5))
            fun blade(col: Int, h: Int, peak: Boolean) {
                cell(blades, col, ground - h, 1, h)
                cell(bases, col, ground - 1)
                if (h >= 3) cell(tips, col, ground - h)
                if (h >= TALL) {
                    cell(tall, col, ground - h, 1, h)
                    cell(tallTips, col, ground - h)
                }
                // The odd flower on top of a tall blade.
                if (peak && h >= TALL && hash(col, 7) % 14 == 0) cell(flowers, col, ground - h)
            }
            var i = 0
            while (i < columns) {
                if (hash(i, 1) % 5 == 0) {
                    val w = 1 + hash(i, 2) % 3
                    for (k in 0 until w) blade(i + k, 1 + hash(i + k, 3) % max(1, min(2, ground)), false)
                    i += w
                } else {
                    val peak = lowest + hash(i, 4) % (ground - lowest + 1)
                    val w = 1 + hash(i, 5) % 3
                    for (k in 0 until w) {
                        val mid = k == (w - 1) / 2
                        blade(i + k, if (mid) peak else max(1, peak - 1 - hash(i + k, 6) % (1 + peak / 4)), mid)
                    }
                    i += w
                }
            }
        }
        // The ground: lighter and darker patches, one to three pixels wide, scattered.
        for (row in ground until min(rows, ground + MAX_TEXTURE_ROWS)) {
            for (c in 0 until columns) {
                val h = hash(c, 300 + row)
                when {
                    h % 17 == 0 -> cell(lighter, c, row, 1 + h / 17 % 2)
                    h % 17 == 5 -> cell(darker, c, row, 1 + h / 17 % 3)
                }
            }
        }
    }

    private class Palette(
        val blade: Int, val tip: Int, val base: Int, val flower: Int,
        val ground: Int, val lighter: Int, val darker: Int,
    )

    private fun palette(light: Boolean, background: Int): Palette =
        if (light) {
            Palette(
                0xFF7DB566.toInt(), 0xFFA5CF8B.toInt(), 0xFF5F9A4C.toInt(), 0xFFF2EFE4.toInt(),
                0xFF5F9A4C.toInt(), 0xFF6BA657.toInt(), 0xFF538C42.toInt(),
            )
        } else {
            // Quiet and dim under a dark keyboard: deep greens, pulled towards the keyboard's own colour.
            fun calm(color: Int) = ColorUtils.blendARGB(color, background, 0.4f)
            Palette(
                calm(0xFF3F6A3A.toInt()), calm(0xFF4F7C47.toInt()), calm(0xFF2F5230.toInt()), calm(0xFF8E9A86.toInt()),
                calm(0xFF2F5230.toInt()), calm(0xFF38603A.toInt()), calm(0xFF274628.toInt()),
            )
        }

    private companion object {
        /** Where the ground starts, in pixels from the top, when not told. */
        const val DEFAULT_GROUND_ROW = 8
        /** Blades this tall or taller stand in front of an animal's feet. */
        const val TALL = 3
        /** The ground is textured this many rows down; below that it is plain. */
        const val MAX_TEXTURE_ROWS = 40

        /** A number that looks random but is always the same for the same [i] and [salt]. */
        fun hash(i: Int, salt: Int): Int {
            var x = i * -1640531535 + salt * -2048144789
            x = x xor (x ushr 15)
            x *= 0x2c1b3c6d
            x = x xor (x ushr 12)
            x *= 0x297a2d39
            x = x xor (x ushr 15)
            return x and 0x7fffffff
        }
    }
}

/** The bottom of the grass: the bottom of the view, in keyboard coordinates. */
internal fun KeyboardView.grassBottom() = height - overlayH - gifHeaderH

/** The grass under the keyboard (drawn before the keys and the animals). */
internal fun KeyboardView.drawGrass(c: Canvas) {
    // Down from the keys: the grass, [bottomSpaceDp] tall, then its dark ground, which carries on under
    // Android's bars. With the space at 0 nothing is drawn.
    if (bottomSpaceDp <= 0f) return
    val end = keyboardHeight + dpu(bottomSpaceDp) + systemBottom
    grass.draw(c, fill, width.toFloat(), keyboardHeight, minOf(end, grassBottom()), theme.isLight, theme.background, dpu(bottomSpaceDp))
}

/** The tallest blades over an animal's feet. */
internal fun KeyboardView.drawGrassFront(c: Canvas) {
    if (bottomSpaceDp <= 0f) return // no grass, no blades
    grass.drawFront(c, fill, width.toFloat(), keyboardHeight, grassBottom(), theme.isLight, theme.background)
}

/**
 * Android's "back" swipe starts at the left and right edges of the screen, where this keyboard's
 * own swipes (delete a word, space, the cursor on the grass) often begin too. Asks Android to leave
 * the keys and the grass alone. It honours at most 200 dp of height, so the lowest 200 dp is what is
 * asked for. Android's bottom swipes (home, switch app) can't be excluded: the keyboard keeps clear of
 * them instead (see [KeyboardView.systemBottom]).
 */
internal fun KeyboardView.updateGestureExclusion() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width == 0) return
    // The grass and the keys; the ground under it needs no gestures of its own.
    val bottom = (overlayH + gifHeaderH + keyboardHeight + dpu(bottomSpaceDp)).toInt().coerceAtMost(height)
    val top = max(overlayH + gifHeaderH, bottom - dpu(200f)).toInt()
    systemGestureExclusionRects = if (bottom > top) listOf(Rect(0, top, width, bottom)) else emptyList()
}

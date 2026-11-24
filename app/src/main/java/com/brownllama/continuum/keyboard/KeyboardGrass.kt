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
 * Pixel-art grass, drawn in the same chunky pixels as the animals ([unit] px each): tufts of
 * pointed blades of every height with a lighter tip, stubble between them, a few tiny flowers, and
 * a body of earth-green with lighter blades and darker specks scattered through it, and brown soil
 * underneath with a ragged edge where the roots reach into it, lighter clods, darker specks and the
 * odd pebble. Where things
 * fall comes from a hash of the column, so the strip has no repeating pattern, and is the same
 * every time it is drawn. The shapes are paths built once for a width, so drawing it is a few
 * fills. [drawFront] draws the tallest blades again, after an animal, so its feet stand in the grass.
 */
internal class Grass(private val unit: Float) {
    private val blades = Path()
    private val tips = Path()
    private val flowers = Path()
    private val inner = Path()
    private val specks = Path()
    private val tall = Path()
    private val tallTips = Path()
    private val roots = Path()
    private val clods = Path()
    private val dirt = Path()
    private val pebbles = Path()
    private var builtWidth = -1f
    private var builtRows = -1
    private var builtSoil = -1

    /** Where an animal's feet go, measured down from the top of the grass. */
    fun feetOffset(strip: Float) = min(FEET_ROWS * unit, strip)

    /** Draws the grass in a strip [top]..[bottom] (keyboard coordinates), [light] for a light keyboard. */
    fun draw(
        c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int,
        soilFrom: Float? = null, soil: Boolean = true,
    ) {
        val strip = bottom - top
        if (strip < 1f || width < 1f) return
        // The soil starts at [soilFrom] (measured from [top]) when given: under Android's navigation
        // bar, which a finger can't reach, so the grass itself is the part that can be touched.
        val soilRow = if (soilFrom != null) max(0, (soilFrom / unit).toInt()) else SOIL_ROW
        build(width, ceil(strip / unit).toInt(), soilRow)
        val p = palette(light, background)
        c.withTranslation(0f, top) {
            clipRect(0f, 0f, width, strip)
            // Soil first, then the green body over its top, with the roots reaching down into it.
            if (soil && strip > soilRow * unit) {
                paint.color = p.soil
                drawRect(0f, soilRow * unit, width, strip, paint)
                paint.color = p.clod
                drawPath(clods, paint)
                paint.color = p.dirt
                drawPath(dirt, paint)
                paint.color = p.pebble
                drawPath(pebbles, paint)
            }
            // No grass at all when the height is 0: only soil.
            if (soilRow == 0) return@withTranslation
            paint.color = p.body
            if (soilRow > TOP_ROWS) drawRect(0f, TOP_ROWS * unit, width, min(strip, soilRow * unit), paint)
            drawPath(roots, paint)
            paint.color = p.inner
            drawPath(inner, paint)
            paint.color = p.speck
            drawPath(specks, paint)
            paint.color = p.blade
            drawPath(blades, paint)
            paint.color = p.tip
            drawPath(tips, paint)
            paint.color = p.flower
            drawPath(flowers, paint)
        }
    }

    /** The tallest blades again, over whatever stands in the grass. */
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

    private fun build(width: Float, rows: Int, soilRow: Int) {
        if (width == builtWidth && rows == builtRows && soilRow == builtSoil) return
        builtWidth = width
        builtRows = rows
        builtSoil = soilRow
        for (p in listOf(blades, tips, flowers, inner, specks, tall, tallTips, roots, clods, dirt, pebbles)) p.reset()
        val columns = ceil(width / unit).toInt()

        fun cell(path: Path, col: Int, row: Int, w: Int = 1, h: Int = 1) {
            val left = col * unit
            path.addRect(left, row * unit, min(width, left + w * unit), (row + h) * unit, Path.Direction.CW)
        }

        // The top: tufts of one to three blades, the middle one tallest, with stubble between.
        var i = 0
        while (i < columns) {
            if (hash(i, 1) % 5 == 0) {
                val w = 1 + hash(i, 2) % 3
                for (k in 0 until w) {
                    val h = 1 + hash(i + k, 3) % 2
                    cell(blades, i + k, TOP_ROWS - h, 1, h)
                }
                i += w
            } else {
                val peak = 3 + hash(i, 4) % 3
                val w = 1 + hash(i, 5) % 3
                for (k in 0 until w) {
                    val h = if (k == (w - 1) / 2) peak else max(1, peak - 1 - hash(i + k, 6) % 2)
                    val row = TOP_ROWS - h
                    cell(blades, i + k, row, 1, h)
                    if (h >= 3) cell(tips, i + k, row)
                    if (h >= TALL) {
                        cell(tall, i + k, row, 1, h)
                        cell(tallTips, i + k, row)
                    }
                    // The odd flower on a tall blade.
                    if (h == peak && peak >= TALL && hash(i + k, 7) % 14 == 0) cell(flowers, i + k, row)
                }
                i += w
            }
        }

        // The body: lighter blades (one pixel wide, two tall) and darker specks, scattered.
        for (row in TOP_ROWS until min(rows, soilRow)) {
            for (col in 0 until columns) {
                val h = hash(col, 100 + row)
                if (h % 22 == 0) cell(inner, col, row, 1, min(2, rows - row))
                else if (h % 22 == 7) cell(specks, col, row, 1 + h / 22 % 2)
            }
        }
        // The roots: here and there the green reaches one or two pixels further down into the soil.
        var col = 0
        while (col < columns) {
            val w = 1 + hash(col, 8) % 3
            val depth = hash(col, 9) % 3 // 0, 1 or 2 extra rows
            if (depth > 0 && soilRow >= TOP_ROWS) for (k in 0 until w) cell(roots, col + k, soilRow, 1, depth - (if (k == w - 1 && w > 1) 1 else 0))
            col += w + hash(col, 10) % 3
        }
        // The soil: lighter clods and darker specks, and the odd pebble (two pixels wide, one tall).
        for (row in soilRow until min(rows, soilRow + MAX_TEXTURE_ROWS)) {
            for (c in 0 until columns) {
                val h = hash(c, 300 + row)
                when {
                    h % 17 == 0 -> cell(clods, c, row, 1 + h / 17 % 2)
                    h % 17 == 5 -> cell(dirt, c, row, 1 + h / 17 % 3)
                    h % 97 == 11 -> cell(pebbles, c, row, 2)
                }
            }
        }
    }

    private class Palette(
        val blade: Int, val tip: Int, val body: Int, val inner: Int, val speck: Int, val flower: Int,
        val soil: Int, val clod: Int, val dirt: Int, val pebble: Int,
    )

    private fun palette(light: Boolean, background: Int): Palette =
        if (light) {
            Palette(
                0xFF84B86C.toInt(), 0xFFA5CF8B.toInt(), 0xFF6CA356.toInt(), 0xFF7CB264.toInt(), 0xFF588F46.toInt(), 0xFFF2EFE4.toInt(),
                0xFF8A6A4C.toInt(), 0xFF9C7B59.toInt(), 0xFF6F5339.toInt(), 0xFFB9AA94.toInt(),
            )
        } else {
            // Quiet and dim under a dark keyboard: deep greens, pulled towards the keyboard's own colour.
            fun calm(color: Int) = ColorUtils.blendARGB(color, background, 0.4f)
            Palette(
                calm(0xFF3F6A3A.toInt()), calm(0xFF4F7C47.toInt()), calm(0xFF2F5230.toInt()), calm(0xFF386037.toInt()),
                calm(0xFF264325.toInt()), calm(0xFF8E9A86.toInt()),
                calm(0xFF4D3A2B.toInt()), calm(0xFF5A4533.toInt()), calm(0xFF3E2E22.toInt()), calm(0xFF7D7466.toInt()),
            )
        }

    private companion object {
        /** The strip's top rows are blades (up to 5 pixels tall); the green body starts below. */
        const val TOP_ROWS = 5
        /** The soil starts this many pixels down, with the green's roots reaching a little further. */
        const val SOIL_ROW = TOP_ROWS + 3
        /** Blades this tall or taller stand in front of an animal's feet. */
        const val TALL = 4
        /** Feet stand this many pixels into the grass. */
        const val FEET_ROWS = 3
        /** The soil is textured this many rows down; below that it is plain. */
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
    // Down from the keys: the grass, [grassDp] tall, then the soil, [bottomSpaceDp] tall and carrying on
    // under Android's bars. With no soil there is only the grass (and the bar's area is the plain background).
    val grassPx = dpu(grassDp)
    val soil = bottomSpaceDp > 0f
    val end = keyboardHeight + grassPx + if (soil) dpu(bottomSpaceDp) + systemBottom else 0f
    grass.draw(c, fill, width.toFloat(), keyboardHeight, minOf(end, grassBottom()), theme.isLight, theme.background, grassPx, soil)
}

/** The tallest blades over an animal's feet. */
internal fun KeyboardView.drawGrassFront(c: Canvas) =
    grass.drawFront(c, fill, width.toFloat(), keyboardHeight, grassBottom(), theme.isLight, theme.background)

/**
 * Android's "back" swipe starts at the left and right edges of the screen, where this keyboard's
 * own swipes (delete a word, space, the cursor on the grass) often begin too. Asks Android to leave
 * the keys and the grass alone. It honours at most 200 dp of height, so the lowest 200 dp is what is
 * asked for. Android's bottom swipes (home, switch app) can't be excluded: the keyboard keeps clear of
 * them instead (see [KeyboardView.systemBottom]).
 */
internal fun KeyboardView.updateGestureExclusion() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width == 0) return
    // The grass and the keys; the soil under it needs no gestures of its own.
    val bottom = (overlayH + gifHeaderH + keyboardHeight + dpu(grassDp)).toInt().coerceAtMost(height)
    val top = max(overlayH + gifHeaderH, bottom - dpu(200f)).toInt()
    systemGestureExclusionRects = if (bottom > top) listOf(Rect(0, top, width, bottom)) else emptyList()
}

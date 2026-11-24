// The strip of grass under the keyboard, where the visiting animals walk.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-art grass, drawn in the same chunky pixels as the animals ([unit] px each): tufts of
 * pointed blades of every height with a lighter tip, stubble between them, a few tiny flowers, and
 * a body of earth-green with lighter blades and darker specks scattered through it. Where things
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
    private var builtWidth = -1f
    private var builtRows = -1

    /** Where an animal's feet go, measured down from the top of the grass. */
    fun feetOffset(strip: Float) = min(FEET_ROWS * unit, strip)

    /** Draws the grass in a strip [top]..[bottom] (keyboard coordinates), [light] for a light keyboard. */
    fun draw(c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int, tint: Int? = null) {
        val strip = bottom - top
        if (strip < 1f || width < 1f) return
        build(width, ceil(strip / unit).toInt())
        val p = palette(light, background)
        c.withTranslation(0f, top) {
            clipRect(0f, 0f, width, strip)
            paint.color = p.body
            if (strip > TOP_ROWS * unit) drawRect(0f, TOP_ROWS * unit, width, strip, paint)
            paint.color = p.inner
            drawPath(inner, paint)
            paint.color = p.speck
            drawPath(specks, paint)
            paint.color = tint ?: p.blade
            drawPath(blades, paint)
            paint.color = tint ?: p.tip
            drawPath(tips, paint)
            paint.color = p.flower
            drawPath(flowers, paint)
        }
    }

    /** The tallest blades again, over whatever stands in the grass. */
    fun drawFront(c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int, tint: Int? = null) {
        if (bottom - top < 1f || width < 1f || builtWidth != width) return
        val p = palette(light, background)
        c.withTranslation(0f, top) {
            paint.color = tint ?: p.blade
            drawPath(tall, paint)
            paint.color = tint ?: p.tip
            drawPath(tallTips, paint)
        }
    }

    private fun build(width: Float, rows: Int) {
        if (width == builtWidth && rows == builtRows) return
        builtWidth = width
        builtRows = rows
        for (p in listOf(blades, tips, flowers, inner, specks, tall, tallTips)) p.reset()
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
        for (row in TOP_ROWS until min(rows, MAX_TEXTURE_ROWS)) {
            for (col in 0 until columns) {
                val h = hash(col, 100 + row)
                if (h % 22 == 0) cell(inner, col, row, 1, min(2, rows - row))
                else if (h % 22 == 7) cell(specks, col, row, 1 + h / 22 % 2)
            }
        }
    }

    private class Palette(val blade: Int, val tip: Int, val body: Int, val inner: Int, val speck: Int, val flower: Int)

    private fun palette(light: Boolean, background: Int): Palette =
        if (light) {
            Palette(0xFF84B86C.toInt(), 0xFFA5CF8B.toInt(), 0xFF6CA356.toInt(), 0xFF7CB264.toInt(), 0xFF588F46.toInt(), 0xFFF2EFE4.toInt())
        } else {
            // Quiet and dim under a dark keyboard: deep greens, pulled towards the keyboard's own colour.
            fun calm(color: Int) = ColorUtils.blendARGB(color, background, 0.4f)
            Palette(calm(0xFF3F6A3A.toInt()), calm(0xFF4F7C47.toInt()), calm(0xFF2F5230.toInt()), calm(0xFF386037.toInt()), calm(0xFF264325.toInt()), calm(0xFF8E9A86.toInt()))
        }

    private companion object {
        /** The strip's top rows are blades (up to 5 pixels tall); the body starts below. */
        const val TOP_ROWS = 5
        /** Blades this tall or taller stand in front of an animal's feet. */
        const val TALL = 4
        /** Feet stand this many pixels into the grass. */
        const val FEET_ROWS = 3
        /** The body is textured this many rows down; below that it is plain. */
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
internal fun KeyboardView.drawGrass(c: Canvas) =
    grass.draw(c, fill, width.toFloat(), keyboardHeight, grassBottom(), theme.isLight, theme.background, if (selecting) theme.accent else null)

/** The tallest blades over an animal's feet. */
internal fun KeyboardView.drawGrassFront(c: Canvas) =
    grass.drawFront(c, fill, width.toFloat(), keyboardHeight, grassBottom(), theme.isLight, theme.background, if (selecting) theme.accent else null)

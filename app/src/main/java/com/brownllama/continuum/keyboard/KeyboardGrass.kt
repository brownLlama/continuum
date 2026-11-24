// The strip of grass under the keyboard, where the visiting animals walk.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.min

/**
 * Pixel-art grass, drawn in the same chunky pixels as the animals ([unit] px each): a jagged edge
 * of blades over a green body with a few darker specks. The shapes are paths built once for a
 * width, so drawing it is three fills. [drawFront] draws the tallest blades again, after an
 * animal, so its feet stand in the grass.
 */
internal class Grass(private val unit: Float) {
    private val blades = Path()
    private val tall = Path()
    private val specks = Path()
    private var builtWidth = -1f
    private var builtRows = -1

    /** Where an animal's feet go, measured down from the top of the grass: three pixels deep. */
    fun feetOffset(strip: Float) = min(FEET_ROWS * unit, strip)

    /** Draws the grass in a strip [top]..[bottom] (keyboard coordinates), [light] for a light keyboard. */
    fun draw(c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int, tint: Int? = null) {
        val strip = bottom - top
        if (strip < 1f || width < 1f) return
        build(width, ceil(strip / unit).toInt())
        val p = palette(light, background)
        val blade = tint ?: p.blade
        c.withTranslation(0f, top) {
            clipRect(0f, 0f, width, strip)
            paint.color = p.body
            if (strip > BLADE_ROWS * unit) drawRect(0f, BLADE_ROWS * unit, width, strip, paint)
            paint.color = blade
            drawPath(blades, paint)
            paint.color = p.speck
            drawPath(specks, paint)
        }
    }

    /** The tallest blades again, over whatever stands in the grass. */
    fun drawFront(c: Canvas, paint: Paint, width: Float, top: Float, bottom: Float, light: Boolean, background: Int, tint: Int? = null) {
        if (bottom - top < 1f || width < 1f || builtWidth != width) return
        c.withTranslation(0f, top) {
            paint.color = tint ?: palette(light, background).blade
            drawPath(tall, paint)
        }
    }

    private fun build(width: Float, rows: Int) {
        if (width == builtWidth && rows == builtRows) return
        builtWidth = width
        builtRows = rows
        blades.reset(); tall.reset(); specks.reset()
        val columns = ceil(width / unit).toInt()
        for (i in 0 until columns) {
            val left = i * unit
            val right = min(width, left + unit)
            // Blades 1 to 4 pixels tall, in a pattern that doesn't visibly repeat.
            val h = bladeHeight(i)
            blades.addRect(left, (BLADE_ROWS - h) * unit, right, BLADE_ROWS * unit, Path.Direction.CW)
            if (h == BLADE_ROWS) tall.addRect(left, 0f, right, BLADE_ROWS * unit, Path.Direction.CW)
            // A darker speck here and there in the body, two pixels wide.
            if ((i * 53 + 17) % 13 == 0 && rows > BLADE_ROWS + 2) {
                val row = BLADE_ROWS + 1 + (i * 29) % (rows - BLADE_ROWS - 1)
                specks.addRect(left, row * unit, min(width, left + 2 * unit), (row + 1) * unit, Path.Direction.CW)
            }
        }
    }

    private class Palette(val blade: Int, val body: Int, val speck: Int)

    private fun palette(light: Boolean, background: Int): Palette {
        // A dark keyboard gets deeper greens, so the strip doesn't glare under it.
        val blade = if (light) 0xFF8CD46C.toInt() else ColorUtils.blendARGB(0xFF6FBF55.toInt(), background, 0.25f)
        val body = if (light) 0xFF5DAE48.toInt() else ColorUtils.blendARGB(0xFF3F8A33.toInt(), background, 0.25f)
        val speck = if (light) 0xFF478F37.toInt() else ColorUtils.blendARGB(0xFF2F6B27.toInt(), background, 0.25f)
        return Palette(blade, body, speck)
    }

    private fun bladeHeight(i: Int) = 1 + ((i * 37) xor (i / 4 * 11)).mod(BLADE_ROWS)

    private companion object {
        /** The tallest blade, in pixels; the body starts there. */
        const val BLADE_ROWS = 4
        /** Feet stand this many pixels into the grass. */
        const val FEET_ROWS = 3
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

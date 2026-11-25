// The grass in the space under the keys.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Grass filling the space under the keys (setting "Space below keyboard"): soft blades standing on
 * the bottom of that space and growing up towards the keys, the tallest reaching them. A bigger
 * setting is taller grass; 0 is none. It only decorates: a touch there goes to the keys above it,
 * as it always has.
 *
 * Three layers of blades, back to front: dense short ones (the darkest), the tall ones, and a few
 * light ones in front. Each blade's place, height, width and lean come from a hash of its number,
 * so the grass looks the same every time. It is drawn once into a bitmap for its size and colours
 * ([image]); a frame only copies that bitmap, and nothing moves, so it costs nothing in between.
 */
internal class Grass(private val density: Float) {
    private var image: Bitmap? = null
    private var builtLight = false
    private var builtBackground = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

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
        val c = Canvas(bmp)
        val colors = colors(light, background)
        for ((i, layer) in LAYERS.withIndex()) {
            path.reset()
            addBlades(layer, width.toFloat(), height.toFloat())
            paint.color = colors[i]
            c.drawPath(path, paint)
        }
        image = bmp
        builtLight = light
        builtBackground = background
        return bmp
    }

    /** One row of blades across [width], standing on the bottom of a strip [height] px tall. */
    private fun addBlades(layer: Layer, width: Float, height: Float) {
        val step = layer.spacingDp * density
        val maxLean = min(height * 0.35f, 6f * density)
        val s = layer.salt
        // From a little left of the edge to a little right of it, so neither edge looks cut off.
        var i = 0
        while (true) {
            val x = (i - 1) * step + (noise(i, s) - 0.5f) * step * 0.8f
            if (x > width + step) break
            val h = height * (layer.shortest + (layer.tallest - layer.shortest) * noise(i, s + 1))
            val w = (1.4f + 1.4f * noise(i, s + 2)) * density
            val lean = (noise(i, s + 3) - 0.5f) * 2f * maxLean
            addBlade(x, height, h, w, lean)
            i++
        }
    }

    /**
     * A blade [w] wide at its base ([x], [bottom]) and [h] tall, its tip [lean] px to the side:
     * two curved edges meeting in a point, bending the way it leans.
     */
    private fun addBlade(x: Float, bottom: Float, h: Float, w: Float, lean: Float) {
        val left = x - w / 2
        val right = x + w / 2
        path.moveTo(left, bottom)
        path.quadTo(left + lean * 0.15f, bottom - h * 0.6f, x + lean, bottom - h)
        path.quadTo(right + lean * 0.4f, bottom - h * 0.55f, right, bottom)
        path.close()
    }

    /** A layer of blades: how far apart (dp), how tall as a share of the strip, and its own hash salt. */
    private class Layer(val spacingDp: Float, val shortest: Float, val tallest: Float, val salt: Int)

    private companion object {
        val LAYERS = listOf(
            Layer(spacingDp = 1.8f, shortest = 0.35f, tallest = 0.75f, salt = 11),
            Layer(spacingDp = 2.6f, shortest = 0.55f, tallest = 1f, salt = 23),
            Layer(spacingDp = 4.2f, shortest = 0.3f, tallest = 0.8f, salt = 37),
        )

        /**
         * The layers' greens, back to front. Under a dark keyboard they are deep and pulled towards
         * its background, so the grass stays calm; under a light one, fresh but soft.
         */
        fun colors(light: Boolean, background: Int): IntArray =
            if (light) {
                intArrayOf(0xFF6E9F58.toInt(), 0xFF86B86C.toInt(), 0xFFA6CF8A.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.12f) }.toIntArray()
            } else {
                intArrayOf(0xFF2F5631.toInt(), 0xFF3F6E3C.toInt(), 0xFF5A8C4F.toInt())
                    .map { ColorUtils.blendARGB(it, background, 0.3f) }.toIntArray()
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

/** The grass's top, in keyboard coordinates: the bottom of the keys. */
internal val KeyboardView.grassTop: Float get() = keyboardHeight

/** How tall the grass is, in px: the setting "Space below keyboard"; 0 draws none. */
internal val KeyboardView.grassHeight: Int get() = dpu(bottomSpaceDp).roundToInt()

/** The grass in the space under the keys (above Android's navigation bar or gesture handle). */
internal fun KeyboardView.drawGrass(c: Canvas) {
    grass.draw(c, width, grassTop, grassHeight, theme.isLight, theme.background)
}

// The tree in the grass: tap it twice to turn the cursor mode on, and the keys become a trackpad.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pixel tree in the grass, in the same chunky pixels ([unit] screen pixels each) as the grass and
 * the animals: a bumpy leafy crown made of clustered lobes, shaded from the top left, with a few red
 * apples, branches, and a trunk that flares at the roots, all with the dark outline everything here
 * has. It is drawn a little see-through, so the keys behind it can still be read.
 *
 * It stands in the middle of the grass level above Android's gesture bar ([fit]). Its bottom, in the
 * grass, is the switch for the cursor mode: tapped twice it turns the mode on ([active]: it is drawn
 * solid and brighter) and tapped twice again it turns it off; a single tap only makes it shake
 * ([nudge]). Its leafy part over the keys only shows; touches there go to the keys. Its picture is
 * drawn once into a bitmap for its size and colours, so a frame only copies that.
 */
internal class Tree(private val unit: Float) {
    /** Where it stands: the top left (keyboard coordinates), and its size in sprite pixels (0: no tree fits). */
    var homeLeft = 0f
        private set
    var homeTop = 0f
        private set
    var cols = 0
        private set
    var rows = 0
        private set

    /** The cursor mode is on: the tree is solid and glowing. */
    var active = false
    private var nudgeAt = -NUDGE_MS

    /** The tree's pixels (colour slots), for [fit]'s size. */
    private var grid = IntArray(0)
    private var image: Bitmap? = null
    private var builtKey = 0L
    private val paint = Paint().apply { alpha = ALPHA }
    private val glow = LightingColorFilter(0xFFFFFFFF.toInt(), 0x002A2A14)

    val present get() = rows > 0
    val width get() = cols * unit
    val height get() = rows * unit

    /**
     * Places the tree for a keyboard [viewWidth] wide: in the middle, its base at [baseY] (just above
     * Android's gesture bar), [wantRows] sprite pixels tall if it can be, but no higher than [topLimit].
     */
    fun fit(viewWidth: Float, baseY: Float, topLimit: Float, wantRows: Int) {
        val fits = ((baseY - topLimit) / unit).toInt().coerceAtMost(wantRows)
        val r = if (fits >= MIN_ROWS) fits else 0
        val c = if (r == 0) 0 else crownWidth(r)
        if (r != rows || c != cols) {
            image = null
            grid = if (r == 0) IntArray(0) else build(r, c)
        }
        rows = r
        cols = c
        if (r == 0) return
        homeLeft = floor(((viewWidth - c * unit) / 2f) / unit) * unit
        homeTop = ceil((baseY - r * unit) / unit) * unit
    }

    /**
     * Whether (x, y) is on the bottom of the tree: within its width (widened by [slack] px) and between
     * [from] (the top of the grass, where the keys end) and [to] (above Android's gesture bar). The crown
     * over the keys is not for touching: it only shows.
     */
    fun hit(x: Float, y: Float, slack: Float, from: Float, to: Float): Boolean =
        present && y >= max(from, homeTop) && y <= to && x >= homeLeft - slack && x <= homeLeft + width + slack

    /** A single tap: the tree shakes for a moment, to show it was touched. */
    fun nudge(now: Long) {
        nudgeAt = now
    }

    /**
     * Draws the tree. Returns how many ms until it needs drawing again (while it shakes), or [REST].
     */
    fun draw(c: Canvas, now: Long, theme: Palette): Long {
        if (!present) return REST
        var shake = 0
        var next = REST
        val t = now - nudgeAt
        if (t in 0 until NUDGE_MS) {
            shake = (sin(2 * PI * t / (NUDGE_MS / 2.0)) * unit).roundToInt()
            next = 16L
        }
        paint.alpha = if (active) 255 else ALPHA
        paint.colorFilter = if (active) glow else null
        // Whole screen pixels, so its own pixels stay crisp.
        c.drawBitmap(imageFor(theme), homeLeft + shake, homeTop, paint)
        return next
    }

    /** The colours the tree is drawn in (the grass's greens, browns, apples and the outline). */
    class Palette(
        val outline: Int, val dark: Int, val mid: Int, val light: Int, val highlight: Int,
        val trunk: Int, val trunkDark: Int, val trunkLight: Int, val apple: Int,
    )

    private fun imageFor(p: Palette): Bitmap {
        val key = (p.mid.toLong() shl 20) xor (p.trunk.toLong() shl 4) xor (rows.toLong() shl 40) xor cols.toLong()
        image?.takeIf { builtKey == key }?.let { return it }
        val bmp = createBitmap(max(1, (cols * unit).roundToInt()), max(1, (rows * unit).roundToInt()))
        val canvas = Canvas(bmp)
        val solid = Paint()
        for (r in 0 until rows) {
            var col = 0
            while (col < cols) {
                val slot = grid[r * cols + col]
                var end = col + 1
                if (slot != NONE) {
                    while (end < cols && grid[r * cols + end] == slot) end++
                    solid.color = when (slot) {
                        OUTLINE -> p.outline
                        DARK -> p.dark
                        MID -> p.mid
                        LIGHT -> p.light
                        HIGHLIGHT -> p.highlight
                        TRUNK -> p.trunk
                        TRUNK_DARK -> p.trunkDark
                        TRUNK_LIGHT -> p.trunkLight
                        else -> p.apple
                    }
                    canvas.drawRect(col * unit, r * unit, end * unit, (r + 1) * unit, solid)
                }
                col = end
            }
        }
        image = bmp
        builtKey = key
        return bmp
    }

    companion object {
        /** What [draw] returns when nothing needs redrawing until something happens. */
        const val REST = -2L
        const val MIN_ROWS = 8
        /** How see-through it is (of 255): the keys behind it can be read. */
        const val ALPHA = 190
        /** How long a single tap makes it shake. */
        const val NUDGE_MS = 220L

        const val NONE = 0
        const val OUTLINE = 1
        const val DARK = 2
        const val MID = 3
        const val LIGHT = 4
        const val HIGHLIGHT = 5
        const val TRUNK = 6
        const val TRUNK_DARK = 7
        const val APPLE = 8
        const val TRUNK_LIGHT = 9

        /** The picture's width for a tree [rows] tall: a crown a good deal wider than the trunk, and odd (the trunk in the middle). */
        fun crownWidth(rows: Int): Int = (rows * 0.78f).roundToInt().let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(9)

        private fun crownRows(rows: Int) = (rows * 0.66f).roundToInt().coerceIn(5, rows - 3)

        /** A repeatable "random" number in 0..1 for [i] and [salt]. */
        private fun noise(i: Int, salt: Int) = GrassField.noise(i, salt)

        /**
         * The tree as a grid of colour slots, [rows] × [cols], the same every time: a crown of overlapping
         * lobes (lit from the top left, shadowed underneath, speckled like leaves), a few apples, a trunk
         * that flares at the roots with branches reaching up into the crown, and an outline round it all.
         * Row 0 and the columns at the sides are left empty for the outline.
         */
        internal fun build(rows: Int, cols: Int): IntArray {
            val g = IntArray(rows * cols)
            val crown = crownRows(rows)
            val mid = cols / 2
            fun put(x: Int, y: Int, slot: Int) { if (x in 0 until cols && y in 0 until rows) g[y * cols + x] = slot }

            // ---- trunk, behind the crown: flares at the base, a little crooked, with two branches up into it.
            val trunkW = max(2, cols / 8)
            val top = crown - 3
            for (y in top until rows) {
                val t = (y - top).toFloat() / max(1, rows - 1 - top)
                val bend = if (noise(rows, 5) < 0.5f) 1 else -1
                val centre = mid + (bend * sin(t * PI) * 0.8).roundToInt()
                val flare = if (y >= rows - 2) 2 else if (y >= rows - 4) 1 else 0
                val half = trunkW / 2
                for (x in centre - half - flare..centre + half - (1 - trunkW % 2) + flare) {
                    val rel = (x - (centre - half)).toFloat() / max(1, trunkW - 1)
                    put(x, y, when {
                        x < centre - half || x > centre + half -> TRUNK_DARK
                        rel < 0.5f -> TRUNK_LIGHT
                        rel > 0.85f -> TRUNK_DARK
                        else -> TRUNK
                    })
                }
                // Bark: a darker mark here and there.
                if (y > top + 2 && noise(y, 11) < 0.35f) put(centre + (noise(y, 12) * trunkW).toInt() - trunkW / 2, y, TRUNK_DARK)
            }
            for (side in intArrayOf(-1, 1)) {
                val len = max(2, cols / 4)
                for (i in 0..len) put(mid + side * i, top - i * 2 / 3, if (i % 2 == 0) TRUNK else TRUNK_DARK)
            }

            // ---- crown: a big middle lobe and smaller ones around it, overlapping into one bumpy cloud.
            data class Lobe(val cx: Float, val cy: Float, val rx: Float, val ry: Float)
            val cx0 = mid.toFloat()
            val cy0 = 1f + crown / 2f
            val rx0 = (cols - 2) / 2f
            val ry0 = crown / 2f
            val lobes = ArrayList<Lobe>()
            lobes += Lobe(cx0, cy0, rx0 * 0.62f, ry0 * 0.7f)
            val around = 6 + cols / 10
            for (k in 0 until around) {
                val a = 2 * PI * (k + 0.35 * noise(k, 21)) / around
                // How far out its middle is and how big it is: together no further than the crown's box.
                val size = 0.3f + 0.12f * noise(k, 23)
                val reach = 0.98f - size - 0.06f * noise(k, 22)
                lobes += Lobe(
                    cx0 + (Math.cos(a) * rx0 * reach).toFloat(), cy0 + (sin(a) * ry0 * reach).toFloat(),
                    rx0 * size, ry0 * size * 1.05f,
                )
            }
            val owner = IntArray(rows * cols) { -1 }
            for (y in 1..crown) for (x in 1 until cols - 1) {
                // The lobe this pixel is most inside of (smallest distance relative to its size).
                var best = Float.MAX_VALUE
                var bl = lobes[0]
                var bi = 0
                for ((i, l) in lobes.withIndex()) {
                    val dx = (x - l.cx) / l.rx
                    val dy = (y - l.cy) / l.ry
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < best) { best = d; bl = l; bi = i }
                }
                if (best > 1f) continue
                owner[y * cols + x] = bi
                // Light from the top left: the lobe's own top left is bright, its bottom right dark; the
                // middle of the crown a little brighter, and speckles like single leaves.
                val nx = (x - bl.cx) / bl.rx
                val ny = (y - bl.cy) / bl.ry
                var light = -(nx * 0.55f + ny * 0.75f) * 0.55f + (1f - best) * 0.25f
                light += (noise((x / 2) * 53 + y / 2, 31) - 0.5f) * 0.34f + (noise(x * 53 + y, 32) - 0.5f) * 0.14f
                // The underside of the whole crown is in shadow.
                light -= max(0f, (y - (crown - crown / 6f)) / (crown / 6f)) * 0.5f
                g[y * cols + x] = when {
                    light > 0.62f -> HIGHLIGHT
                    light > 0.22f -> LIGHT
                    light > -0.18f -> MID
                    else -> DARK
                }
            }
            // Where one lobe meets the next, a shade darker: the crown reads as clusters of leaves.
            for (y in 1..crown) for (x in 1 until cols - 1) {
                val o = owner[y * cols + x]
                if (o < 0) continue
                val edge = (owner.getOrElse(y * cols + x + 1) { -1 }.let { it >= 0 && it != o }) ||
                    (owner.getOrElse((y + 1) * cols + x) { -1 }.let { it >= 0 && it != o })
                if (edge && g[y * cols + x] > DARK) g[y * cols + x] -= 1
            }
            // Apples, in fixed places inside the crown.
            var apples = 0
            var tries = 0
            while (apples < 2 + cols / 8 && tries++ < 80) {
                val x = 2 + (noise(tries, 41) * (cols - 4)).toInt()
                val y = 2 + (noise(tries, 42) * (crown - 3)).toInt()
                val inside = (-1..1).all { dx -> (-1..1).all { dy -> g[(y + dy) * cols + x + dx] in DARK..HIGHLIGHT } }
                if (inside && g[y * cols + x] != APPLE) { g[y * cols + x] = APPLE; apples++ }
            }

            // ---- outline: every empty pixel beside a coloured one.
            val src = g.copyOf()
            fun filled(x: Int, y: Int) = x in 0 until cols && y in 0 until rows && src[y * cols + x] != NONE
            for (y in 0 until rows) for (x in 0 until cols) {
                if (src[y * cols + x] != NONE) continue
                if (filled(x - 1, y) || filled(x + 1, y) || filled(x, y - 1) || filled(x, y + 1)) g[y * cols + x] = OUTLINE
            }
            return g
        }
    }
}

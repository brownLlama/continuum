// The tree in the grass: pick it up (hold it) and drag it to move the cursor; let go and it falls where it is.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pixel tree in the grass, in the same chunky pixels ([unit] screen pixels each) as the grass and
 * the animals: a bumpy leafy crown made of clustered lobes, shaded from the top left, with a few red
 * apples, branches, and a trunk that flares at the roots, all with the dark outline everything here
 * has. It is drawn a little see-through, so the keys behind it can still be read.
 *
 * It stands on the grass level above Android's gesture bar, first in the middle ([fit]). A finger
 * picks it up ([pickUp]) and it hangs below the finger ([carry]) until the finger lets go ([drop]);
 * then it falls straight down from there and stands where it landed. Its picture is drawn once
 * into a bitmap for its size and colours, so a frame only copies that.
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

    /** Held by a finger at ([carryX], [carryY]) since [grabAt]; or falling from ([fromLeft], [fromTop]) since [fallAt]. */
    var carried = false
        private set
    var falling = false
        private set
    private var carryX = 0f
    private var carryY = 0f
    private var grabAt = 0L
    /** Where the top left was relative to the finger when it was picked up. */
    private var grabDx = 0f
    private var grabDy = 0f
    private var fallAt = 0L
    private var fromLeft = 0f
    private var fromTop = 0f
    /** Where the user put it down, as a share of the keyboard's width (null: not yet, so in the middle). */
    private var placed: Float? = null

    /** The tree's pixels (colour slots), for [fit]'s size: what a finger can pick it up by. */
    private var grid = IntArray(0)
    private var image: Bitmap? = null
    private var builtKey = 0L
    private val paint = Paint().apply { alpha = ALPHA }

    val present get() = rows > 0
    val width get() = cols * unit
    val height get() = rows * unit

    /**
     * Places the tree for a keyboard [viewWidth] wide: its base at [baseY] (just above Android's
     * gesture bar), [wantRows] sprite pixels tall if it can be, but no higher than [topLimit]. In the
     * middle until it has been put down somewhere; always [edgeGap] px or more from either edge.
     */
    fun fit(viewWidth: Float, baseY: Float, topLimit: Float, edgeGap: Float, wantRows: Int) {
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
        val want = placed?.let { it * viewWidth } ?: ((viewWidth - c * unit) / 2f)
        homeLeft = floor(want.coerceIn(edgeGap, max(edgeGap, viewWidth - edgeGap - c * unit)) / unit) * unit
        if (!falling) homeTop = ceil((baseY - r * unit) / unit) * unit
        else homeTop = ceil((baseY - r * unit) / unit) * unit
    }

    /**
     * Whether (x, y) is on the standing tree: on one of its pixels or within [slack] px of one, and
     * above [to] (so not in Android's gesture bar). Where the tree stands over the keys it is what is
     * touched, as it is what is seen.
     */
    fun hit(x: Float, y: Float, slack: Float, to: Float): Boolean {
        if (!present || carried || falling || y > to) return false
        val near = (slack / unit).toInt() + 1
        val col = ((x - homeLeft) / unit).toInt()
        val row = ((y - homeTop) / unit).toInt()
        for (r in row - near..row + near) for (c in col - near..col + near) {
            if (r in 0 until rows && c in 0 until cols && grid[r * cols + c] != NONE) return true
        }
        return false
    }

    /** Picked up by a finger at ([x], [y]): it eases from where it stood to hang below the finger. */
    fun pickUp(x: Float, y: Float, now: Long) {
        carried = true
        falling = false
        carryX = x
        carryY = y
        grabAt = now
        grabDx = homeLeft - x
        grabDy = homeTop - y
    }

    /** The finger moved: the tree hangs below it (its top at the finger, the finger at the middle of its width). */
    fun carry(x: Float, y: Float) {
        carryX = x
        carryY = y
    }

    /** Where the top left is now while held (easing from the grab to hanging below the finger). */
    internal fun heldLeft(now: Long): Float {
        val e = ease(((now - grabAt).toFloat() / HANG_MS).coerceIn(0f, 1f))
        return carryX + grabDx + (-width / 2f - grabDx) * e
    }

    internal fun heldTop(now: Long): Float {
        val e = ease(((now - grabAt).toFloat() / HANG_MS).coerceIn(0f, 1f))
        return carryY + grabDy + (-height * HELD_AT - grabDy) * e
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t)

    /**
     * Let go: it falls straight down from where it hangs to the ground line [baseY] and stands there
     * ([viewWidth]: its place is kept as a share of the keyboard's width).
     */
    fun drop(now: Long, viewWidth: Float, baseY: Float, edgeGap: Float) {
        if (!carried) return
        fromLeft = heldLeft(now)
        fromTop = heldTop(now)
        carried = false
        falling = true
        fallAt = now
        val left = floor(fromLeft.coerceIn(edgeGap, max(edgeGap, viewWidth - edgeGap - width)) / unit) * unit
        placed = left / viewWidth
        homeLeft = left
        homeTop = ceil((baseY - height) / unit) * unit
    }

    /**
     * Draws the tree: standing, hanging from the finger, or falling. Returns how many ms until it needs
     * drawing again (while it moves by itself), or [REST]: a finger's moves redraw it.
     */
    fun draw(c: Canvas, now: Long, theme: Palette): Long {
        if (!present) return REST
        var left = homeLeft
        var top = homeTop
        var next = REST
        when {
            carried -> {
                left = heldLeft(now)
                top = heldTop(now)
                if (now - grabAt < HANG_MS) next = 16L
            }
            falling -> {
                val t = ((now - fallAt).toFloat() / FALL_MS).coerceIn(0f, 1f)
                if (t >= 1f) {
                    falling = false
                } else {
                    // Gravity: slow at first, then faster.
                    val g = t * t
                    left = fromLeft + (homeLeft - fromLeft) * min(1f, t * 3f)
                    top = fromTop + (homeTop - fromTop) * g
                    next = 16L
                }
            }
        }
        // Whole screen pixels, so its own pixels stay crisp.
        c.drawBitmap(imageFor(theme), left.roundToInt().toFloat(), top.roundToInt().toFloat(), paint)
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
        /** How long it takes to settle below the finger after being picked up, and to fall when let go. */
        const val HANG_MS = 140L
        const val FALL_MS = 300L
        /** Where on the tree the finger holds it (0 its top .. 1 its base): near the top, so it hangs below the finger. */
        const val HELD_AT = 0.12f

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

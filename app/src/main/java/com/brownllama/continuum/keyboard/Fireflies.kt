// The fireflies of the cursor mode's night: they drift about the keyboard, and gather round the finger that slides.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * A small swarm of pixel fireflies ([COUNT]) over the keyboard's whole area, in two dimensions: left
 * alone they wander, each toward a spot of its own that it picks every few seconds, floating
 * rather than flying (a soft spring with a lot of damping), and flicker at their own speeds. When a
 * finger slides, the first one is the finger's own and sits right under it, and the others swarm
 * round it in loose circles; the finger also drops sparks that fade behind it ([SPARKS] of them in a
 * ring, so nothing is allocated). Let go, and they keep circling the spot for a moment, then scatter.
 *
 * Two more states. While text is being selected they turn amber ([amber]). Once it is, they split in two and
 * each half circles one of two spots ([choose]), a pale blue pair of lights for copy and a warm red-orange
 * one for cut, until [endChoice].
 *
 * Positions are in the keyboard's pixels, drawn on the sprite grid ([unit]) like everything else. The
 * numbers it needs to move are a few small arrays, and a frame costs a few dozen rectangles.
 */
internal class Fireflies(private val unit: Float, private val random: Random = Random.Default) {
    val x = FloatArray(COUNT)
    val y = FloatArray(COUNT)
    private val vx = FloatArray(COUNT)
    private val vy = FloatArray(COUNT)
    private val targetX = FloatArray(COUNT)
    private val targetY = FloatArray(COUNT)
    private val retargetAt = LongArray(COUNT)
    private val phase = FloatArray(COUNT)
    /** How fast each flickers, how far from the finger it circles, and which way and how fast. */
    private val flicker = FloatArray(COUNT)
    private val orbit = FloatArray(COUNT)
    private val spin = FloatArray(COUNT)

    private val sparkX = FloatArray(SPARKS)
    private val sparkY = FloatArray(SPARKS)
    private val sparkBorn = LongArray(SPARKS) { Long.MIN_VALUE / 2 }
    private var nextSpark = 0
    private var lastSparkAt = 0L

    /** The colours while text is being selected: amber, not the usual yellow-green. */
    var amber = false

    /** The two spots they split between once there is a selection: even-numbered ones circle the first (copy), odd-numbered the second (cut). */
    var choosing = false
        private set
    private var copyX = 0f
    private var copyY = 0f
    private var cutX = 0f
    private var cutY = 0f

    /** The whole swarm goes to circle [copyX], [copyY] (the first half) and [cutX], [cutY] (the second). */
    fun choose(copyX: Float, copyY: Float, cutX: Float, cutY: Float) {
        this.copyX = copyX
        this.copyY = copyY
        this.cutX = cutX
        this.cutY = cutY
        choosing = true
        amber = false
    }

    /** The two groups join again. */
    fun endChoice() {
        choosing = false
    }

    private var width = 0f
    private var height = 0f
    private var lastNow = 0L
    private var lastFingerAt = Long.MIN_VALUE / 2
    private var lastFx = 0f
    private var lastFy = 0f

    /** Whether they are gathered round a finger (down now, or just lifted). */
    fun gathered(now: Long) = now - lastFingerAt < GATHER_MS

    /** Scatters them over an area [w] × [h] px, and starts the clock at [now]. */
    fun reset(w: Float, h: Float, now: Long) {
        width = w
        height = h
        lastNow = now
        lastFingerAt = Long.MIN_VALUE / 2
        amber = false
        choosing = false
        for (i in 0 until COUNT) {
            x[i] = random.nextFloat() * w
            y[i] = random.nextFloat() * h
            vx[i] = 0f
            vy[i] = 0f
            retargetAt[i] = now + random.nextLong(0, 1500)
            phase[i] = random.nextFloat() * 6.2832f
            flicker[i] = 2.2f + random.nextFloat() * 3f
            orbit[i] = unit * (3f + random.nextFloat() * 7f)
            spin[i] = (0.9f + random.nextFloat() * 1.3f) * (if (random.nextBoolean()) 1f else -1f)
            targetX[i] = x[i]
            targetY[i] = y[i]
        }
        for (k in 0 until SPARKS) sparkBorn[k] = Long.MIN_VALUE / 2
    }

    /** Moves everything on to [now]; the finger is at ([fx], [fy]) if [fingerDown]. */
    fun step(now: Long, fingerDown: Boolean, fx: Float, fy: Float) {
        if (width <= 0f) return
        val dt = (now - lastNow).coerceIn(0L, 50L) / 1000f
        lastNow = now
        if (fingerDown) {
            lastFingerAt = now
            lastFx = fx
            lastFy = fy
        }
        val gather = gathered(now) && !choosing
        val damp = exp(-dt * (if (gather || choosing) 5f else 1.6f))
        val k = if (choosing) 9f else if (gather) 7f else 0.7f
        for (i in 0 until COUNT) {
            if (choosing) {
                val a = phase[i] + now * spin[i] / 1000f
                val r = unit * (3.5f + (i % 5) * 1.3f)
                val sx = if (i % 2 == 0) copyX else cutX
                val sy = if (i % 2 == 0) copyY else cutY
                targetX[i] = sx + cos(a) * r
                targetY[i] = sy + sin(a) * r * 0.85f
            } else if (gather) {
                val a = phase[i] + now * spin[i] / 1000f
                targetX[i] = lastFx + cos(a) * orbit[i]
                targetY[i] = lastFy + sin(a) * orbit[i] * 0.8f
            } else if (now >= retargetAt[i]) {
                targetX[i] = random.nextFloat() * width
                targetY[i] = random.nextFloat() * height
                retargetAt[i] = now + 1500 + random.nextLong(0, 2500)
            }
            vx[i] = (vx[i] + (targetX[i] - x[i]) * k * dt) * damp
            vy[i] = (vy[i] + (targetY[i] - y[i]) * k * dt) * damp
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            if (x[i] < 0f) { x[i] = 0f; vx[i] = -vx[i] * 0.5f } else if (x[i] > width) { x[i] = width; vx[i] = -vx[i] * 0.5f }
            if (y[i] < 0f) { y[i] = 0f; vy[i] = -vy[i] * 0.5f } else if (y[i] > height) { y[i] = height; vy[i] = -vy[i] * 0.5f }
        }
        if (fingerDown && !choosing) {
            // The first one is the finger's own: right under it.
            val f = 1f - exp(-dt * 28f)
            x[0] = (x[0] + (fx - x[0]) * f).coerceIn(0f, width)
            y[0] = (y[0] + (fy - y[0]) * f).coerceIn(0f, height)
            if (now - lastSparkAt >= SPARK_EVERY_MS) {
                lastSparkAt = now
                sparkX[nextSpark] = x[0] + (random.nextFloat() - 0.5f) * unit * 2f
                sparkY[nextSpark] = y[0] + (random.nextFloat() - 0.5f) * unit * 2f
                sparkBorn[nextSpark] = now
                nextSpark = (nextSpark + 1) % SPARKS
            }
        }
    }

    /** Draws them at [now]: the sparks, then each firefly as a bright pixel with a soft cross of light round it. */
    fun draw(c: Canvas, paint: Paint, now: Long) {
        val spark = if (amber) AMBER_SPARK_RGB else SPARK_RGB
        for (k in 0 until SPARKS) {
            val age = now - sparkBorn[k]
            if (age < 0 || age >= SPARK_MS) continue
            val a = ((1f - age.toFloat() / SPARK_MS) * 210f).toInt()
            paint.color = (a shl 24) or spark
            dot(c, paint, sparkX[k], sparkY[k], 1)
        }
        val gather = gathered(now) && !choosing
        for (i in 0 until COUNT) {
            // Flickers between a dim glow and full light, each at its own pace.
            val s = sin(now * flicker[i] / 1000f + phase[i])
            val f = 0.5f + 0.5f * s * s
            val lead = i == 0 && gather
            val glow: Int
            val core: Int
            when {
                choosing && i % 2 == 0 -> { glow = COPY_GLOW_RGB; core = COPY_CORE_RGB }
                choosing -> { glow = CUT_GLOW_RGB; core = CUT_CORE_RGB }
                amber -> { glow = AMBER_GLOW_RGB; core = AMBER_CORE_RGB }
                else -> { glow = GLOW_RGB; core = CORE_RGB }
            }
            // The two groups that are to be chosen glow a little harder.
            val halo = (((if (lead) 120 else if (choosing) 100 else 70) * f)).toInt()
            paint.color = (halo shl 24) or glow
            val r = if (lead) 2 else 1
            val size = if (lead) 2 else 1
            for (d in 1..r) {
                dot(c, paint, x[i] - (d + size - 1) * unit, y[i], size)
                dot(c, paint, x[i] + d * unit, y[i], size)
                dot(c, paint, x[i], y[i] - (d + size - 1) * unit, size)
                dot(c, paint, x[i], y[i] + d * unit, size)
            }
            paint.color = ((120 + (135 * f).toInt()) shl 24) or core
            dot(c, paint, x[i], y[i], size)
        }
    }

    /** A square [size] sprite pixels wide with its top left at ([px], [py]), snapped to the grid. */
    private fun dot(c: Canvas, paint: Paint, px: Float, py: Float, size: Int) {
        val l = floor(px / unit) * unit
        val t = floor(py / unit) * unit
        c.drawRect(l, t, l + size * unit, t + size * unit, paint)
    }

    companion object {
        const val COUNT = 12
        const val SPARKS = 40
        /** They stay round the spot this long (ms) after the finger lifts. */
        const val GATHER_MS = 1400L
        const val SPARK_MS = 700L
        const val SPARK_EVERY_MS = 22L
        private const val CORE_RGB = 0xF2FF9A
        private const val GLOW_RGB = 0xB4E640
        private const val SPARK_RGB = 0xFFE27A
        /** While selecting: amber. Copy's pale blue pair of lights, and cut's warm red-orange ones. */
        private const val AMBER_CORE_RGB = 0xFFE08A
        private const val AMBER_GLOW_RGB = 0xFFAA22
        private const val AMBER_SPARK_RGB = 0xFFB347
        private const val COPY_CORE_RGB = 0xE6F4FF
        private const val COPY_GLOW_RGB = 0x6EB8FF
        private const val CUT_CORE_RGB = 0xFFD2B0
        private const val CUT_GLOW_RGB = 0xFF5A32
    }
}

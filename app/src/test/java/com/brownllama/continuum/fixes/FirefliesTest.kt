package com.brownllama.continuum.fixes

import android.graphics.Canvas
import android.graphics.Paint
import com.brownllama.continuum.keyboard.Fireflies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot
import kotlin.random.Random

/** The swarm that the cursor mode's night brings out: pure movement, so it is checked without a screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirefliesTest {
    private val w = 1080f
    private val h = 300f
    private val unit = 4f

    private fun swarm() = Fireflies(unit, Random(7)).also { it.reset(w, h, 0L) }

    /** Runs the swarm for [ms] at 30 frames a second from [from], with the finger at ([fx], [fy]) or none. */
    private fun Fireflies.run(from: Long, ms: Long, down: Boolean, fx: Float = 0f, fy: Float = 0f): Long {
        var t = from
        while (t < from + ms) {
            t += 33
            step(t, down, fx, fy)
        }
        return t
    }

    private fun Fireflies.meanDistance(fx: Float, fy: Float) = (0 until Fireflies.COUNT).map { hypot(x[it] - fx, y[it] - fy) }.average()

    @Test fun `they start scattered over the whole area and never leave it`() {
        val f = swarm()
        val xs = (0 until Fireflies.COUNT).map { f.x[it] }
        val ys = (0 until Fireflies.COUNT).map { f.y[it] }
        assertTrue("spread over the width: ${xs.max() - xs.min()}", xs.max() - xs.min() > w * 0.4f)
        assertTrue("and the height: ${ys.max() - ys.min()}", ys.max() - ys.min() > h * 0.3f)
        f.run(0, 20_000, down = false)
        for (i in 0 until Fireflies.COUNT) {
            assertTrue("x ${f.x[i]}", f.x[i] in 0f..w)
            assertTrue("y ${f.y[i]}", f.y[i] in 0f..h)
        }
        f.run(20_000, 10_000, down = true, fx = 5000f, fy = -5000f) // a finger far outside the area
        for (i in 0 until Fireflies.COUNT) {
            assertTrue("x ${f.x[i]}", f.x[i] in 0f..w)
            assertTrue("y ${f.y[i]}", f.y[i] in 0f..h)
        }
    }

    @Test fun `they wander when left alone`() {
        val f = swarm()
        val x0 = f.x.copyOf()
        val y0 = f.y.copyOf()
        f.run(0, 6_000, down = false)
        assertTrue("they moved about", (0 until Fireflies.COUNT).count { hypot(f.x[it] - x0[it], f.y[it] - y0[it]) > 30f } > Fireflies.COUNT / 2)
    }

    @Test fun `a sliding finger draws them round it, the first one right under it, in both directions`() {
        val f = swarm()
        val before = f.meanDistance(540f, 150f)
        var t = f.run(0, 100, down = true, fx = 540f, fy = 150f)
        // Slides across, then up and down: the first one stays under the finger in both x and y.
        for (step in 0 until 40) {
            t += 33
            val fx = 540f + step * 8f
            val fy = 150f + 80f * kotlin.math.sin(step / 5.0).toFloat()
            f.step(t, true, fx, fy)
            if (step > 5) {
                assertEquals("x, step $step", fx, f.x[0], 40f)
                assertEquals("y, step $step", fy, f.y[0], 40f)
            }
        }
        t = f.run(t, 2_000, down = true, fx = 800f, fy = 120f)
        assertEquals(800f, f.x[0], unit * 2)
        assertEquals(120f, f.y[0], unit * 2)
        assertTrue("the swarm gathered: ${f.meanDistance(800f, 120f)} < $before", f.meanDistance(800f, 120f) < 90f && f.meanDistance(800f, 120f) < before)
    }

    @Test fun `after the finger lifts they stay a moment, then scatter again`() {
        val f = swarm()
        var t = f.run(0, 2_000, down = true, fx = 300f, fy = 100f)
        t = f.run(t, 600, down = false)
        assertTrue("still round the spot", f.meanDistance(300f, 100f) < 100f)
        assertTrue(f.gathered(t))
        t = f.run(t, 6_000, down = false)
        assertTrue("scattered again", f.meanDistance(300f, 100f) > 150f)
    }

    @Test fun `a frame draws a few dozen rectangles, and a finger adds sparks`() {
        val f = swarm()
        val rects = intArrayOf(0)
        val canvas = object : Canvas(android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)) {
            override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) { rects[0]++ }
        }
        val paint = Paint()
        var t = f.run(0, 1_000, down = false)
        f.draw(canvas, paint, t)
        val still = rects[0]
        assertTrue("12 fireflies: a core and four rays each, at most $still", still in Fireflies.COUNT..Fireflies.COUNT * 8 + 4)
        rects[0] = 0
        t = f.run(t, 700, down = true, fx = 400f, fy = 100f)
        f.draw(canvas, paint, t)
        assertTrue("sparks trail the finger (${rects[0]} > $still)", rects[0] > still + 10)
        assertTrue("but never more than the ring and the swarm", rects[0] < Fireflies.COUNT * 12 + Fireflies.SPARKS + 10)
    }
}

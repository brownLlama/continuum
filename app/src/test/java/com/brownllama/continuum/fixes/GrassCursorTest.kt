package com.brownllama.continuum.fixes

import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sliding a finger sideways along the grass under the keys moves the cursor; the keys are untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GrassCursorTest {
    private fun keyboard(mode: String = "mini", space: Int = 25) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to space))
            .also { it.waitReady() }

    /** A touch point just inside the grass (a few pixels under the keys), in the view's own pixels. */
    private fun Keyboard.grassY() = view.overlayH + view.gifHeaderH + view.keyboardHeight + 6f

    /** A finger sliding by [dx] pixels in small moves, from [fromX] at height [y] (default: in the grass). */
    private fun Keyboard.slide(dx: Float, fromX: Float = view.width * 0.5f, y: Float? = null) {
        layoutView()
        val y = y ?: grassY()
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, at: Long) = MotionEvent.obtain(t, at, action, x, y, 0)
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, fromX, t))
        val n = 20
        for (i in 1..n) view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, fromX + dx * i / n, t + i * 8L))
        view.onTouchEvent(ev(MotionEvent.ACTION_UP, fromX + dx, t + n * 8L + 8))
        idle()
    }

    @Test fun `sliding along the grass moves the cursor, anywhere along it, in both modes`() {
        for (mode in listOf("mini", "full")) for (from in listOf(0.2f, 0.5f, 0.8f)) {
            val k = keyboard(mode)
            k.focus(text = "hello world")
            assertEquals(11, k.edit.selectionStart)
            k.slide(-28f, fromX = k.view.width * from) // one character for every 4 dp
            assertEquals("$mode from $from", 4, k.edit.selectionStart)
            k.slide(20f, fromX = k.view.width * from)
            assertEquals("$mode from $from", 9, k.edit.selectionStart)
            assertEquals("hello world", k.text)
        }
    }

    @Test fun `the cursor stops at the ends of the text`() {
        val k = keyboard()
        k.focus(text = "ab")
        k.slide(-300f)
        assertEquals(0, k.edit.selectionStart)
        k.slide(300f)
        assertEquals(2, k.edit.selectionStart)
        assertEquals("ab", k.text)
    }

    @Test fun `a word in progress is finished as typed before the cursor moves`() {
        val k = keyboard("full")
        k.focus()
        k.type("zxqvk")
        k.slide(-18f)
        assertEquals("zxqvk", k.text)
        assertEquals(1, k.edit.selectionStart)
        k.type("a")
        assertEquals("zaxqvk", k.text)
    }

    @Test fun `a slide that starts on the keys never moves the cursor, it is the swipe it always was`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            k.focus(text = "hello world")
            k.layoutView()
            // Swipe left on the letters deletes a word.
            k.slide(-100f, y = k.view.overlayH + k.view.keyboardHeight * 0.6f)
            assertEquals(mode, "hello ", k.text)
            assertEquals(mode, 6, k.edit.selectionStart)
        }
    }

    @Test fun `a tap on the grass is still a tap, not a slide`() {
        val k = keyboard()
        k.focus(text = "hello")
        k.layoutView()
        k.tap(k.view.width / 2f, k.grassY())
        assertEquals(6, k.text.length)
    }

    @Test fun `with no grass there is nothing to slide on`() {
        val k = keyboard(space = 0)
        k.focus(text = "hello world")
        k.layoutView()
        k.slide(-28f, y = k.view.overlayH + k.view.keyboardHeight + 3f)
        assertTrue("the cursor did not move back", k.edit.selectionStart >= 11)
    }

    @Test fun `the grass leans the way the finger slides, and stands again when it lifts`() {
        val k = keyboard()
        k.focus(text = "hello world")
        k.layoutView()
        val y = k.grassY()
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, at: Long) = MotionEvent.obtain(t, at, action, x, y, 0)
        k.view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 500f, t))
        for (i in 1..10) k.view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 500f + 8f * i, t + i * 8L))
        assertEquals(1, k.view.grass.push)
        for (i in 1..10) k.view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 580f - 12f * i, t + 100L + i * 8L))
        assertEquals(-1, k.view.grass.push)
        k.view.onTouchEvent(ev(MotionEvent.ACTION_UP, 460f, t + 300L))
        assertEquals(0, k.view.grass.push)
        assertTrue(k.view.grass.at(10, k.view.grass.imageHeight) == 0)
    }
}

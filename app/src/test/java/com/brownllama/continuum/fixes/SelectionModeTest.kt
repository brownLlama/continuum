package com.brownllama.continuum.fixes

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.CHOOSE_DELAY_MS
import com.brownllama.continuum.keyboard.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Selecting in the cursor mode: a double tap selects the word at the cursor, a double tap that slides stretches
 * the selection, a triple tap selects everything; then the fireflies split into a copy swarm and a cut swarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelectionModeTest {
    private fun keyboard() =
        Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to 25))
            .also { it.waitReady() }

    private fun Keyboard.wait(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun Keyboard.frame() = view.draw(Canvas(Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)))
    private val Keyboard.offY get() = view.overlayH + view.gifHeaderH

    /** The text, with the cursor at [cursor], in the cursor mode (turned on with two taps on the tree). */
    private fun Keyboard.night(text: String, cursor: Int): Keyboard {
        focus(text = text)
        layoutView()
        frame()
        val t = view.tree
        val x = t.homeLeft + t.width / 2f
        val y = offY + t.homeTop + t.height - 3f
        tapAt(x, y); wait(120); tapAt(x, y); wait(60)
        assertTrue(view.cursorMode)
        moveCursor(cursor)
        return this
    }

    private fun Keyboard.touch(action: Int, x: Float, y: Float, at: Long = SystemClock.uptimeMillis()) {
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    private fun Keyboard.tapAt(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        wait(40)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    /** A spot on the letters, away from the tree. */
    private fun Keyboard.spot() = 300f to offY + view.keyboardHeight * 0.6f

    private fun Keyboard.doubleTap() {
        val (x, y) = spot()
        tapAt(x, y); wait(100); tapAt(x, y)
    }

    private fun Keyboard.selected(): Pair<Int, Int> = minOf(edit.selectionStart, edit.selectionEnd) to maxOf(edit.selectionStart, edit.selectionEnd)

    private fun Keyboard.clip(): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun `a double tap selects the word the cursor is in, and the fireflies turn amber`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        assertEquals("brave", k.text.substring(k.selected().first, k.selected().second))
        assertEquals(Selection.SELECTING, k.view.selection)
        assertTrue(k.view.fireflies.amber)
    }

    @Test fun `with the cursor at the end of a word, or between words, it is the word just before`() {
        val k = keyboard().night("hello brave new world", 11)
        k.doubleTap()
        assertEquals("brave", k.text.substring(k.selected().first, k.selected().second))
        val k2 = keyboard().night("hello  brave", 6)
        k2.doubleTap()
        assertEquals("hello", k2.text.substring(k2.selected().first, k2.selected().second))
    }

    @Test fun `a double tap with no word near selects nothing`() {
        val k = keyboard().night("", 0)
        k.doubleTap()
        assertEquals(Selection.NONE, k.view.selection)
        assertFalse(k.view.fireflies.amber)
    }

    @Test fun `a double tap that slides stretches the selection, both ways`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(100)
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x + 1f * i, y) // 20 px: five characters
        k.wait(50)
        assertEquals("brave new ", k.text.substring(k.selected().first, k.selected().second))
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x + 20f - 4f * i, y) // 80 px back: past where it began
        k.wait(50)
        // The fixed end is the start of "brave" (6): going left of it the selection grows to the left.
        assertTrue("it grew the other way: ${k.selected()}", k.selected().first < 6 && k.selected().second == 6)
        k.touch(MotionEvent.ACTION_UP, x - 60f, y)
        assertEquals("hello brave new world", k.text)
    }

    @Test fun `a triple tap selects everything`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(80); k.tapAt(x, y); k.wait(80); k.tapAt(x, y)
        assertEquals(0 to k.text.length, k.selected())
        assertEquals(Selection.SELECTING, k.view.selection)
    }

    @Test fun `a moment after the last finger lifts the swarm splits into copy and cut, near the finger and well apart`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS - 100)
        assertEquals("not yet", Selection.SELECTING, k.view.selection)
        k.wait(200)
        assertEquals(Selection.CHOOSING, k.view.selection)
        assertTrue(k.view.fireflies.choosing)
        val v = k.view
        assertTrue("copy is on the left of cut", v.spotCopyX < v.spotCutX)
        assertTrue("well apart", v.spotCutX - v.spotCopyX >= 100f)
        assertTrue("inside the screen", v.spotCopyX > 0f && v.spotCutX < v.width)
        k.frame() // and it draws
    }

    @Test fun `the choices stay on the screen even when the finger lifted at its edge`() {
        for (edgeX in listOf(5f, 1075f)) {
            val k = keyboard().night("hello brave new world", 8)
            val y = k.spot().second
            k.tapAt(edgeX, y); k.wait(100); k.tapAt(edgeX, y)
            k.wait(CHOOSE_DELAY_MS + 100)
            val v = k.view
            assertEquals(Selection.CHOOSING, v.selection)
            assertTrue("copy ${v.spotCopyX}", v.spotCopyX >= 40f && v.spotCopyX < v.width - 40f)
            assertTrue("cut ${v.spotCutX}", v.spotCutX > 40f && v.spotCutX <= v.width - 40f)
            assertTrue("apart", v.spotCutX - v.spotCopyX >= 100f)
        }
    }

    @Test fun `tapping the copy swarm copies the selection and ends the selecting`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        val y = k.offY + k.view.spotY
        k.tapAt(k.view.spotCopyX, y)
        assertEquals("brave", k.clip())
        assertEquals("hello brave new world", k.text)
        assertEquals(Selection.NONE, k.view.selection)
        assertFalse(k.view.fireflies.amber)
        assertFalse(k.view.fireflies.choosing)
        assertEquals("the selection is dropped, the cursor at its end", 11, k.edit.selectionStart)
        assertEquals(k.edit.selectionStart, k.edit.selectionEnd)
        // The mode goes on: a slide moves the cursor again.
        val (x, y2) = k.spot()
        k.touch(MotionEvent.ACTION_DOWN, x + 400f, y2)
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x + 400f - 2f * i, y2)
        k.wait(50)
        assertEquals("ten characters back from 11", 1, k.edit.selectionStart)
        k.touch(MotionEvent.ACTION_UP, x + 360f, y2)
    }

    @Test fun `tapping the cut swarm cuts the selection`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        k.tapAt(k.view.spotCutX, k.offY + k.view.spotY)
        assertEquals("brave", k.clip())
        assertEquals("hello  new world", k.text)
        assertEquals(Selection.NONE, k.view.selection)
    }

    @Test fun `a tap anywhere else drops the selection`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        k.tapAt(k.view.spotCutX + 250f, k.offY + k.view.spotY)
        assertEquals(Selection.NONE, k.view.selection)
        assertEquals("nothing was copied or cut", "hello brave new world", k.text)
        assertEquals(k.edit.selectionStart, k.edit.selectionEnd)
        assertFalse(k.view.fireflies.choosing)
        assertTrue(k.view.cursorMode)
    }

    @Test fun `a tap does not move the cursor, but a slide does, once it has gone a little way`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..6) k.touch(MotionEvent.ACTION_MOVE, x + i, y - i) // a wobble of 6 px
        k.wait(50)
        k.touch(MotionEvent.ACTION_UP, x + 6f, y - 6f)
        assertEquals(8, k.edit.selectionStart)
    }

    @Test fun `when the mode ends the selecting ends too, and the selection stays in the text`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        assertTrue(k.ime.selecting)
        k.view.keyboardHidden()
        assertFalse(k.view.cursorMode)
        assertEquals(Selection.NONE, k.view.selection)
        assertFalse(k.ime.selecting)
        assertEquals("brave", k.text.substring(k.selected().first, k.selected().second))
    }
}

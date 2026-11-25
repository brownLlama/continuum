package com.brownllama.continuum.fixes

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.CHOOSE_DELAY_MS
import com.brownllama.continuum.keyboard.JAR_SHUT_MS
import com.brownllama.continuum.keyboard.JAR_TOTAL_MS
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

    @Test fun `a double tap that slides stretches from the word, so right reaches past its end and left before its start and the word always stays`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(100)
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x + 1f * i, y) // 20 px: five characters
        k.wait(50)
        assertEquals("brave new ", k.text.substring(k.selected().first, k.selected().second))
        for (i in 1..10) k.touch(MotionEvent.ACTION_MOVE, x + 20f - 2f * i, y) // back 20 px: the word again
        k.wait(50)
        assertEquals("brave", k.text.substring(k.selected().first, k.selected().second))
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x - 1f * i, y) // 20 px to the left of where it began: before the word
        k.wait(50)
        val sel = k.selected()
        assertTrue("it reaches back before the word: $sel", sel.first < 6)
        assertEquals("and the end of the word stays", 11, sel.second)
        assertTrue("the word is inside it: " + k.text.substring(sel.first, sel.second), k.text.substring(sel.first, sel.second).endsWith("brave"))
        k.touch(MotionEvent.ACTION_UP, x - 20f, y)
        assertEquals("hello brave new world", k.text)
    }

    @Test fun `sliding further left than the text goes stops at its start, and further right at its end`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(100)
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..40) k.touch(MotionEvent.ACTION_MOVE, x - 10f * i, y)
        k.wait(50)
        assertEquals(0 to 11, k.selected())
        for (i in 1..80) k.touch(MotionEvent.ACTION_MOVE, x - 400f + 12f * i, y)
        k.wait(50)
        assertEquals(6 to k.text.length, k.selected())
        k.touch(MotionEvent.ACTION_UP, x + 400f, y)
    }

    @Test fun `sliding up or down while selecting stretches by lines with shift held, down the end and up the start, and sliding back shortens`() {
        val k = keyboard().night("one\ntwo\nthree\nfour", 5)
        val keys = ArrayList<Int>()
        val real = k.ime.ic!!
        k.ime.ic = object : android.view.inputmethod.InputConnectionWrapper(real, true) {
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) keys += event.keyCode
                return super.sendKeyEvent(event)
            }
        }
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(100)
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        assertEquals("two", k.text.substring(k.selected().first, k.selected().second))
        var at = y
        fun slideTo(to: Float) {
            for (i in 1..13) k.touch(MotionEvent.ACTION_MOVE, x, at + (to - at) * i / 13)
            at = to
            k.wait(50)
        }
        val shift = android.view.KeyEvent.KEYCODE_SHIFT_LEFT
        slideTo(y + 26f) // a line down: the end goes down
        assertEquals(listOf(shift, android.view.KeyEvent.KEYCODE_DPAD_DOWN), keys)
        keys.clear()
        slideTo(y) // back: the same end shortens
        assertEquals(listOf(shift, android.view.KeyEvent.KEYCODE_DPAD_UP), keys)
        keys.clear()
        slideTo(y - 26f) // a line up from there: now the start goes up
        assertEquals(listOf(shift, android.view.KeyEvent.KEYCODE_DPAD_UP), keys)
        k.touch(MotionEvent.ACTION_UP, x, y - 26f)
        assertEquals("one\ntwo\nthree\nfour", k.text)
    }

    @Test fun `a triple tap selects everything`() {
        val k = keyboard().night("hello brave new world", 8)
        val (x, y) = k.spot()
        k.tapAt(x, y); k.wait(80); k.tapAt(x, y); k.wait(80); k.tapAt(x, y)
        assertEquals(0 to k.text.length, k.selected())
        assertEquals(Selection.SELECTING, k.view.selection)
    }

    @Test fun `a moment after the last finger lifts two jars appear, copy and cut, near the finger and well apart`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS - 100)
        assertEquals("not yet", Selection.SELECTING, k.view.selection)
        k.wait(200)
        assertEquals(Selection.CHOOSING, k.view.selection)
        assertTrue("the fireflies hover between the jars", k.view.fireflies.hovering)
        val v = k.view
        assertTrue("copy is on the left of cut", v.spotCopyX < v.spotCutX)
        assertTrue("well apart", v.spotCutX - v.spotCopyX >= 100f)
        assertTrue("inside the screen", v.spotCopyX > 0f && v.spotCutX < v.width)
        k.frame() // and it draws
    }

    @Test fun `the choices are two glass jars with their names on stickers`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        val labels = ArrayList<String>()
        val rects = intArrayOf(0)
        val canvas = object : Canvas(Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                labels += text
                super.drawText(text, x, y, paint)
            }
            override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: android.graphics.Paint) { rects[0]++ }
        }
        k.view.draw(canvas)
        assertTrue("labelled Copy: $labels", "Copy" in labels)
        assertTrue("and Cut: $labels", "Cut" in labels)
        assertTrue("drawn in pixels: ${rects[0]}", rects[0] > 100)
    }

    @Test fun `choosing a jar pours the fireflies into it, the lid clicks shut, and then the cursor mode ends`() {
        for (cut in listOf(false, true)) {
            val k = keyboard().night("hello brave new world", 8)
            k.doubleTap()
            k.wait(CHOOSE_DELAY_MS + 100)
            val x = if (cut) k.view.spotCutX else k.view.spotCopyX
            k.tapAt(x, k.offY + k.view.spotY)
            assertEquals("the right jar", if (cut) 2 else 1, k.view.jarChosen)
            assertTrue("the fireflies stream in", k.view.fireflies.funneling)
            assertFalse(k.view.fireflies.hovering)
            k.wait(JAR_SHUT_MS + 100)
            k.frame()
            assertEquals("lid shut, still lit", if (cut) 2 else 1, k.view.jarChosen)
            k.wait(JAR_TOTAL_MS)
            assertEquals("the jar is gone", 0, k.view.jarChosen)
            assertFalse("and the fireflies are out", k.view.fireflies.funneling)
            assertFalse("and it is day again", k.view.cursorMode)
            assertFalse(k.view.tree.active)
        }
    }

    @Test fun `the other jar stays until the fireflies are in and the lid has shut, and only then disappears`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        k.tapAt(k.view.spotCopyX, k.offY + k.view.spotY)
        fun labels(): List<String> {
            val l = ArrayList<String>()
            val canvas = object : Canvas(Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)) {
                override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) { l += text; super.drawText(text, x, y, paint) }
            }
            k.view.draw(canvas)
            return l
        }
        k.wait(300)
        assertTrue("while the fireflies pour in, both jars are there: ${labels()}", labels().containsAll(listOf("Copy", "Cut")))
        k.wait(JAR_SHUT_MS - 300 + com.brownllama.continuum.keyboard.JAR_OTHER_FADE_MS + 50)
        val after = labels()
        assertTrue("the chosen jar is still there: $after", "Copy" in after)
        assertFalse("and the other is gone: $after", "Cut" in after)
    }

    @Test fun `all the fireflies end up inside the chosen jar`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        val v = k.view
        k.tapAt(v.spotCopyX, k.offY + v.spotY)
        // Run the swarm's own clock to well after they have all gone in (from wherever they were, which can be the far end of the keyboard).
        var t = SystemClock.uptimeMillis()
        repeat(120) { t += 33; v.fireflies.step(t, false, 0f, 0f) }
        val u = v.spritePx
        for (i in 0 until com.brownllama.continuum.keyboard.Fireflies.COUNT) {
            assertTrue("firefly $i x ${v.fireflies.x[i]} near the jar at ${v.spotCopyX}", kotlin.math.abs(v.fireflies.x[i] - v.spotCopyX) <= 8 * u)
        }
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

    @Test fun `tapping the copy jar copies the selection and ends the selecting`() {
        val k = keyboard().night("hello brave new world", 8)
        k.doubleTap()
        k.wait(CHOOSE_DELAY_MS + 100)
        val y = k.offY + k.view.spotY
        k.tapAt(k.view.spotCopyX, y)
        assertEquals("brave", k.clip())
        assertEquals("hello brave new world", k.text)
        assertEquals(Selection.NONE, k.view.selection)
        assertTrue("the fireflies go into the jar", k.view.fireflies.funneling)
        assertEquals("the selection is dropped, the cursor at its end", 11, k.edit.selectionStart)
        assertEquals(k.edit.selectionStart, k.edit.selectionEnd)
        k.wait(JAR_TOTAL_MS + 100)
        assertFalse("the cursor mode ends when the jar has shut", k.view.cursorMode)
    }

    @Test fun `tapping the cut jar cuts the selection`() {
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
        assertFalse(k.view.fireflies.hovering)
        assertEquals("no jar was chosen", 0, k.view.jarChosen)
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

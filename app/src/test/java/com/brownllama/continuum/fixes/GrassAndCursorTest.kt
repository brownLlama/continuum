package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.content.ClipboardManager
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.editMenuRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The grass under the keyboard, and sliding a finger along it to move the cursor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GrassAndCursorTest {
    private fun keyboard(mode: String = "mini") =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false)).also { it.waitReady() }

    /** A finger on the grass (the bottom pixels of the view) sliding by [dx] pixels in small moves. */
    private fun Keyboard.slide(dx: Float, fromX: Float = view.width / 2f, y: Float? = null, lift: Boolean = true) {
        layoutView() // the view grows when full mode opens
        val y = y ?: (view.height - 4f)
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, at: Long) = MotionEvent.obtain(t, at, action, x, y, 0)
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, fromX, t))
        val n = 20
        for (i in 1..n) view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, fromX + dx * i / n, t + i * 8L))
        if (lift) view.onTouchEvent(ev(MotionEvent.ACTION_UP, fromX + dx, t + n * 8L + 8))
        idle()
    }

    // ---- cursor -------------------------------------------------------------------------------

    @Test fun `sliding along the grass moves the cursor`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            k.focus(text = "hello world")
            assertEquals(11, k.edit.selectionStart)
            k.slide(-100f)
            assertEquals(mode, 1, k.edit.selectionStart)
            k.slide(50f)
            assertEquals(mode, 6, k.edit.selectionStart)
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
    }

    @Test fun `a word in progress is finished as typed before the cursor moves`() {
        val k = keyboard("full")
        k.focus()
        k.type("zxqvk")
        k.slide(-30f)
        assertEquals("zxqvk", k.text)
        assertEquals(2, k.edit.selectionStart)
        assertEquals("the word was finished", 1, k.ime.learnedCount("zxqvk"))
        // Typing carries on from the new place.
        k.type("a")
        assertEquals("zxaqvk", k.text)
    }

    @Test fun `a tap on the grass still types the letter above it, and a slide in the keys does not move the cursor`() {
        val k = keyboard()
        k.focus(text = "hello")
        k.layoutView()
        k.tap(k.view.width / 2f, k.view.height - 4f)
        assertEquals(6, k.text.length)
        assertEquals(6, k.edit.selectionStart)
        // The same slide higher up is a swipe on the letters (swipe left deletes a word), not the cursor.
        val k2 = keyboard()
        k2.focus(text = "hello world")
        k2.layoutView()
        k2.slide(-100f, y = k2.view.height * 0.5f)
        assertTrue(k2.edit.selectionStart == k2.text.length)
    }

    // ---- grass ----------------------------------------------------------------------------------

    private class Recorder(bmp: Bitmap) : Canvas(bmp) {
        val rects = ArrayList<Triple<Int, Float, Float>>() // colour, bottom, width
        val paths = ArrayList<Int>()
        var offset = 0f
        override fun translate(dx: Float, dy: Float) { offset += dy; super.translate(dx, dy) }
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) { rects += Triple(paint.color, b + offset, r - l); super.drawRect(l, t, r, b, paint) }
        override fun drawPath(path: Path, paint: Paint) { paths += paint.color; super.drawPath(path, paint) }
    }

    private fun isGreen(c: Int) = ((c shr 8) and 0xff) > ((c shr 16) and 0xff) + 10 && ((c shr 8) and 0xff) > (c and 0xff) + 10

    private fun grassOf(k: Keyboard): Recorder {
        k.layoutView()
        val rec = Recorder(Bitmap.createBitmap(k.view.width, k.view.height, Bitmap.Config.ARGB_8888))
        k.view.draw(rec)
        return rec
    }

    @Test fun `the space under the keyboard is grass in both modes and in the panels`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            k.focus()
            for (panel in listOf(KeyboardView.Panel.LETTERS, KeyboardView.Panel.SYMBOLS, KeyboardView.Panel.EMOJI, KeyboardView.Panel.CLIPBOARD)) {
                k.view.showPanel(panel)
                val rec = grassOf(k)
                val body = rec.rects.filter { isGreen(it.first) && it.third >= k.view.width.toFloat() }
                assertTrue("$mode/$panel body", body.isNotEmpty())
                assertEquals("$mode/$panel: down to the bottom of the view", k.view.height.toFloat(), body.last().second, 1f)
                assertTrue("$mode/$panel blades", rec.paths.any { isGreen(it) })
            }
        }
    }

    @Test fun `the grass follows the setting for the space below the keyboard`() {
        val none = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 0)).also { it.waitReady(); it.focus() }
        assertFalse("no space, no grass", grassOf(none).paths.any { isGreen(it) })
        val some = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 40)).also { it.waitReady(); it.focus() }
        assertTrue(grassOf(some).paths.any { isGreen(it) })
    }

    // ---- edit menu ------------------------------------------------------------------------------

    /**
     * Holds a finger on the grass until the menu opens, slides up onto menu item [item] and lifts there
     * ([item] -1: lifts on the grass itself).
     */
    private fun Keyboard.menu(item: Int, fromX: Float = view.width / 2f) {
        layoutView()
        val y0 = view.height - 4f
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float, at: Long) = MotionEvent.obtain(t, at, action, x, y, 0)
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, fromX, y0, t))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        var x = fromX
        var y = y0
        if (item >= 0) {
            val r = RectF()
            view.editMenuRect(r, fromX)
            x = r.left + (item + 0.5f) * r.width() / 5
            y = r.centerY() + view.overlayH + view.gifHeaderH
        }
        view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, t + 1000))
        view.onTouchEvent(ev(MotionEvent.ACTION_UP, x, y, t + 1010))
        idle()
    }

    private fun Keyboard.clip(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun `select all, copy and paste`() {
        val k = keyboard()
        k.focus(text = "hello")
        k.menu(1) // All
        assertEquals(0, k.edit.selectionStart); assertEquals(5, k.edit.selectionEnd)
        k.menu(3) // Copy
        assertEquals("hello", k.clip())
        k.edit.setSelection(5)
        k.menu(4) // Paste
        assertEquals("hellohello", k.text)
    }

    @Test fun `cut removes the selection and keeps it on the clipboard`() {
        val k = keyboard()
        k.focus(text = "hello world")
        k.menu(1)
        k.menu(2) // Cut
        assertEquals("", k.text)
        assertEquals("hello world", k.clip())
        k.menu(4)
        assertEquals("hello world", k.text)
    }

    @Test fun `select, then slide, stretches the selection`() {
        val k = keyboard("full")
        k.focus(text = "hello world")
        k.menu(0) // Select on
        assertTrue(k.view.selecting)
        k.slide(-60f)
        assertEquals(5, minOf(k.edit.selectionStart, k.edit.selectionEnd))
        assertEquals(11, maxOf(k.edit.selectionStart, k.edit.selectionEnd))
        k.menu(3) // Copy
        assertEquals(" world", k.clip())
        assertFalse("copying ends selecting", k.view.selecting)
        // Sliding moves the cursor again.
        k.slide(30f)
        assertEquals(k.edit.selectionStart, k.edit.selectionEnd)
    }

    @Test fun `lifting on the grass with the menu open does nothing, and so does a key tap after Select`() {
        val k = keyboard()
        k.focus(text = "hello")
        k.menu(-1)
        assertEquals("hello", k.text)
        assertEquals(null, k.clip())
        assertFalse(k.view.selecting)
        k.menu(0)
        assertTrue(k.view.selecting)
        k.layoutView()
        k.tap(k.view.width / 2f, k.view.overlayH + 20f) // a tap on the keys
        assertFalse(k.view.selecting)
    }

    @Test fun `the menu draws its five items`() {
        val k = keyboard()
        k.focus()
        k.layoutView()
        val t = android.os.SystemClock.uptimeMillis()
        k.view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, k.view.width / 2f, k.view.height - 4f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        val words = ArrayList<String>()
        val rec = object : Canvas(Bitmap.createBitmap(k.view.width, k.view.height, Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: Paint) { words += text; super.drawText(text, x, y, paint) }
        }
        k.view.draw(rec)
        for (w in listOf("Select", "All", "Cut", "Copy", "Paste")) assertTrue("$w in $words", w in words)
    }

    // ---- the cursor bubble ------------------------------------------------------------------------

    private fun Keyboard.wordsDrawn(): List<String> {
        layoutView()
        val words = ArrayList<String>()
        val rec = object : Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: Paint) { words += text; super.drawText(text, x, y, paint) }
        }
        view.draw(rec)
        return words
    }

    @Test fun `sliding shows the text around the cursor, and it goes when the finger lifts`() {
        val k = keyboard()
        k.focus(text = "hello world again")
        assertFalse("nothing before a slide", k.wordsDrawn().any { it.contains("hello") })
        k.slide(-60f, lift = false) // six characters left: before "again"... cursor after "hello world"
        val words = k.wordsDrawn()
        assertTrue(words.toString(), words.any { it == "hello world" })
        assertTrue(words.toString(), words.any { it == " again" })
        // Sliding further keeps the bubble in step.
        k.slide(-60f, fromX = k.view.width / 2f - 60f, lift = false)
        assertTrue(k.wordsDrawn().toString(), k.wordsDrawn().any { it.startsWith("hello") && it.length < 11 })
        // Lifting removes it.
        val t = android.os.SystemClock.uptimeMillis()
        k.view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, 0f, k.view.height - 4f, 0))
        k.view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, k.view.height - 4f, 0))
    }

    @Test fun `a selection is shown highlighted in the bubble`() {
        val k = keyboard("full")
        k.focus(text = "hello world")
        k.menu(0)
        k.slide(-50f, lift = false)
        val words = k.wordsDrawn()
        assertTrue(words.toString(), words.any { it == "world".takeLast(5) } || words.any { it.trim() == "world" })
    }
}

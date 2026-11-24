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
    /** [space]: the space under the keys, in dp (with Android's navigation bar it is taller on a phone). */
    private fun keyboard(mode: String = "mini", space: Int = 40) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to space))
            .also { it.waitReady() }

    /** A finger on the grass (the bottom pixels of the view) sliding by [dx] pixels in small moves. */
    private fun Keyboard.slide(dx: Float, fromX: Float = view.width * 0.8f, y: Float? = null, lift: Boolean = true) {
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
            k.slide(-28f) // one character for every 4 dp
            assertEquals(mode, 4, k.edit.selectionStart)
            k.slide(20f)
            assertEquals(mode, 9, k.edit.selectionStart)
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
        // Never a key press past the text: at its ends an arrow key can leave the field for another one.
        assertEquals("two characters each way", 4, k.arrowPresses)
    }

    @Test fun `a word in progress is finished as typed before the cursor moves`() {
        val k = keyboard("full")
        k.focus()
        k.type("zxqvk")
        k.slide(-18f)
        assertEquals("zxqvk", k.text)
        assertEquals(1, k.edit.selectionStart)
        assertEquals("the word was finished", 1, k.ime.learnedCount("zxqvk"))
        // Typing carries on from the new place.
        k.type("a")
        assertEquals("zaxqvk", k.text)
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
        val tops = ArrayList<Pair<Int, Float>>() // colour, top
        val paths = ArrayList<Int>()
        var offset = 0f
        override fun translate(dx: Float, dy: Float) { offset += dy; super.translate(dx, dy) }
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) { rects += Triple(paint.color, b + offset, r - l); tops += paint.color to t + offset; super.drawRect(l, t, r, b, paint) }
        override fun drawPath(path: Path, paint: Paint) { paths += paint.color; super.drawPath(path, paint) }
    }

    private fun isGreen(c: Int) = ((c shr 8) and 0xff) > ((c shr 16) and 0xff) + 10 && ((c shr 8) and 0xff) > (c and 0xff) + 10

    private fun isBrown(c: Int) = ((c shr 16) and 0xff) > ((c shr 8) and 0xff) && ((c shr 16) and 0xff) > (c and 0xff)

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
                assertTrue("$mode/$panel green body", body.isNotEmpty())
                // The soil under it reaches down to the bottom of the view.
                val soil = rec.rects.filter { isBrown(it.first) && it.third >= k.view.width.toFloat() }
                assertTrue("$mode/$panel soil", soil.isNotEmpty())
                assertEquals("$mode/$panel: down to the bottom of the view", k.view.height.toFloat(), soil.last().second, 1f)
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

    @Test fun `Word selects the word at the cursor`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            k.focus(text = "hello big world")
            k.menu(0) // at the end of "world"
            assertEquals(mode, "world", selected(k))
            k.edit.setSelection(7) // in the middle of "big"
            k.menu(0)
            assertEquals(mode, "big", selected(k))
            k.edit.setSelection(5) // between "hello" and the space: the word before it
            k.menu(0)
            assertEquals(mode, "hello", selected(k))
            assertEquals("the text is left alone", "hello big world", k.text)
            k.menu(3) // Copy
            assertEquals("hello", k.clip())
        }
    }

    private fun selected(k: Keyboard) = k.text.substring(minOf(k.edit.selectionStart, k.edit.selectionEnd), maxOf(k.edit.selectionStart, k.edit.selectionEnd))

    @Test fun `Word takes apostrophes and hyphens with the word, and does nothing between spaces`() {
        val k = keyboard()
        k.focus(text = "it's a self-aware  dog")
        k.edit.setSelection(3)
        k.menu(0)
        assertEquals("it's", selected(k))
        k.edit.setSelection(12)
        k.menu(0)
        assertEquals("self-aware", selected(k))
        k.edit.setSelection(18) // between two spaces
        k.menu(0)
        assertEquals("", selected(k))
    }

    @Test fun `with a word selected, sliding stretches the selection, and a plain slide moves the cursor`() {
        val k = keyboard()
        k.focus(text = "hello big world")
        k.edit.setSelection(7)
        k.menu(0) // "big"
        k.slide(22f) // 5 characters to the right: past "big", into " wor"
        assertTrue(selected(k), selected(k).startsWith("big"))
        assertTrue(selected(k), selected(k).length > 3)
        // Never past the end of the text.
        k.slide(400f)
        assertEquals(15, maxOf(k.edit.selectionStart, k.edit.selectionEnd))
        // A plain slide (nothing selected) moves the cursor instead.
        val k2 = keyboard()
        k2.focus(text = "hello world")
        k2.slide(-20f)
        assertEquals(k2.edit.selectionStart, k2.edit.selectionEnd)
    }

    @Test fun `lifting on the grass with the menu open does nothing`() {
        val k = keyboard()
        k.focus(text = "hello")
        k.menu(-1)
        assertEquals("hello", k.text)
        assertEquals(null, k.clip())
        assertEquals(k.edit.selectionStart, k.edit.selectionEnd)
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
        for (w in listOf("Word", "All", "Cut", "Copy", "Paste")) assertTrue("$w in $words", w in words)
    }

    // ---- the navigation bar ---------------------------------------------------------------------

    @Test fun `the soil lies under android's navigation bar, so the grass above it is the part to touch`() {
        // Without a bar, the soil starts a few pixels under the grass's blades.
        val plain = Recorder(Bitmap.createBitmap(1000, 100, Bitmap.Config.ARGB_8888))
        com.brownllama.continuum.keyboard.Grass(2f).draw(plain, Paint(), 1000f, 0f, 100f, true, 0xFFFFFFFF.toInt())
        assertEquals(16f, plain.tops.first { isBrown(it.first) }.second, 0.5f)
        // With the bar's last 40 px given, it starts there.
        val withBar = Recorder(Bitmap.createBitmap(1000, 100, Bitmap.Config.ARGB_8888))
        com.brownllama.continuum.keyboard.Grass(2f).draw(withBar, Paint(), 1000f, 0f, 100f, true, 0xFFFFFFFF.toInt(), 60f)
        assertEquals(60f, withBar.tops.first { isBrown(it.first) }.second, 0.5f)
        // In the view: the soil starts where the bar does (the bottom 40 px).
        val k = keyboard()
        k.focus()
        k.view.systemBottom = 40
        val rec = grassOf(k)
        val soilTop = rec.tops.first { isBrown(it.first) }.second
        assertEquals(k.view.height - 40f, soilTop, 2.5f)
        // A slide on the grass just above the bar still moves the cursor.
        k.focus(text = "hello world")
        k.layoutView()
        k.slide(-24f, y = k.view.height - 40f - 6f)
        assertEquals(5, k.edit.selectionStart)
    }

    @Test fun `there is more room for the grass by default`() {
        assertTrue(Prefs.BOTTOM_SPACE_DEFAULT >= 20)
    }

    // ---- Android's gesture zones -----------------------------------------------------------------

    @Test fun `the back swipe is kept off the keys and the grass`() {
        val k = keyboard()
        k.focus()
        k.layoutView()
        val rects = k.view.systemGestureExclusionRects
        assertEquals(1, rects.size)
        assertEquals(0, rects[0].left)
        assertEquals(k.view.width, rects[0].right)
        assertEquals("down to the bottom of the view when there is no bar", k.view.height, rects[0].bottom)
        // With a bar, the grass above it is covered and the bar is not.
        k.view.systemBottom = 40
        k.view.requestLayout()
        k.layoutView()
        assertEquals(k.view.height - 40, k.view.systemGestureExclusionRects[0].bottom)
        assertTrue("never more than 200 dp", k.view.systemGestureExclusionRects[0].height() <= 200f * k.context.resources.displayMetrics.density + 1)
    }

    @Test fun `android's whole bottom gesture zone is kept clear, even when it is taller than the bar`() {
        val k = keyboard()
        k.focus()
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 24))
            .setInsets(android.view.WindowInsets.Type.mandatorySystemGestures(), android.graphics.Insets.of(0, 0, 0, 60))
            .build()
        k.view.dispatchApplyWindowInsets(insets)
        assertEquals(60, k.view.systemBottom)
        k.layoutView()
        // The grass above is the touchable part, the soil under it covers the zone.
        val rec = grassOf(k)
        assertEquals(k.view.height - 60f, rec.tops.first { isBrown(it.first) }.second, 2.5f)
    }

    // ---- where the cursor slide starts, and the soil setting ----------------------------------------

    @Test fun `the cursor slide only starts in the bottom right of the grass`() {
        val k = keyboard()
        // From the middle or the left of the grass it is the swipe it always was (left: delete a word).
        k.focus(text = "hello world")
        k.layoutView()
        k.slide(-250f, fromX = k.view.width * 0.3f)
        assertTrue("a swipe, not the cursor: " + k.text, k.text.length < 11)
        val k2 = keyboard()
        k2.focus(text = "hello world")
        k2.slide(-250f, fromX = k2.view.width * 0.55f)
        assertTrue("the middle is not the cursor zone either", k2.text.length < 11 && k2.edit.selectionStart == k2.text.length)
        // From the right edge region it moves the cursor.
        val k3 = keyboard()
        k3.focus(text = "hello world")
        k3.slide(-28f, fromX = k3.view.width * 0.65f)
        assertEquals(4, k3.edit.selectionStart)
        assertEquals("hello world", k3.text)
    }

    @Test fun `sliding is fast, a character for every few dp`() {
        val k = keyboard()
        k.focus(text = "a".repeat(100))
        k.slide(-100f)
        assertEquals(75, k.edit.selectionStart) // 25 characters in 100 dp
    }

    @Test fun `the soil can be made thicker, and the grass you can touch stays the same`() {
        val plain = keyboard()
        plain.focus()
        plain.layoutView()
        val h0 = plain.view.height
        val thick = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 40, Prefs.SOIL_SPACE to 24)).also { it.waitReady(); it.focus() }
        thick.layoutView()
        assertEquals(24f, thick.view.soilDp, 0f)
        assertEquals("24 dp more soil under the same grass", h0 + 24, thick.view.height)
        val rec = grassOf(thick)
        assertEquals(thick.view.height - 24f, rec.tops.first { isBrown(it.first) }.second, 2.5f)
        // The default is no extra soil.
        assertEquals(0, Prefs.SOIL_SPACE_DEFAULT)
        // The slide still works on the grass above it.
        thick.focus(text = "hello world")
        thick.layoutView()
        thick.slide(-28f, y = thick.view.height - 24f - 6f)
        assertEquals(4, thick.edit.selectionStart)
    }
}

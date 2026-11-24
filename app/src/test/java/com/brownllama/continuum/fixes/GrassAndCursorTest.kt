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
import org.junit.Assert.assertNull
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
    /** [space]: the space below the keyboard in dp, which is the height of the grass. */
    private fun keyboard(mode: String = "mini", space: Int = 24) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to space))
            .also { it.waitReady() }

    /** A touch point just inside the grass (a few pixels under the keys), in the view's own pixels. */
    private fun Keyboard.grassY() = view.overlayH + view.gifHeaderH + view.keyboardHeight + 6f

    /** A finger on the grass (just under the keys) sliding by [dx] pixels in small moves. */
    private fun Keyboard.slide(dx: Float, fromX: Float = view.width * 0.8f, y: Float? = null, lift: Boolean = true) {
        layoutView() // the view grows when full mode opens
        val y = y ?: grassY()
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
        k.tap(k.view.width / 2f, k.grassY())
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
        val bounds = ArrayList<android.graphics.RectF>() // of each path, in view coordinates
        var offset = 0f
        override fun translate(dx: Float, dy: Float) { offset += dy; super.translate(dx, dy) }
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) { rects += Triple(paint.color, b + offset, r - l); tops += paint.color to t + offset; super.drawRect(l, t, r, b, paint) }
        override fun drawPath(path: Path, paint: Paint) {
            paths += paint.color
            bounds += android.graphics.RectF().also { path.computeBounds(it, true); it.offset(0f, offset) }
            super.drawPath(path, paint)
        }
    }

    private fun isGreen(c: Int) = ((c shr 8) and 0xff) > ((c shr 16) and 0xff) + 10 && ((c shr 8) and 0xff) > (c and 0xff) + 10

    /** The top and bottom of all the green blades drawn, in view coordinates (null when there are none). */
    private fun Recorder.blades(): Pair<Float, Float>? {
        val green = paths.indices.filter { isGreen(paths[it]) && !bounds[it].isEmpty }
        return if (green.isEmpty()) null else green.minOf { bounds[it].top } to green.maxOf { bounds[it].bottom }
    }

    /** The tops of the green rectangles that span the whole width: a flat ground, which there is none of any more. */
    private fun Recorder.groundTops(width: Int) = rects.indices.filter { isGreen(rects[it].first) && rects[it].third >= width.toFloat() }.map { tops[it].second }

    /** A keyboard with a navigation bar of [bar] px under the keys, laid out. */
    private fun Keyboard.withBar(bar: Int): Keyboard {
        focus(); view.navBottom = bar; view.gestureBottom = bar; view.requestLayout(); layoutView()
        return this
    }

    private fun grassOf(k: Keyboard): Recorder {
        k.layoutView()
        val rec = Recorder(Bitmap.createBitmap(k.view.width, k.view.height, Bitmap.Config.ARGB_8888))
        k.view.draw(rec)
        return rec
    }

    @Test fun `the space under the keyboard is grass in both modes and in the panels`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode).withBar(40)
            for (panel in listOf(KeyboardView.Panel.LETTERS, KeyboardView.Panel.SYMBOLS, KeyboardView.Panel.EMOJI, KeyboardView.Panel.CLIPBOARD)) {
                k.view.showPanel(panel)
                val rec = grassOf(k)
                assertTrue("$mode/$panel blades", rec.paths.any { isGreen(it) })
                // The blades stand on the bottom of the view (behind Android's bar), with no flat ground.
                assertEquals("$mode/$panel: standing on the bottom of the view", k.view.height.toFloat(), rec.blades()!!.second, 1f)
                assertTrue("$mode/$panel: no flat ground", rec.groundTops(k.view.width).isEmpty())
            }
        }
    }

    @Test fun `nothing at 0, then grass, and as it grows the keys go up and the blades get taller from the bottom edge`() {
        class Look(val height: Int, val blades: Boolean, val top: Float?, val bottom: Float?)
        // (Measured at once: all keyboards of a test share the settings, so a later one would restyle an earlier one.)
        fun look(space: Int, bar: Int = 40): Look {
            val k = Keyboard(mapOf(Prefs.BOTTOM_SPACE to space)).withBar(bar)
            val rec = grassOf(k)
            return Look(k.view.height, rec.paths.any { isGreen(it) }, rec.blades()?.first?.let { k.view.height - it }, rec.blades()?.second)
        }
        val none = look(0)
        assertFalse("0: no blades", none.blades)
        assertNull("0: no blades at all", none.top)
        val small = look(2)
        assertTrue("a little: a little grass", small.blades)
        assertTrue("a little: no taller than that, not the whole bar", small.top!! <= 2.5f)
        val base = look(24)
        assertTrue(base.blades)
        // The blades stand on the bottom of the view, whatever the bar's height, and are at most as tall as the space.
        assertEquals(base.height.toFloat(), base.bottom!!, 1f)
        assertTrue("at most 24 px tall, not the 40 px bar: ${base.top}", base.top!! <= 24f + 2.5f && base.top > 12f)
        // More space: the view is taller by that (the keys go up), and the grass is taller.
        val more = look(40)
        assertEquals(base.height + 16, more.height)
        assertTrue("taller: ${more.top}", more.top!! in 36f..42.5f)
        // The grass you can touch is all of it: a slide just inside its bottom edge works, under it does not.
        val inside = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 24)).also { it.waitReady(); it.focus(text = "hello world") }
        inside.layoutView()
        inside.slide(-28f, y = inside.view.overlayH + inside.view.keyboardHeight + 24f - 3f)
        assertEquals(4, inside.edit.selectionStart)
        val under = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 24)).also { it.waitReady(); it.focus(text = "hello world") }
        under.layoutView()
        under.slide(-28f, y = under.view.overlayH + under.view.keyboardHeight + 24f + 2f)
        assertTrue("under the grass is not grass, so the cursor did not slide", under.edit.selectionStart > 4)
    }

    // ---- edit menu ------------------------------------------------------------------------------

    /**
     * Holds a finger on the grass until the menu opens, slides up onto menu item [item] and lifts there
     * ([item] -1: lifts on the grass itself).
     */
    private fun Keyboard.menu(item: Int, fromX: Float = view.width / 2f) {
        layoutView()
        val y0 = grassY()
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
        k.view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, k.view.width / 2f, k.grassY(), 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        val words = ArrayList<String>()
        val rec = object : Canvas(Bitmap.createBitmap(k.view.width, k.view.height, Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: Paint) { words += text; super.drawText(text, x, y, paint) }
        }
        k.view.draw(rec)
        for (w in listOf("Word", "All", "Cut", "Copy", "Paste")) assertTrue("$w in $words", w in words)
    }

    // ---- the navigation bar ---------------------------------------------------------------------

    @Test fun `the blades stand on the bottom edge, behind android's navigation bar`() {
        // A strip 100 px tall: blades standing on its bottom, the tallest reaching its top, and no flat ground.
        val strip = Recorder(Bitmap.createBitmap(1000, 100, Bitmap.Config.ARGB_8888))
        com.brownllama.continuum.keyboard.Grass(2f).draw(strip, Paint(), 1000f, 0f, 100f, true, 0xFFFFFFFF.toInt())
        assertEquals(100f, strip.blades()!!.second, 0.5f)
        assertEquals(0f, strip.blades()!!.first, 2.5f)
        assertTrue(strip.groundTops(1000).isEmpty())
        // In the view (grass 24, a 40 px bar): the blades are on the bottom of the view, behind the bar.
        val k = keyboard().withBar(40)
        assertEquals(k.view.height.toFloat(), grassOf(k).blades()!!.second, 1f)
        // A slide on the grass just above it still moves the cursor.
        k.focus(text = "hello world")
        k.layoutView()
        k.slide(-24f, y = k.view.height - 40f - 6f)
        assertEquals(5, k.edit.selectionStart)
    }

    @Test fun `the default space is 30 dp of grass`() {
        assertEquals(30, Prefs.BOTTOM_SPACE_DEFAULT)
    }

    @Test fun `the back swipe is kept off the keys and the grass`() {
        val k = keyboard()
        k.focus()
        k.layoutView()
        val grassEnd = (k.view.overlayH + k.view.keyboardHeight + 24f * k.context.resources.displayMetrics.density).toInt()
        val rects = k.view.systemGestureExclusionRects
        assertEquals(1, rects.size)
        assertEquals(0, rects[0].left)
        assertEquals(k.view.width, rects[0].right)
        assertEquals("down to the end of the grass, not the ground under it", grassEnd, rects[0].bottom)
        // A bar under the grass changes nothing.
        k.view.navBottom = 40
        k.view.gestureBottom = 40
        k.view.requestLayout()
        k.layoutView()
        assertEquals(grassEnd, k.view.systemGestureExclusionRects[0].bottom)
        assertTrue("never more than 200 dp", k.view.systemGestureExclusionRects[0].height() <= 200f * k.context.resources.displayMetrics.density + 1)
    }

    @Test fun `android's whole bottom gesture zone is kept clear when there is grass, and only the bar's height when there is none`() {
        val k = keyboard()
        k.focus()
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 24))
            .setInsets(android.view.WindowInsets.Type.mandatorySystemGestures(), android.graphics.Insets.of(0, 0, 0, 60))
            .build()
        k.view.dispatchApplyWindowInsets(insets)
        assertEquals(24, k.view.navBottom)
        assertEquals("with grass, it stays clear of the whole gesture zone", 60, k.view.systemBottom)
        k.layoutView()
        // The blades stand on the bottom of the view, behind the whole zone.
        val rec = grassOf(k)
        assertEquals(k.view.height.toFloat(), rec.blades()!!.second, 1f)
        // With no space at all only the bar's own height is kept: no more gap than Android needs.
        k.view.bottomSpaceDp = 0f
        assertEquals(24, k.view.systemBottom)
    }

    @Test fun `with no space there is nothing under the keys but android's bar, and no grass shows, even when an animal visits`() {
        val k = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 0, Prefs.ANIMALS to true))
        k.waitReady(); k.focus(); k.layoutView()
        assertEquals("no gap: just the keys and the space for popups", Math.round(k.view.overlayH + k.view.keyboardHeight), k.view.height)
        val plain = grassOf(k)
        assertTrue("nothing green", plain.paths.none { isGreen(it) } && plain.rects.none { isGreen(it.first) && it.third >= k.view.width.toFloat() })
        assertTrue("no ground", plain.groundTops(k.view.width).isEmpty())
        // An animal in the middle of a visit: still no blades in front of it.
        val species = com.brownllama.continuum.keyboard.Species.ALL.first()
        k.view.animal.start(species, k.view.width.toFloat(), k.view.keyboardHeight, android.os.SystemClock.uptimeMillis())
        val visit = grassOf(k)
        assertTrue("no blades in front of its feet", visit.paths.none { isGreen(it) })
        // A bar: the plain bar's height only, not the gesture zone.
        k.view.navBottom = 24
        k.view.gestureBottom = 60
        assertEquals(24, k.view.systemBottom)
    }

    // ---- where the cursor slide starts ----------------------------------------

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

    // ---- word selection that works in any app, and a quiet keyboard ---------------------------------

    @Test fun `Word sets the selection by position, with no key presses, when the app says where the cursor is`() {
        val k = keyboard()
        k.focus(text = "hello big world")
        k.moveCursor(7)
        k.menu(0)
        assertEquals("big", selected(k))
        assertEquals("no arrow keys were needed", 0, k.arrowPresses)
        // Even when the app only reports its selection through updates and does not answer when asked.
        val k2 = keyboard()
        k2.reportsExtractedText = false
        k2.focus(text = "hello big world")
        k2.moveCursor(7)
        k2.menu(0)
        assertEquals("big", selected(k2))
        assertEquals(0, k2.arrowPresses)
    }

    @Test fun `Word still works in an app that says nothing about its cursor, with shifted arrow keys`() {
        val k = keyboard()
        k.reportsExtractedText = false
        k.focus(text = "hello big world")
        k.edit.setSelection(7) // the app does not tell the keyboard
        k.ime.selStart = -1
        k.ime.selEnd = -1
        k.menu(0)
        assertEquals("big", selected(k))
        assertTrue(k.arrowPresses > 0)
    }

    @Test fun `with a word selected, sliding moves its right end by position`() {
        val k = keyboard()
        k.focus(text = "hello big world")
        k.moveCursor(7)
        k.menu(0) // "big"
        assertEquals("big", selected(k))
        k.slide(22f) // 5 characters to the right
        assertEquals("big worl", selected(k))
        k.slide(-20f) // 5 back
        assertEquals("big", selected(k))
        assertEquals("no arrow keys were needed", 0, k.arrowPresses)
        // Shrinking stops at the start of the selection.
        k.slide(-400f)
        assertEquals("", selected(k))
        // Growing stops at the end of the text.
        val k2 = keyboard()
        k2.focus(text = "hello big world")
        k2.moveCursor(7)
        k2.menu(0)
        k2.slide(400f)
        assertEquals(15, maxOf(k2.edit.selectionStart, k2.edit.selectionEnd))
        assertEquals("big world", selected(k2))
    }

    @Test fun `a finger on the grass makes no glow and no key highlight, and a finger on the keys still does`() {
        val k = keyboard()
        k.focus()
        k.layoutView()
        val t = android.os.SystemClock.uptimeMillis()
        k.view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, k.view.width * 0.8f, k.grassY(), 0))
        assertTrue("no glow on the grass", k.view.glows.isEmpty())
        for (i in 1..10) k.view.onTouchEvent(MotionEvent.obtain(t, t + i * 8L, MotionEvent.ACTION_MOVE, k.view.width * 0.8f - i * 6f, k.grassY(), 0))
        assertTrue("still none while sliding", k.view.glows.isEmpty())
        k.view.onTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, k.view.width * 0.8f - 60f, k.grassY(), 0))
        k.idle()
        // On the letter row it glows, as it always did.
        k.view.onTouchEvent(MotionEvent.obtain(t + 200, t + 200, MotionEvent.ACTION_DOWN, k.view.width * 0.5f, k.view.overlayH + k.view.keyboardHeight - 20f, 0))
        assertFalse("a glow under a finger on the letters", k.view.glows.isEmpty())
    }

    @Test fun `the grass is blades, not a green slab, and the tallest reach the height of the setting`() {
        val k = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 40)).withBar(40)
        val rec = grassOf(k)
        assertTrue("no wide flat rectangle", rec.groundTops(k.view.width).isEmpty())
        // The blades are drawn as shapes of their own, the tallest 40 dp up from the bottom of the view.
        assertTrue(rec.paths.count { isGreen(it) } >= 2)
        assertEquals(k.view.height - 40f, rec.blades()!!.first, 2.5f)
    }

    @Test fun `a small value is a thin line of grass, not a block the size of android's bar`() {
        val k = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 1)).withBar(60)
        val rec = grassOf(k)
        val (top, bottom) = rec.blades()!!
        assertEquals(k.view.height.toFloat(), bottom, 1f)
        assertTrue("only about 1 dp of blades: ${bottom - top}", bottom - top <= 4f)
        assertTrue(rec.groundTops(k.view.width).isEmpty())
        val bare = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 0)).withBar(40)
        val plain = grassOf(bare)
        assertTrue(plain.blades() == null && plain.groundTops(bare.view.width).isEmpty())
    }
}

package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.CURSOR_MODE_TIMEOUT_MS
import com.brownllama.continuum.keyboard.Tree
import com.brownllama.continuum.keyboard.grassBottom
import com.brownllama.continuum.keyboard.grassTop
import com.brownllama.continuum.keyboard.treeBase
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
 * The tree in the grass is the switch of the cursor mode: tapped twice on its bottom it turns the mode
 * on, and the keys become a trackpad; tapped twice again (or after a while, or on leaving) it turns off.
 * Its leaves over the keys only show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TreeCursorTest {
    private fun keyboard(mode: String = "mini", space: Int = 25, bar: Int = 0) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to space))
            .also { it.view.systemBottom = bar; it.waitReady() }

    private fun Keyboard.wait(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun Keyboard.frame() = view.draw(Canvas(Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)))

    /** The keyboard with its text, drawn once so the tree has its place. */
    private fun Keyboard.withTree(text: String): Keyboard {
        focus(text = text)
        layoutView()
        frame()
        return this
    }

    private val Keyboard.offY get() = view.overlayH + view.gifHeaderH

    /** A finger on the tree's trunk, in the grass (view pixels). */
    private fun Keyboard.onTrunk(): Pair<Float, Float> {
        val t = view.tree
        return t.homeLeft + t.width / 2f to offY + t.homeTop + t.height - 3f
    }

    /** A finger on the tree's crown, over the keys (view pixels). */
    private fun Keyboard.onCrown(): Pair<Float, Float> {
        val t = view.tree
        return t.homeLeft + t.width / 2f to offY + t.homeTop + t.height * 0.3f
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

    /** Two quick taps on the tree's trunk. */
    private fun Keyboard.doubleTapTree() {
        val (x, y) = onTrunk()
        tapAt(x, y)
        wait(120)
        tapAt(x, y)
        wait(50)
    }

    /** Slides a finger from ([x], [y]) by ([dx], [dy]) in 20 small moves, then lets the frames run. */
    private fun Keyboard.slide(x: Float, y: Float, dx: Float, dy: Float, lift: Boolean = true) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) touch(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20)
        wait(50)
        if (lift) touch(MotionEvent.ACTION_UP, x + dx, y + dy)
        wait(50)
    }

    /** A spot on the letters, away from the tree. */
    private fun Keyboard.onKeys() = 150f to offY + view.keyboardHeight * 0.6f

    @Test fun `a tree stands at the right of the grass, above Android's gesture bar, rising over the keys`() {
        for (bar in listOf(0, 66)) {
            val k = keyboard(bar = bar).withTree("hello")
            val v = k.view
            val t = v.tree
            assertTrue("bar $bar: a tree fits", t.present)
            assertTrue("it rises over the keys, not just the grass", t.homeTop < v.grassTop - 10f)
            assertTrue("but not into the suggestion bar", t.homeTop >= v.barH)
            assertTrue("its base is above the gesture bar", t.homeTop + t.height <= v.grassBottom - bar + 0.5f)
            assertEquals("its base is where it can be touched", v.treeBase(), t.homeTop + t.height, v.spritePx)
            assertTrue("at the right", t.homeLeft > v.width * 0.7f)
            assertTrue("clear of the screen edge", t.homeLeft + t.width < v.width)
            assertTrue("about 60 dp tall, as far as there is room", t.height >= 40f * k.context.resources.displayMetrics.density)
        }
    }

    @Test fun `with no grass, or none above the gesture bar, there is no tree`() {
        assertFalse(keyboard(space = 0).withTree("hello").view.tree.present)
        assertFalse(keyboard(space = 2, bar = 66).withTree("hello").view.tree.present)
    }

    @Test fun `it is a little see-through, so the letters behind it can be read`() {
        assertTrue(Tree.ALPHA in 120..230)
    }

    @Test fun `a single tap on the tree only shakes it, so there is no mode and no letter typed`() {
        val k = keyboard().withTree("hello")
        val (x, y) = k.onTrunk()
        k.tapAt(x, y)
        k.wait(600)
        assertFalse(k.view.cursorMode)
        assertEquals("hello", k.text)
        // A second tap long after is a first tap again.
        k.tapAt(x, y)
        assertFalse(k.view.cursorMode)
    }

    @Test fun `two quick taps on the tree turn the cursor mode on, and two more turn it off`() {
        val k = keyboard().withTree("hello world")
        k.doubleTapTree()
        assertTrue(k.view.cursorMode)
        assertTrue("the tree shows it", k.view.tree.active)
        assertEquals("hello world", k.text)
        k.wait(600)
        k.doubleTapTree()
        assertFalse(k.view.cursorMode)
        assertFalse(k.view.tree.active)
    }

    @Test fun `in the cursor mode sliding anywhere on the keys moves the cursor`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode).withTree("hello world")
            k.doubleTapTree()
            val (x, y) = k.onKeys()
            k.slide(x, y, 28f, 0f) // one character for every 4 dp
            assertEquals("$mode: right, but already at the end", 11, k.edit.selectionStart)
            k.slide(x + 300f, y, -28f, 0f)
            assertEquals(mode, 4, k.edit.selectionStart)
            assertEquals("no letters were typed", "hello world", k.text)
            k.slide(x, y, 12f, 0f) // three back
            assertEquals(mode, 7, k.edit.selectionStart)
        }
    }

    @Test fun `in the cursor mode a tap on a key types nothing, and a swipe is not a swipe`() {
        val k = keyboard().withTree("hello world")
        k.doubleTapTree()
        val (x, y) = k.onKeys()
        k.tapAt(x, y)
        assertEquals("hello world", k.text)
        k.slide(x + 500f, y, -120f, 0f) // would be "delete a word"
        assertEquals("hello world", k.text)
        assertTrue(k.view.cursorMode)
    }

    @Test fun `the cursor moves are sent once a frame, not once per touch event`() {
        val k = keyboard().withTree("hello world")
        k.doubleTapTree()
        val (x, y) = k.onKeys()
        k.touch(MotionEvent.ACTION_DOWN, x + 500f, y)
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x + 500f - 1.4f * i, y)
        assertEquals("nothing sent yet", 11, k.edit.selectionStart)
        k.wait(50)
        assertEquals("all of it in one go", 4, k.edit.selectionStart)
    }

    @Test fun `in the cursor mode sliding up or down moves the cursor a line each 26 dp, as arrow keys`() {
        val k = keyboard().withTree("one\ntwo\nthree")
        val keys = ArrayList<Int>()
        val real = k.ime.ic!!
        k.ime.ic = object : android.view.inputmethod.InputConnectionWrapper(real, true) {
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) keys += event.keyCode
                return super.sendKeyEvent(event)
            }
        }
        k.doubleTapTree()
        val (x, y) = k.onKeys()
        k.slide(x, y, 0f, -52f) // two lines up
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_UP), keys)
        assertEquals("one\ntwo\nthree", k.text)
    }

    @Test fun `outside the cursor mode a slide on the keys is the swipe it always was`() {
        val k = keyboard().withTree("hello world")
        val (x, y) = k.onKeys()
        k.slide(x + 500f, y, -120f, 0f)
        assertEquals("hello ", k.text)
        assertFalse(k.view.cursorMode)
    }

    @Test fun `the leaves over the keys only show, so a tap and a hold there go to the key under them`() {
        val k = keyboard().withTree("hello")
        val (x, y) = k.onCrown()
        assertTrue("that spot is over the keys", k.view.tree.homeTop + k.view.tree.height * 0.3f < k.view.grassTop)
        k.tapAt(x, y)
        assertEquals("a letter was typed", 6, k.text.length)
        assertFalse(k.view.cursorMode)
        // Even two quick taps there are two letters, not the switch.
        k.tapAt(x, y)
        k.wait(100)
        k.tapAt(x, y)
        assertFalse(k.view.cursorMode)
        assertTrue("three letters in all, none of the taps was the switch", k.text.length >= 8)
        // A hold is the key's hold: mini mode's zoom opens.
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        k.wait(700)
        assertTrue(k.view.zoom != null)
        k.touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test fun `the mode turns itself off after a while, but not while a finger is sliding`() {
        val k = keyboard().withTree("hello world")
        k.doubleTapTree()
        assertTrue(k.view.cursorMode)
        k.wait(CURSOR_MODE_TIMEOUT_MS + 500)
        assertFalse("timed out", k.view.cursorMode)
        k.doubleTapTree()
        val (x, y) = k.onKeys()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        k.touch(MotionEvent.ACTION_MOVE, x + 20f, y)
        k.wait(CURSOR_MODE_TIMEOUT_MS * 2)
        assertTrue("a finger is down", k.view.cursorMode)
        k.touch(MotionEvent.ACTION_UP, x + 20f, y)
        k.wait(CURSOR_MODE_TIMEOUT_MS + 500)
        assertFalse(k.view.cursorMode)
    }

    @Test fun `hiding the keyboard turns the mode off`() {
        val k = keyboard().withTree("hello")
        k.doubleTapTree()
        assertTrue(k.view.cursorMode)
        k.view.keyboardHidden()
        assertFalse(k.view.cursorMode)
    }

    @Test fun `a touch beside the tree is not the tree`() {
        val k = keyboard().withTree("hello world")
        val t = k.view.tree
        val y = k.offY + t.homeTop + t.height - 3f
        k.tapAt(t.homeLeft + t.width + 80f, y)
        k.wait(100)
        k.tapAt(t.homeLeft + t.width + 80f, y)
        assertFalse(k.view.cursorMode)
    }

    @Test fun `the tree's picture has bumpy leaves, apples, branches and a trunk, at every size`() {
        for (rows in Tree.MIN_ROWS..40) {
            val cols = Tree.crownWidth(rows)
            val g = Tree.build(rows, cols)
            assertTrue("rows $rows: leaves", g.any { it == Tree.MID })
            assertTrue("rows $rows: shade", g.any { it == Tree.DARK } && g.any { it == Tree.LIGHT || it == Tree.HIGHLIGHT })
            assertTrue("rows $rows: outline", g.any { it == Tree.OUTLINE })
            assertTrue("rows $rows: trunk", g.any { it == Tree.TRUNK || it == Tree.TRUNK_LIGHT })
            assertTrue("rows $rows: stands on a trunk", (0 until cols).count { g[(rows - 1) * cols + it] != Tree.NONE } < cols)
            assertTrue("rows $rows: top row", (0 until cols).all { g[it] == Tree.NONE || g[it] == Tree.OUTLINE })
            if (rows >= 14) assertTrue("rows $rows: apples", g.any { it == Tree.APPLE })
        }
    }
}

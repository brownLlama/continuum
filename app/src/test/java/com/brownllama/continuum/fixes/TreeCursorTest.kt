package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
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

/** The tree on the right of the grass: pick it up, drag it to move the cursor, let go and it stops. */
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

    /** Where a finger lands on the standing tree, in the view's own pixels. */
    private fun Keyboard.onTree(): Pair<Float, Float> {
        val t = view.tree
        return t.homeLeft + t.width / 2f to view.overlayH + view.gifHeaderH + t.homeTop + t.height - 3f
    }

    private fun Keyboard.touch(action: Int, x: Float, y: Float, at: Long = android.os.SystemClock.uptimeMillis()) {
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    /** Puts a finger on the tree and drags it by ([dx], [dy]) in small moves; lifts unless [lift] is false. */
    private fun Keyboard.drag(dx: Float, dy: Float = 0f, lift: Boolean = true) {
        val (x, y) = onTree()
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) touch(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20)
        if (lift) {
            touch(MotionEvent.ACTION_UP, x + dx, y + dy)
            wait(400) // it slides back
            frame()
        }
        idle()
    }

    @Test fun `a tall tree stands at the right of the grass, above Android's gesture bar, rising over the keys`() {
        for (bar in listOf(0, 66)) {
            val k = keyboard(bar = bar).withTree("hello")
            val v = k.view
            val t = v.tree
            assertTrue("bar $bar: a tree fits", t.present)
            assertTrue("it rises over the keys, not just the grass", t.homeTop < v.grassTop - 10f)
            assertTrue("but not into the suggestion bar", t.homeTop >= v.barH)
            assertTrue("its base is above the gesture bar", t.homeTop + t.height <= v.grassBottom - bar + 0.5f)
            assertEquals("its base is where it can be touched", v.treeBase(), t.homeTop + t.height, v.spritePx)
            assertTrue("on the right", t.homeLeft > v.width * 0.7f)
            assertTrue("clear of the screen edge", t.homeLeft + t.width < v.width)
            assertTrue("about 80 dp tall, as far as there is room", t.height >= 40f * k.context.resources.displayMetrics.density)
        }
    }

    @Test fun `with no grass, or too little, there is no tree`() {
        assertFalse(keyboard(space = 0).withTree("hello").view.tree.present)
        assertFalse("no ground above the gesture bar", keyboard(space = 2, bar = 66).withTree("hello").view.tree.present)
    }

    @Test fun `picking the tree up and dragging it sideways moves the cursor, and letting go stops it`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode).withTree("hello world")
            assertEquals(11, k.edit.selectionStart)
            k.drag(-28f, lift = false) // one character for every 4 dp
            assertTrue(mode, k.view.tree.carried)
            assertEquals(mode, 4, k.edit.selectionStart)
            val (x, y) = k.onTree()
            k.touch(MotionEvent.ACTION_UP, x - 28f, y)
            assertFalse(mode, k.view.tree.carried)
            k.wait(400)
            k.frame()
            assertFalse("back home", k.view.tree.returning)
            assertEquals("hello world", k.text)
            assertEquals(4, k.edit.selectionStart)
            // Letting go stopped it: moving the finger now does nothing.
            k.touch(MotionEvent.ACTION_MOVE, x - 200f, y)
            assertEquals(4, k.edit.selectionStart)
        }
    }

    @Test fun `dragging it right moves the cursor right, and never past the text`() {
        val k = keyboard().withTree("hello world")
        k.moveCursor(0)
        k.drag(20f)
        assertEquals(5, k.edit.selectionStart)
        k.drag(1000f)
        assertEquals(11, k.edit.selectionStart)
        k.drag(-1000f)
        assertEquals(0, k.edit.selectionStart)
    }

    @Test fun `dragging it up or down moves the cursor a line each 26 dp, as arrow keys`() {
        val k = keyboard().withTree("one\ntwo\nthree")
        val keys = ArrayList<Int>()
        val real = k.ime.ic!!
        k.ime.ic = object : android.view.inputmethod.InputConnectionWrapper(real, true) {
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) keys += event.keyCode
                return super.sendKeyEvent(event)
            }
        }
        k.drag(0f, -52f) // two lines up
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_UP), keys)
        assertEquals("one\ntwo\nthree", k.text)
    }

    @Test fun `the tree is picked up by its crown over the keys too`() {
        val k = keyboard().withTree("hello world")
        val t = k.view.tree
        val x = t.homeLeft + t.width / 2f
        val y = k.view.overlayH + k.view.gifHeaderH + t.homeTop + t.height * 0.3f
        assertTrue("that spot is over the keys", t.homeTop + t.height * 0.3f < k.view.grassTop)
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        assertTrue(k.view.tree.carried)
    }

    @Test fun `a touch away from the tree is not the tree`() {
        val k = keyboard().withTree("hello world")
        val (_, y) = k.onTree()
        // Far to the left in the grass, and over the keys at the left.
        k.touch(MotionEvent.ACTION_DOWN, 100f, y)
        assertFalse(k.view.tree.carried)
        k.touch(MotionEvent.ACTION_UP, 100f, y)
        k.touch(MotionEvent.ACTION_DOWN, 100f, k.view.overlayH + k.view.keyboardHeight * 0.5f)
        assertFalse(k.view.tree.carried)
        k.touch(MotionEvent.ACTION_UP, 100f, k.view.overlayH + k.view.keyboardHeight * 0.5f)
    }

    @Test fun `a swipe on the keys is the swipe it always was`() {
        val k = keyboard().withTree("hello world")
        val y = k.view.overlayH + k.view.keyboardHeight * 0.6f
        val t = android.os.SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, 700f, y, t)
        for (i in 1..6) k.touch(MotionEvent.ACTION_MOVE, 700f - 20f * i, y, t + i * 10L)
        k.touch(MotionEvent.ACTION_UP, 580f, y, t + 80L)
        k.idle()
        assertFalse(k.view.tree.carried)
        assertEquals("hello ", k.text)
    }

    @Test fun `a second finger typing does not drop the tree`() {
        val k = keyboard().withTree("hello")
        val (x, y) = k.onTree()
        val t = android.os.SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, x, y, t)
        assertTrue(k.view.tree.carried)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y },
            MotionEvent.PointerCoords().apply { this.x = 100f; this.y = k.view.overlayH + 20f })
        val e = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        k.view.onTouchEvent(e)
        assertTrue("still held", k.view.tree.carried)
    }

    @Test fun `the tree's picture is round, outlined and has a trunk, at every size`() {
        for (rows in Tree.MIN_ROWS..40) {
            val cols = Tree.crownWidth(rows)
            val g = Tree.build(rows, cols)
            assertTrue("rows $rows: leaves", g.any { it == Tree.MID })
            assertTrue("rows $rows: outline", g.any { it == Tree.OUTLINE })
            assertTrue("rows $rows: trunk", g.any { it == Tree.TRUNK })
            // The lowest row is the trunk's foot (and its outline), nothing wider.
            assertTrue("rows $rows: stands on a trunk", (0 until cols).count { g[(rows - 1) * cols + it] != Tree.NONE } <= 4)
            // Nothing touches the picture's top edge: that row is the outline's.
            assertTrue("rows $rows: top row", (0 until cols).all { g[it] == Tree.NONE || g[it] == Tree.OUTLINE })
        }
    }
}

package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.TREE_EDGE_GAP_DP
import com.brownllama.continuum.keyboard.TREE_HOLD_MS
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
 * The tree in the grass: held and picked up, dragged to move the cursor, and let go to fall where it
 * is. Over the grass it is picked up at once; over the keys a quick tap is still the key's.
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

    /** A finger on the tree's base, in the grass (view pixels). */
    private fun Keyboard.onBase(): Pair<Float, Float> {
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

    /** Moves a finger from ([x], [y]) by ([dx], [dy]) in 20 small moves, then lets the frames run. */
    private fun Keyboard.moveBy(x: Float, y: Float, dx: Float, dy: Float) {
        for (i in 1..20) touch(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20)
        wait(50)
    }

    /** Puts a finger on the tree's base and drags it by ([dx], [dy]); lifts unless [lift] is false. */
    private fun Keyboard.drag(dx: Float, dy: Float = 0f, lift: Boolean = true) {
        val (x, y) = onBase()
        touch(MotionEvent.ACTION_DOWN, x, y)
        moveBy(x, y, dx, dy)
        if (lift) {
            touch(MotionEvent.ACTION_UP, x + dx, y + dy)
            wait(500) // it falls
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

    @Test fun `over the grass it is picked up at once, and dragging it sideways moves the cursor`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode).withTree("hello world")
            assertEquals(11, k.edit.selectionStart)
            val (x, y) = k.onBase()
            k.touch(MotionEvent.ACTION_DOWN, x, y)
            assertTrue("$mode: picked up at once", k.view.tree.carried)
            k.moveBy(x, y, -28f, 0f) // one character for every 4 dp
            assertEquals(mode, 4, k.edit.selectionStart)
            assertEquals("hello world", k.text)
        }
    }

    @Test fun `the cursor moves are sent once a frame, not once per touch event`() {
        val k = keyboard().withTree("hello world")
        val (x, y) = k.onBase()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) k.touch(MotionEvent.ACTION_MOVE, x - 1.4f * i, y)
        assertEquals("nothing sent yet", 11, k.edit.selectionStart)
        k.wait(50)
        assertEquals("all of it in one go", 4, k.edit.selectionStart)
    }

    @Test fun `the tree hangs below the finger, and follows it exactly`() {
        val k = keyboard().withTree("hello world")
        val (x, y) = k.onBase()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        val t = k.view.tree
        k.moveBy(x, y, -60f, -40f)
        k.wait(Tree.HANG_MS + 50)
        val now = SystemClock.uptimeMillis()
        assertEquals("centred on the finger", x - 60f, t.heldLeft(now) + t.width / 2f, 0.5f)
        assertEquals("its top just at the finger", y - 40f - k.offY - t.height * Tree.HELD_AT, t.heldTop(now), 0.5f)
    }

    @Test fun `letting go stops the cursor, and the tree falls straight down and stays where it landed`() {
        val k = keyboard().withTree("hello world")
        val t = k.view.tree
        val (x, y) = k.onBase()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        k.moveBy(x, y, -400f, -50f)
        val sel = k.edit.selectionStart
        k.touch(MotionEvent.ACTION_UP, x - 400f, y - 50f)
        assertFalse(t.carried)
        assertTrue("falling", t.falling)
        k.wait(500)
        k.frame()
        assertFalse("landed", t.falling)
        assertEquals("on the ground line", k.view.treeBase(), t.homeTop + t.height, k.view.spritePx)
        assertEquals("where it was let go", x - 400f, t.homeLeft + t.width / 2f, k.view.spritePx * 2)
        assertTrue("not back at the right", t.homeLeft < k.view.width * 0.7f)
        // The cursor stopped: moving the finger now does nothing.
        k.touch(MotionEvent.ACTION_MOVE, x - 600f, y)
        k.wait(50)
        assertEquals(sel, k.edit.selectionStart)
        // And it can be picked up again where it stands.
        val (x2, y2) = k.onBase()
        k.touch(MotionEvent.ACTION_DOWN, x2, y2)
        assertTrue(k.view.tree.carried)
    }

    @Test fun `it never lands closer to an edge than the gap`() {
        val gap = TREE_EDGE_GAP_DP * 1f
        for (dx in listOf(-5000f, 5000f)) {
            val k = keyboard().withTree("hello")
            val (x, y) = k.onBase()
            k.touch(MotionEvent.ACTION_DOWN, x, y)
            k.moveBy(x, y, dx, 0f)
            k.touch(MotionEvent.ACTION_UP, x + dx, y)
            k.wait(500)
            k.frame()
            val t = k.view.tree
            assertTrue("left $dx", t.homeLeft >= gap - 0.5f)
            assertTrue("right $dx", t.homeLeft + t.width <= k.view.width - gap + 0.5f)
        }
    }

    @Test fun `dragging it right moves the cursor right, and never past the text`() {
        val k = keyboard().withTree("hello world")
        k.moveCursor(0)
        k.drag(20f) // five characters
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

    @Test fun `over the keys a quick tap on the tree types the key under it`() {
        val k = keyboard().withTree("hello")
        val (x, y) = k.onCrown()
        assertTrue("that spot is over the keys", k.view.tree.homeTop + k.view.tree.height * 0.3f < k.view.grassTop)
        val t = SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, x, y, t)
        assertFalse("not picked up yet", k.view.tree.carried)
        k.touch(MotionEvent.ACTION_UP, x, y, t + 80)
        k.wait(TREE_HOLD_MS + 100)
        assertFalse(k.view.tree.carried)
        assertEquals("a letter was typed", 6, k.text.length)
    }

    @Test fun `over the keys a hold picks the tree up, and then it moves the cursor`() {
        val k = keyboard().withTree("hello world")
        val (x, y) = k.onCrown()
        k.touch(MotionEvent.ACTION_DOWN, x, y)
        k.wait(TREE_HOLD_MS + 60)
        assertTrue("held", k.view.tree.carried)
        k.moveBy(x, y, -28f, 0f)
        assertEquals(4, k.edit.selectionStart)
        k.touch(MotionEvent.ACTION_UP, x - 28f, y)
        assertEquals("no letter was typed by the hold", "hello world", k.text)
    }

    @Test fun `a swipe that starts on the tree over the keys is still the swipe`() {
        val k = keyboard().withTree("hello world")
        val (x, y) = k.onCrown()
        val t = SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, x, y, t)
        for (i in 1..6) k.touch(MotionEvent.ACTION_MOVE, x - 20f * i, y, t + i * 10L)
        k.touch(MotionEvent.ACTION_UP, x - 120f, y, t + 80L)
        k.wait(TREE_HOLD_MS + 100)
        assertFalse(k.view.tree.carried)
        assertEquals("hello ", k.text)
    }

    @Test fun `a touch away from the tree is not the tree`() {
        val k = keyboard().withTree("hello world")
        val (_, y) = k.onBase()
        k.touch(MotionEvent.ACTION_DOWN, 100f, y)
        assertFalse(k.view.tree.carried)
        k.touch(MotionEvent.ACTION_UP, 100f, y)
        k.touch(MotionEvent.ACTION_DOWN, 100f, k.offY + k.view.keyboardHeight * 0.5f)
        k.wait(TREE_HOLD_MS + 100)
        assertFalse(k.view.tree.carried)
    }

    @Test fun `a second finger typing does not drop the tree`() {
        val k = keyboard().withTree("hello")
        val (x, y) = k.onBase()
        val t = SystemClock.uptimeMillis()
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

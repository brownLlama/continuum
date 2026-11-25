package com.brownllama.continuum.fixes

import android.os.Looper
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.Species
import com.brownllama.continuum.keyboard.animalFeet
import com.brownllama.continuum.keyboard.animalFeetRows
import com.brownllama.continuum.keyboard.grassBottom
import com.brownllama.continuum.keyboard.grassHeight
import com.brownllama.continuum.keyboard.grassTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The animal that lives in the grass: pick it up, drag it to move the cursor, let go and it stops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PetCursorTest {
    private fun keyboard(mode: String = "mini", space: Int = 25) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false, Prefs.BOTTOM_SPACE to space, Prefs.ANIMALS to true))
            .also { it.waitReady() }

    private fun Keyboard.wait(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The keyboard with its animal standing in the grass. */
    private fun Keyboard.withAnimal(text: String): Keyboard {
        focus(text = text)
        layoutView()
        // The view is not on a screen in a test, so the keyboard's own check never runs: put the animal there as it would.
        if (view.grassHeight > 0) {
            view.animal.start(Species.ALEX, view.width.toFloat(), view.animalFeet(), android.os.SystemClock.uptimeMillis(),
                groundRows = view.animalFeetRows(), resident = true)
        }
        return this
    }

    /** Where a finger lands on the animal's legs, in the view's own pixels. */
    private fun Keyboard.onAnimal(): Pair<Float, Float> {
        val a = view.animal
        val x = a.gridLeft * view.spritePx + 12 * view.spritePx
        return x to view.overlayH + view.gifHeaderH + view.grassTop + 4f
    }

    private fun Keyboard.touch(action: Int, x: Float, y: Float, at: Long = android.os.SystemClock.uptimeMillis()) {
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    /** Puts a finger on the animal and drags it by ([dx], [dy]) in small moves; lifts unless [lift] is false. */
    private fun Keyboard.drag(dx: Float, dy: Float = 0f, lift: Boolean = true) {
        val (x, y) = onAnimal()
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..20) touch(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20)
        if (lift) {
            touch(MotionEvent.ACTION_UP, x + dx, y + dy)
            wait(300) // it falls back to the ground
        }
        idle()
    }

    @Test fun `an animal stands in the grass whenever the keyboard shows, and stays`() {
        val k = keyboard().withAnimal("hello")
        val a = k.view.animal
        assertTrue(a.visiting && a.resident)
        assertTrue("standing, not walking", a.resting)
        k.wait(5_000)
        assertTrue("still there", a.visiting)
    }

    @Test fun `with no grass there is no resident to start`() {
        val k = keyboard(space = 0).withAnimal("hello")
        assertFalse(k.view.animal.visiting)
        assertEquals(0, k.view.animalFeetRows())
    }

    @Test fun `picking the animal up and dragging it sideways moves the cursor, and letting go stops it`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode).withAnimal("hello world")
            assertEquals(11, k.edit.selectionStart)
            k.drag(-28f, lift = false) // one character for every 4 dp
            assertTrue(mode, k.view.animal.carried)
            assertEquals(mode, 4, k.edit.selectionStart)
            val (x, y) = k.onAnimal()
            k.touch(MotionEvent.ACTION_UP, x - 28f, y)
            k.wait(400)
            assertFalse(mode, k.view.animal.carried)
            k.view.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1080, 400, android.graphics.Bitmap.Config.ARGB_8888)))
            assertTrue("it stopped where it was put down", k.view.animal.resting)
            assertEquals("hello world", k.text)
            assertEquals(4, k.edit.selectionStart)
        }
    }

    @Test fun `dragging it to the right moves the cursor right, and never past the text`() {
        val k = keyboard().withAnimal("hello world")
        k.moveCursor(0)
        k.drag(20f) // five characters
        assertEquals(5, k.edit.selectionStart)
        k.drag(1000f)
        assertEquals(11, k.edit.selectionStart)
        k.drag(-1000f)
        assertEquals(0, k.edit.selectionStart)
    }

    @Test fun `a touch on the keys is never the animal, so typing and swipes are as they were`() {
        val k = keyboard().withAnimal("hello world")
        k.layoutView()
        // A swipe left on the letters deletes a word.
        val y = k.view.overlayH + k.view.keyboardHeight * 0.6f
        val t = android.os.SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, 700f, y, t)
        for (i in 1..6) k.touch(MotionEvent.ACTION_MOVE, 700f - 20f * i, y, t + i * 10L)
        k.touch(MotionEvent.ACTION_UP, 580f, y, t + 80L)
        k.idle()
        assertFalse(k.view.animal.carried)
        assertEquals("hello ", k.text)
    }

    @Test fun `a second finger typing does not drop the animal`() {
        val k = keyboard().withAnimal("hello")
        val (x, y) = k.onAnimal()
        val t = android.os.SystemClock.uptimeMillis()
        k.touch(MotionEvent.ACTION_DOWN, x, y, t)
        assertTrue(k.view.animal.carried)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y },
            MotionEvent.PointerCoords().apply { this.x = 100f; this.y = k.view.overlayH + 20f })
        val e = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        k.view.onTouchEvent(e)
        assertTrue("still held", k.view.animal.carried)
    }

    @Test fun `the animal stands above Android's gesture bar, where a finger can pick it up`() {
        val k = keyboard()
        k.view.systemBottom = 60
        k.withAnimal("hello")
        val v = k.view
        // Its feet are above the bar, not behind it.
        assertTrue(v.animalFeet() <= v.grassBottom - 60f)
        assertTrue(v.animal.visiting)
    }
}

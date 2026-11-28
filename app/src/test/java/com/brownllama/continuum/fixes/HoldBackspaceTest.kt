package com.brownllama.continuum.fixes

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Backspace: a tap deletes a letter; held, whole words go, faster and faster, until it's let go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HoldBackspaceTest {
    private val words = (1..40).joinToString(" ") { "word$it" }

    private fun keyboard(): Keyboard = Keyboard(mapOf(Prefs.MODE to "full")).apply { focus(text = words) }

    private fun backspaceEvent(k: Keyboard, action: Int) {
        val key = (k.view.keys + k.view.barKeys).first { it.kind == Kind.BACKSPACE }
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, key.rect.centerX(), k.view.overlayH + key.rect.centerY(), 0)
        k.view.onTouchEvent(e)
        e.recycle()
    }

    private fun hold(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun wordsLeft(k: Keyboard) = k.text.split(' ').count { it.isNotEmpty() }

    // A tap's one letter goes to the app as a key event, which the test's field doesn't see; what
    // is checked here is that it deletes no word, then or later.
    @Test fun `a tap deletes no whole word`() {
        val k = keyboard()
        backspaceEvent(k, MotionEvent.ACTION_DOWN)
        backspaceEvent(k, MotionEvent.ACTION_UP)
        hold(2000)
        assertEquals(words, k.text)
    }

    @Test fun `held, whole words go, and they stop when it's let go`() {
        val k = keyboard()
        backspaceEvent(k, MotionEvent.ACTION_DOWN)
        hold(390)
        assertEquals("no word yet, before the hold counts", words, k.text)
        hold(20)
        assertEquals("then the last word", words.substringBeforeLast(' ') + " ", k.text)
        hold(250)
        assertEquals("then the word before it", 38, wordsLeft(k))
        backspaceEvent(k, MotionEvent.ACTION_UP)
        val atLift = k.text
        hold(3000)
        assertEquals("nothing more once let go", atLift, k.text)
    }

    @Test fun `the longer it's held, the faster words go, until everything is gone`() {
        val k = keyboard()
        backspaceEvent(k, MotionEvent.ACTION_DOWN)
        hold(1400)
        val firstSecond = 40 - wordsLeft(k)
        hold(1000)
        val nextSecond = 40 - wordsLeft(k) - firstSecond
        assertTrue("faster later: $firstSecond then $nextSecond", nextSecond > firstSecond)
        hold(5000)
        assertEquals("", k.text.trim())
        backspaceEvent(k, MotionEvent.ACTION_UP)
    }
}

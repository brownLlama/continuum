package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TouchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What the touch model learns from kept words, and what it refuses to learn. */
class TouchModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val qwerty = KeyLayout.QWERTY
    private val qwertz = KeyLayout.QWERTZ
    private fun model(file: File = File(tmp.root, "touch.tsv")) = TouchModel(file)

    /** [word] tapped in mini mode, each tap [dx] slots off its letter, plus [noise]. */
    private fun mini(word: String, dx: Float, noise: Float = 0f, rnd: Random? = null, layout: KeyLayout = qwerty) =
        word.map { Tap(layout.miniSlot[it - 'a'] + dx + (rnd?.nextGaussian()?.toFloat() ?: 0f) * noise, 0f) }

    private fun full(word: String, dx: Float, dy: Float, layout: KeyLayout = qwerty) =
        word.map { Tap(layout.fullX[it - 'a'] + dx, layout.fullY[it - 'a'] + dy) }

    private val words = listOf("hello", "there", "about", "would", "people", "quiz", "jumpy", "vexing", "black", "frown")

    @Test fun `nothing learned, nothing to say`() {
        assertNull(model().shape(qwerty, Mode.MINI))
    }

    @Test fun `a steady offset is learned, and grows with the number of taps`() {
        val m = model()
        repeat(4) { for (w in words) assertTrue(m.learn(qwerty, Mode.MINI, mini(w, 0.6f), w)) }
        val few = m.shape(qwerty, Mode.MINI)!!.dx['o' - 'a']
        repeat(30) { for (w in words) m.learn(qwerty, Mode.MINI, mini(w, 0.6f), w) }
        val many = m.shape(qwerty, Mode.MINI)!!.dx['o' - 'a']
        assertTrue("few $few many $many", few in 0.1f..many)
        assertEquals(0.6f, many, 0.08f)
        // Letters never typed move with the rest, by the shift all taps share.
        assertEquals(0.6f, m.shape(qwerty, Mode.MINI)!!.dx['k' - 'a'], 0.08f)
    }

    @Test fun `full mode learns across and down`() {
        val m = model()
        repeat(40) { for (w in words) m.learn(qwerty, Mode.FULL, full(w, 0.1f, 0.3f), w) }
        val s = m.shape(qwerty, Mode.FULL)!!
        assertEquals(0.1f, s.dx['e' - 'a'], 0.03f)
        assertEquals(0.3f, s.dy['e' - 'a'], 0.04f)
    }

    @Test fun `offsets stay within their bounds`() {
        val m = model()
        // Each word a little further off than the last: every tap stays close to where its letter is now expected.
        var off = 0f
        repeat(400) {
            off = minOf(off + 0.05f, 3f)
            for (w in words) m.learn(qwerty, Mode.MINI, mini(w, off), w)
        }
        val s = m.shape(qwerty, Mode.MINI)!!
        for (c in "helotabuwdp".map { it - 'a' }) assertTrue("${'a' + c}: ${s.dx[c]}", abs(s.dx[c]) <= 0.75f * 1.4f + 1e-4f)
        // Wide scatter around the centres teaches no offset to speak of.
        val wide = model(File(tmp.root, "wide.tsv"))
        val rnd = Random(1)
        repeat(300) { for (w in words) wide.learn(qwerty, Mode.MINI, mini(w, 0f, 1.2f, rnd), w) }
        for (c in "helotabuwdp".map { it - 'a' }) assertEquals(0f, wide.shape(qwerty, Mode.MINI)!!.dx[c], 0.2f)
    }

    @Test fun `a shift of all taps is learned for letters not typed yet too`() {
        val m = model()
        repeat(20) { for (w in words) m.learn(qwerty, Mode.FULL, full(w, 0f, 0.3f), w) }
        // "k" is in none of the words, yet it is expected lower like the rest.
        assertTrue(m.shape(qwerty, Mode.FULL)!!.dy['k' - 'a'] > 0.2f)
    }

    @Test fun `a word whose taps don't fit its letters teaches nothing`() {
        val m = model()
        // "hello" typed as "hlelo": two taps far from their letters.
        val swapped = mini("hlelo", 0f)
        assertFalse(m.learn(qwerty, Mode.MINI, swapped, "hello"))
        // A far-off tap anywhere in the word rejects it whole.
        val oneFar = mini("hello", 0f).toMutableList().also { it[2] = Tap(it[2].x + 5f, 0f) }
        assertFalse(m.learn(qwerty, Mode.MINI, oneFar, "hello"))
        assertNull(m.shape(qwerty, Mode.MINI))
    }

    @Test fun `taps that don't line up with the word, or say nothing about the finger, are not used`() {
        val m = model()
        assertFalse("too few taps", m.learn(qwerty, Mode.MINI, mini("hello", 0f).take(4), "hello"))
        assertFalse("one letter", m.learn(qwerty, Mode.MINI, mini("a", 0f), "a"))
        assertFalse("not a key", m.learn(qwerty, Mode.MINI, mini("hello", 0f), "hellö"))
        val exact = "hello".map { Tap(qwerty.miniSlot[it - 'a'].toFloat(), 0f, exact = it - 'a') }
        assertFalse("picked exactly", m.learn(qwerty, Mode.MINI, exact, "hello"))
        // Exact taps are left out, the others still count.
        val mixed = exact.take(3) + mini("lo", 0.5f)
        assertTrue(m.learn(qwerty, Mode.MINI, mixed, "hello"))
        assertNotNull(m.shape(qwerty, Mode.MINI))
        // "h" was picked exactly: it has nothing of its own, only a little of the shared shift.
        assertTrue(m.shape(qwerty, Mode.MINI)!!.dx['h' - 'a'] < 0.1f)
    }

    @Test fun `a fixed letter teaches where its tap really was`() {
        val m = model()
        // The thumb meant "e" but landed on "r", every time; the user deleted it and typed "e".
        repeat(60) { assertTrue(m.learnTap(qwerty, Mode.FULL, Tap(qwerty.fullX['r' - 'a'], qwerty.fullY['r' - 'a']), 'e')) }
        assertTrue(m.shape(qwerty, Mode.FULL)!!.dx['e' - 'a'] > 0.3f)
        // A tap far from the letter typed in its place (deleting and typing another word) is not used.
        assertFalse(m.learnTap(qwerty, Mode.FULL, Tap(qwerty.fullX['p' - 'a'], qwerty.fullY['p' - 'a']), 'a'))
        assertFalse(m.learnTap(qwerty, Mode.FULL, Tap(0f, 0f, exact = 4), 'e'))
    }

    @Test fun `a model gone wrong can find its way back to the key centres`() {
        val m = model()
        repeat(60) { for (w in words) m.learn(qwerty, Mode.FULL, full(w, 0f, 0.35f), w) }
        // Now the taps land on the centres; far from where the model expects them, but on the keys.
        repeat(60) { for (w in words) assertTrue(m.learn(qwerty, Mode.FULL, full(w, 0f, 0f), w)) }
        assertEquals(0f, m.shape(qwerty, Mode.FULL)!!.dy['e' - 'a'], 0.06f)
    }

    @Test fun `each layout and mode is learned on its own`() {
        val m = model()
        repeat(40) { for (w in words) m.learn(qwerty, Mode.MINI, mini(w, 0.6f), w) }
        assertNull(m.shape(qwerty, Mode.FULL))
        assertNull(m.shape(qwertz, Mode.MINI))
        repeat(40) { for (w in words) m.learn(qwertz, Mode.MINI, mini(w, -0.4f, layout = qwertz), w) }
        assertEquals(0.6f, m.shape(qwerty, Mode.MINI)!!.dx['o' - 'a'], 0.08f)
        assertEquals(-0.4f, m.shape(qwertz, Mode.MINI)!!.dx['o' - 'a'], 0.08f)
    }

    @Test fun `it follows a change of grip`() {
        val m = model()
        repeat(60) { for (w in words) m.learn(qwerty, Mode.MINI, mini(w, 0.6f), w) }
        repeat(60) { for (w in words) m.learn(qwerty, Mode.MINI, mini(w, -0.3f), w) }
        assertEquals(-0.3f, m.shape(qwerty, Mode.MINI)!!.dx['o' - 'a'], 0.1f)
    }

    @Test fun `what was learned is saved, read back, and forgotten on clear`() {
        val file = File(tmp.root, "touch.tsv")
        val m = model(file)
        repeat(40) { for (w in words) m.learn(qwerty, Mode.FULL, full(w, 0.1f, 0.3f), w) }
        m.save()
        val before = m.shape(qwerty, Mode.FULL)!!
        val again = model(file).shape(qwerty, Mode.FULL)!!
        assertEquals(before.dx.toList(), again.dx.toList())
        assertEquals(before.dy.toList(), again.dy.toList())
        m.clear()
        assertNull(m.shape(qwerty, Mode.FULL))
        assertNull(model(file).shape(qwerty, Mode.FULL))
    }

    @Test fun `a damaged file is read as far as it makes sense`() {
        val file = File(tmp.root, "touch.tsv")
        file.writeText("a\tqwerty/mini\tnope\t1\t1\nl\tqwerty/mini\to\t50\t0.4\t0\nl\tqwerty/mini\tö\t5\t1\t1\nl\tqwerty/mini\te\t50\tNaN\t0\ngarbage\n")
        val s = model(file).shape(qwerty, Mode.MINI)!!
        // The broken shared line is skipped (it stays 0); "o" is read; "ö" and the NaN line are not.
        assertEquals(0.4f * 50 / 80 * 1.4f, s.dx['o' - 'a'], 1e-4f)
        assertEquals(0f, s.dx['e' - 'a'], 0f)
    }

    @Test fun `read and written in the background, it learns the same`() {
        val file = File(tmp.root, "touch.tsv")
        val io = Executors.newSingleThreadExecutor()
        try {
            val m = TouchModel(file, io)
            repeat(40) { for (w in words) m.learn(qwerty, Mode.MINI, mini(w, 0.6f), w) }
            m.save()
            assertEquals(0.6f, TouchModel(file, io).shape(qwerty, Mode.MINI)!!.dx['o' - 'a'], 0.08f)
        } finally {
            io.shutdown()
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

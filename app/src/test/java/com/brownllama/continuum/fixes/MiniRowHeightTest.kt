package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TouchModel
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Mini mode's letters are drawn staggered by keyboard row, so how high a tap lands says which row
 * it was for (Q, A and Y share a column). The touch model learns where each row's taps land: a
 * typist who aims at the rows is read better once it has, and one who doesn't is read the same.
 */
class MiniRowHeightTest {
    private val layout = KeyLayout.QWERTZ

    private fun decoder() = Decoder(DICT, UserModel(File.createTempFile("user", ".tsv").also { it.delete() }))
    private fun touchFile() = File.createTempFile("touch", ".tsv").also { it.delete() }

    /** A typist: 0.8 slots of scatter across; [sy] rows up and down around their row's height, or around the home row if not [aims]. */
    private fun taps(word: String, aims: Boolean, sy: Float, rnd: Random) = word.map {
        val c = it - 'a'
        val y = ((if (aims) layout.rowOf[c].toFloat() else 1f) + rnd.nextGaussian().toFloat() * sy).coerceIn(-1f, 3f)
        Tap(layout.miniSlot[c] + rnd.nextGaussian().toFloat() * 0.8f, y)
    }

    private fun sample(rnd: Random) = WORDS[(Math.pow(rnd.nextDouble(), 2.0) * 20000).toInt()]

    private fun score(d: Decoder, aims: Boolean, sy: Float, n: Int = 1500): Int {
        val rnd = Random(99)
        var ok = 0
        repeat(n) {
            val w = sample(rnd)
            if (Dictionary.keyOf(d.decode(taps(w, aims, sy, rnd), Mode.MINI, layout, null).words.first().display) == w) ok++
        }
        return ok
    }

    /** The typist types [n] words; those read right are kept and learned from, as the keyboard does. */
    private fun train(d: Decoder, t: TouchModel, aims: Boolean, sy: Float, n: Int = 600) {
        val rnd = Random(5)
        repeat(n) {
            val w = sample(rnd)
            val tp = taps(w, aims, sy, rnd)
            if (Dictionary.keyOf(d.decode(tp, Mode.MINI, layout, null).words.first().display) == w) t.learn(layout, Mode.MINI, tp, w)
        }
    }

    @Test fun `a typist who aims at the rows is read better once their rows are learned`() {
        val d = decoder()
        val before = score(d, aims = true, sy = 0.7f)
        d.touch = TouchModel(touchFile())
        train(d, d.touch!!, aims = true, sy = 0.7f)
        val after = score(d, aims = true, sy = 0.7f)
        assertTrue("$before -> $after of 1500", after >= before + 15)
    }

    @Test fun `a typist who doesn't aim at the rows is read no worse`() {
        val d = decoder()
        val before = score(d, aims = false, sy = 1f)
        d.touch = TouchModel(touchFile())
        train(d, d.touch!!, aims = false, sy = 1f)
        val rows = d.touch!!.shape(layout, Mode.MINI)!!.rowY!!
        assertTrue(rows.joinToString(), rows.max() - rows.min() < 0.15f)
        val after = score(d, aims = false, sy = 1f)
        assertTrue("$before -> $after of 1500", after >= before - 8)
    }

    @Test fun `row heights are kept with the rest of the touch model, and need some taps first`() {
        val file = touchFile()
        val t = TouchModel(file)
        t.learn(layout, Mode.MINI, listOf(Tap(layout.miniSlot['h' - 'a'].toFloat(), 1f), Tap(layout.miniSlot['i' - 'a'].toFloat(), 0f)), "hi")
        assertNull("too few taps to say", t.shape(layout, Mode.MINI)!!.rowY)
        train(decoder(), t, aims = true, sy = 0.6f, n = 200)
        val rows = t.shape(layout, Mode.MINI)!!.rowY
        assertNotNull(rows)
        assertTrue(rows!!.joinToString(), rows[0] < rows[1] && rows[1] < rows[2])
        t.save()
        val again = TouchModel(file).shape(layout, Mode.MINI)!!
        assertArrayEquals(rows, again.rowY, 1e-5f)
        assertEquals(t.shape(layout, Mode.MINI)!!.rowSpread, again.rowSpread, 1e-5f)
        // Full mode has no rows to learn this way.
        assertNull(again.let { TouchModel(file).shape(layout, Mode.FULL) })
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        val WORDS: List<String> = File(ASSETS, Dictionary.OPEN_ASSET).readLines().filter { !it.startsWith("#") }
            .map { it.substringBefore(' ') }.filter { w -> w.length >= 2 && w.all { it in 'a'..'z' } }
        val DICT: Dictionary = run {
            val wrong = File(ASSETS, Dictionary.MISSPELLINGS_ASSET).inputStream().use(Dictionary::readMisspellings)
            File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong) }
        }
    }
}

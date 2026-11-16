package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TouchModel
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The decoder with a touch model, on the app's own word list and simulated typists: a typist whose
 * taps land off the key centres is read better once the model has learned from their kept words,
 * and one whose taps don't is read no worse.
 */
class TouchAdaptationTest {
    private val layout = KeyLayout.QWERTZ

    private fun decoder() = Decoder(DICT, UserModel(File.createTempFile("user", ".tsv").also { it.delete() }))
    private fun touch() = TouchModel(File.createTempFile("touch", ".tsv").also { it.delete() })

    private fun sample(rnd: Random) = WORDS[(Math.pow(rnd.nextDouble(), 2.0) * 20000).toInt()]

    /** [word] typed in [mode] by a typist whose taps land [bias] (across, down) off each key, with [noise]. */
    private fun taps(word: String, mode: Mode, bias: (Int) -> Pair<Float, Float>, noise: Float, rnd: Random) = word.map {
        val c = it - 'a'
        val (bx, by) = bias(c)
        if (mode == Mode.MINI) Tap(layout.miniSlot[c] + bx + rnd.nextGaussian().toFloat() * noise, 0f)
        else Tap(layout.fullX[c] + bx + rnd.nextGaussian().toFloat() * noise, layout.fullY[c] + by + rnd.nextGaussian().toFloat() * noise)
    }

    /** Words in [n] that come out right: in mini mode the best word, in full mode the letters as tapped. */
    private fun score(d: Decoder, mode: Mode, bias: (Int) -> Pair<Float, Float>, noise: Float, n: Int): Int {
        val rnd = Random(99)
        var ok = 0
        repeat(n) {
            val w = sample(rnd)
            val r = d.decode(taps(w, mode, bias, noise, rnd), mode, layout, null)
            val got = if (mode == Mode.MINI) Dictionary.keyOf(r.words.first().display) else r.literal
            if (got == w) ok++
        }
        return ok
    }

    /** The typist types [n] words; those that came out right are kept, and learned from. */
    private fun train(d: Decoder, t: TouchModel, mode: Mode, bias: (Int) -> Pair<Float, Float>, noise: Float, n: Int) {
        val rnd = Random(5)
        repeat(n) {
            val w = sample(rnd)
            val tp = taps(w, mode, bias, noise, rnd)
            val r = d.decode(tp, mode, layout, null)
            val got = if (mode == Mode.MINI) Dictionary.keyOf(r.words.first().display) else r.literal
            if (got == w) t.learn(layout, mode, tp, w)
        }
    }

    private val none: (Int) -> Pair<Float, Float> = { 0f to 0f }

    @Test fun `an empty touch model changes nothing`() {
        val d = decoder()
        val before = score(d, Mode.MINI, none, 0.8f, 400)
        d.touch = touch()
        assertEquals(before, score(d, Mode.MINI, none, 0.8f, 400))
    }

    @Test fun `a thumb that lands off the letters is read better after a few hundred words`() {
        val thumb: (Int) -> Pair<Float, Float> = { c -> val s = layout.miniSlot[c]; (if (s < 9) 0.7f else if (s < 18) 0.3f else -0.2f) to 0f }
        val d = decoder()
        val before = score(d, Mode.MINI, thumb, 0.8f, 1500)
        d.touch = touch()
        train(d, d.touch!!, Mode.MINI, thumb, 0.8f, 600)
        val after = score(d, Mode.MINI, thumb, 0.8f, 1500)
        assertTrue("mini: $before -> $after of 1500", after >= before + 12)
    }

    @Test fun `full mode taps that land low are typed right much more often`() {
        val low: (Int) -> Pair<Float, Float> = { 0.1f to 0.3f }
        val d = decoder()
        val before = score(d, Mode.FULL, low, 0.3f, 1000)
        d.touch = touch()
        train(d, d.touch!!, Mode.FULL, low, 0.3f, 800)
        val after = score(d, Mode.FULL, low, 0.3f, 1000)
        assertTrue("full, letters as tapped: $before -> $after of 1000", after >= before + 100)
    }

    @Test fun `a typist who hits the centres is read no worse`() {
        for (mode in Mode.entries) {
            val noise = if (mode == Mode.MINI) 0.8f else 0.3f
            val d = decoder()
            val before = score(d, mode, none, noise, 1500)
            d.touch = touch()
            train(d, d.touch!!, mode, none, noise, 600)
            val after = score(d, mode, none, noise, 1500)
            assertTrue("$mode: $before -> $after of 1500", after >= before - 12)
        }
    }

    @Test fun `with adapt off the decoder reads the key centres`() {
        val d = decoder()
        val t = touch()
        d.touch = t
        val shifted: (Int) -> Pair<Float, Float> = { 0.6f to 0f }
        train(d, t, Mode.MINI, shifted, 0.8f, 600)
        val rnd = Random(3)
        repeat(50) {
            val tp = taps(sample(rnd), Mode.MINI, shifted, 0.8f, rnd)
            val plain = decoder().decode(tp, Mode.MINI, layout, null)
            val off = d.decode(tp, Mode.MINI, layout, null, adapt = false)
            assertEquals(plain.words.map { it.display }, off.words.map { it.display })
        }
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

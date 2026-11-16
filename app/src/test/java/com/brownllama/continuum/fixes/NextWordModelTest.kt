package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The next-word model (assets/next_words.tsv) as the app loads it: the file is sound, it settles
 * taps that fit two words the way the word before suggests, typing everyday sentences gets better
 * with it, and without a previous word nothing changes.
 */
class NextWordModelTest {
    private val layout = KeyLayout.QWERTZ
    private fun decoder(d: Dictionary = WITH) = Decoder(d, UserModel(File.createTempFile("user", ".tsv").also { it.delete() }))

    private fun slot(c: Char) = layout.miniSlot[c - 'a'].toFloat()
    private fun between(a: Char, b: Char) = Tap((slot(a) + slot(b)) / 2, 0f)
    private fun on(c: Char) = Tap(slot(c), 0f)

    @Test fun `the model file is sound`() {
        val rows = File(ASSETS, Dictionary.NEXT_ASSET).inputStream().use(Dictionary::readNext)
        assertTrue("rows: ${rows.size}", rows.size > 1000)
        assertTrue("<s>" in rows)
        for ((prev, row) in rows) {
            assertTrue(prev, prev == "<s>" || WITH.contains(prev))
            assertTrue(prev, row.backoff <= 0f)
            for ((next, lift) in row.next) {
                assertTrue("$prev $next", WITH.contains(next))
                assertTrue("$prev $next", lift > 0f && lift < 16f)
            }
        }
        // Contractions Google keeps whole ("don't") or splits ("I 'm") are both there, and the
        // French "dont il" is not.
        assertTrue(rows.getValue("dont").next.any { it.first == "know" })
        assertTrue(rows.getValue("im").next.any { it.first == "going" })
        assertFalse(rows.getValue("dont").next.any { it.first == "il" })
    }

    @Test fun `taps between two letters are read as the word that fits the previous one`() {
        val d = decoder()
        fun best(prev: String, vararg taps: Tap) = d.decode(taps.toList(), Mode.MINI, layout, prev).words.first().display
        assertEquals("go", best("lets", between('t', 'g'), on('o')))
        assertEquals("to", best("have", between('t', 'g'), on('o')))
        assertEquals("on", best("depends", between('i', 'o'), on('n')))
        assertEquals("in", best("interested", between('i', 'o'), on('n')))
        assertEquals("be", best("to", between('b', 'h'), on('e')))
        assertEquals("he", best("and", between('b', 'h'), on('e')))
        assertEquals("if", best("what", between('i', 'o'), on('f')))
        assertEquals("of", best("one", between('i', 'o'), on('f')))
    }

    @Test fun `without a previous word the model changes nothing`() {
        val a = decoder(WITH)
        val b = decoder(WITHOUT)
        val rnd = Random(3)
        repeat(300) {
            val w = WORDS[(Math.pow(rnd.nextDouble(), 2.0) * 20000).toInt()]
            val taps = w.map { Tap(layout.miniSlot[it - 'a'] + rnd.nextGaussian().toFloat() * 0.8f, 0f) }
            assertEquals(w, b.decode(taps, Mode.MINI, layout, null).words.map { it.display }, a.decode(taps, Mode.MINI, layout, null).words.map { it.display })
        }
    }

    @Test fun `everyday sentences are typed right more often`() {
        // Mini mode gains most (one row of letters fits many words); full mode starts high.
        for ((mode, sigma, gain) in listOf(Triple(Mode.MINI, 0.8f, words / 50), Triple(Mode.FULL, 0.3f, words / 200))) {
            val without = typeSentences(decoder(WITHOUT), mode, sigma)
            val with = typeSentences(decoder(WITH), mode, sigma)
            assertTrue("$mode: $without -> $with of $words", with >= without + gain)
        }
    }

    @Test fun `next-word predictions find the next word more often`() {
        fun hits(d: Decoder): Int {
            var hit = 0
            for (s in SENTENCES) {
                var prev: String? = null
                for ((i, w) in s.withIndex()) {
                    val key = Dictionary.keyOf(w) ?: continue
                    if (d.predict(prev, sentenceStart = i == 0, limit = 3).any { Dictionary.keyOf(it) == key }) hit++
                    prev = key
                }
            }
            return hit
        }
        val without = hits(decoder(WITHOUT))
        val with = hits(decoder(WITH))
        assertTrue("$without -> $with of $words", with >= without + words / 10)
    }

    @Test fun `the model survives the binary copy the app keeps`() {
        val copy = Dictionary.fromBytes(WITH.toBytes())
        assertEquals(WITH.nextFor("lets"), copy.nextFor("lets"))
        assertEquals(WITH.startWords, copy.startWords)
        val a = WITH.context("depends", false, 1f)!!
        val b = copy.context("depends", false, 1f)!!
        for (i in 0 until WITH.size step 97) assertEquals(a.of(i), b.of(i), 0f)
        assertEquals(WITH.context(null, true, 1f)!!.backoff, copy.context(null, true, 1f)!!.backoff, 0f)
    }

    private val words = SENTENCES.sumOf { it.size }

    /** How many words of the sentences come out right, typed with [sigma] noise. */
    private fun typeSentences(d: Decoder, mode: Mode, sigma: Float): Int {
        val rnd = Random(17)
        var ok = 0
        for (s in SENTENCES) {
            var prev: String? = null
            for ((i, w) in s.withIndex()) {
                val key = Dictionary.keyOf(w) ?: continue
                val taps = key.map {
                    val c = it - 'a'
                    if (mode == Mode.MINI) Tap(layout.miniSlot[c] + rnd.nextGaussian().toFloat() * sigma, 0f)
                    else Tap(layout.fullX[c] + rnd.nextGaussian().toFloat() * sigma, layout.fullY[c] + rnd.nextGaussian().toFloat() * sigma)
                }
                val r = d.decode(taps, mode, layout, prev, start = prev == null && i == 0)
                if (Dictionary.keyOf(r.words.firstOrNull()?.display ?: r.literal) == key) ok++
                prev = key
            }
        }
        return ok
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        val SENTENCES: List<List<String>> = File("src/test/java/com/brownllama/continuum/fixes/sentences.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(' ') }
        val WORDS: List<String> = File(ASSETS, Dictionary.OPEN_ASSET).readLines().filter { !it.startsWith("#") }
            .map { it.substringBefore(' ') }.filter { w -> w.length >= 2 && w.all { it in 'a'..'z' } }
        private val wrong = File(ASSETS, Dictionary.MISSPELLINGS_ASSET).inputStream().use(Dictionary::readMisspellings)
        val WITHOUT: Dictionary = File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong) }
        val WITH: Dictionary = run {
            val next = File(ASSETS, Dictionary.NEXT_ASSET).inputStream().use(Dictionary::readNext)
            File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong, emptyMap(), next) }
        }
    }
}

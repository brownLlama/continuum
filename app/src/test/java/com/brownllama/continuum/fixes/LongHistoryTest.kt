package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.exp

/**
 * The user model after a long history, not just the first few words: what a long-time user has
 * typed must not crowd out every word they haven't (the decoder weighs their words by share, not by
 * raw count), their own words are still learned, and a word typed once still comes back.
 *
 * The histories are written as UserModel's own file: [n] words drawn from the dictionary's own
 * frequencies (a user who types the way the word list expects, so an ideal user model changes
 * nothing), optionally with personal words mixed in.
 */
class LongHistoryTest {
    private val layout = KeyLayout.QWERTZ
    private val today = 20_000

    /** Mini-mode taps around [w]'s letters, noise 0.8 slots. */
    private fun taps(w: String, rnd: Random) = w.map { Tap(layout.miniSlot[it - 'a'] + rnd.nextGaussian().toFloat() * 0.8f, 0f) }

    private fun best(d: Decoder, w: String, rnd: Random) =
        d.decode(taps(w, rnd), Mode.MINI, layout, null).words.firstOrNull()?.key

    /** A user who typed [counts] a month ago (and [recent] today), as their saved file. */
    private fun userWith(counts: Map<String, Int>, recent: Set<String> = emptySet()): UserModel {
        val f = File.createTempFile("history", ".tsv").also { it.deleteOnExit() }
        f.bufferedWriter().use { w ->
            for ((k, c) in counts) w.append("u\t$k\t$c\t${if (k in recent) today else today - 30}\n")
        }
        return UserModel(f, today = { today })
    }

    /** [n] words typed the way the dictionary expects, as counts (the 10,000 most used, as the model keeps). */
    private fun history(n: Int, extra: (Random) -> String? = { null }): Map<String, Int> {
        val rnd = Random(42)
        val counts = HashMap<String, Int>()
        repeat(n) { counts.merge(extra(rnd) ?: SAMPLER.next(rnd), 1, Int::plus) }
        return counts.entries.sortedByDescending { it.value }.take(UserModel.MAX_WORDS).associate { it.key to it.value }
    }

    /** Words right out of [n] drawn from the dictionary's frequencies (two letters or more). */
    private fun accuracy(d: Decoder, n: Int = 1500): Int {
        val rnd = Random(9)
        var ok = 0
        var done = 0
        while (done < n) {
            val w = SAMPLER.next(rnd)
            if (w.length < 2) continue
            done++
            if (best(d, w, rnd) == w) ok++
        }
        return ok
    }

    @Test fun `a long history of ordinary typing doesn't crowd out the words not typed yet`() {
        val fresh = accuracy(Decoder(DICT, userWith(emptyMap())))
        val long = accuracy(Decoder(DICT, userWith(history(20_000))))
        // With raw counts this fell by about 4 points (89% to 85%); by share it stays.
        assertTrue("no history $fresh -> 20,000 words $long of 1500", long >= fresh - 20)
    }

    @Test fun `a long-time user's own words are still learned`() {
        val rnd = Random(1)
        val personal = (0 until 60).map { WORDS[15_000 + rnd.nextInt(25_000)] }.distinct()
        val counts = history(20_000) { r -> if (r.nextDouble() < 0.15) personal[(Math.pow(r.nextDouble(), 2.5) * personal.size).toInt()] else null }
        val d = Decoder(DICT, userWith(counts))
        val t = Random(3)
        val typed = personal.filter { (counts[it] ?: 0) >= 3 }
        val ok = typed.count { w -> best(d, w, t) == w }
        assertTrue("$ok of ${typed.size}", ok >= typed.size * 95 / 100)
    }

    @Test fun `a word typed once comes back for a long-time user too, and a kept typo doesn't take over`() {
        val background = history(20_000)
        val t = Random(11)
        var back = 0
        for (w in NEW_WORDS) {
            val u = userWith(background)
            u.learn(null, w)
            if (best(Decoder(DICT, u), w, t) == w) back++
        }
        // With raw counts this fell with history (77% with none, 64% after 20,000 words); by share
        // and with a recent word counted as at least a common one, about 70%.
        assertTrue("$back of ${NEW_WORDS.size}", back >= NEW_WORDS.size * 65 / 100)
        for ((word, typo) in listOf("coat" to "caot", "brains" to "bdains", "hello" to "hwllo", "about" to "abuot")) {
            val u = userWith(background)
            u.learn(null, typo)
            val exact = word.map { Tap(layout.miniSlot[it - 'a'].toFloat(), 0f) }
            assertEquals(typo, word, Decoder(DICT, u).decode(exact, Mode.MINI, layout, null).words.first().key)
        }
    }

    @Test fun `the totals follow learning, taking back and forgetting`() {
        val u = UserModel(File.createTempFile("user", ".tsv").also { it.delete() })
        u.learn(null, "hello"); u.learn("hello", "there"); u.learn("hello", "you")
        assertEquals(3L, u.total)
        assertEquals(2, u.followers("hello"))
        u.unlearn("hello", "you")
        assertEquals(2L, u.total)
        assertEquals(1, u.followers("hello"))
        u.forget("there", "there")
        assertEquals(1L, u.total)
        assertEquals(0, u.followers("hello"))
        assertTrue(u.isRecent("hello"))
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        val WORDS: List<String> = File(ASSETS, Dictionary.OPEN_ASSET).readLines().filter { !it.startsWith("#") }
            .map { it.substringBefore(' ') }.filter { w -> w.length >= 2 && w.all { it in 'a'..'z' } }
        val DICT: Dictionary = run {
            val wrong = File(ASSETS, Dictionary.MISSPELLINGS_ASSET).inputStream().use(Dictionary::readMisspellings)
            File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong) }
        }
        /** Words the English list lacks (German ones, standing in for names and slang). */
        val NEW_WORDS: List<String> = File(ASSETS, "words_de.txt").readLines().filter { !it.startsWith("#") }
            .map { it.substringBefore(' ') }.mapNotNull { Dictionary.keyOf(it) }
            .filter { it.length in 4..9 && !DICT.contains(it) }.drop(500).take(100)

        val SAMPLER = Sampler(DICT)
    }
}

/** Draws [dict]'s keys in proportion to their corpus frequency. */
private class Sampler(private val dict: Dictionary) {
    private val cum = DoubleArray(dict.size).also { c ->
        var s = 0.0
        for (i in 0 until dict.size) { s += exp(dict.logFreq[i].toDouble()); c[i] = s }
    }

    fun next(r: Random): String {
        val u = r.nextDouble() * cum.last()
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < u) lo = m + 1 else hi = m }
        return dict.key(lo)
    }
}

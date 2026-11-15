package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.DecodeResult
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

/**
 * Two words typed without the space between them, on the app's own word list: the pairs that
 * should split do, and single words stay exactly as they were.
 */
class TwoWordsTest {
    private val user = UserModel(File.createTempFile("user", ".tsv").also { it.delete() })
    private val decoder = Decoder(DICT, user)
    private val layout = KeyLayout.named("qwerty")

    /** [text]'s letters, each tapped in the middle of its key. */
    private fun taps(text: String, mode: Mode) = text.filter { it in 'a'..'z' }.map {
        val c = it - 'a'
        if (mode == Mode.MINI) Tap(layout.miniSlot[c].toFloat(), 0f) else Tap(layout.fullX[c], layout.fullY[c])
    }

    private fun decode(text: String, mode: Mode, splits: Boolean = true, d: Decoder = decoder): DecodeResult =
        d.decode(taps(text, mode), mode, layout, null, splits = splits)

    /** The word space would type in mini mode: the decoder's best. */
    private fun best(text: String, mode: Mode = Mode.MINI, d: Decoder = decoder) = decode(text, mode, d = d).words.first().display

    private fun offered(r: DecodeResult) = (r.words + r.corrections + r.completions).map { it.display }

    @Test fun `common pairs typed as one word become two`() {
        for (mode in Mode.entries) for (pair in listOf("thank you", "of the", "in the", "of course", "at least", "right now", "no problem")) {
            assertEquals("$pair in $mode", pair, best(pair, mode))
        }
    }

    @Test fun `a pair is one candidate, keyed by its letters and marked as a correction`() {
        val c = decode("thankyou", Mode.FULL).words.first()
        assertEquals("thank you", c.display)
        assertEquals("thankyou", c.key)
        assertTrue(c.edited)
    }

    @Test fun `words that happen to be two words in a row stay one word`() {
        val words = listOf(
            "today", "into", "maybe", "income", "theme", "behave", "tome", "meme", "onto", "become", "without", "nobody",
            "goodnight", "myself", "another", "forget", "youths", "vogue", "inane", "outcome", "upon", "cannot", "anyone",
        )
        for (mode in Mode.entries) for (w in words) {
            val with = decode(w, mode)
            val without = decode(w, mode, splits = false)
            assertEquals("$w in $mode", without.words.first().display, with.words.first().display)
            assertFalse("$w in $mode", ' ' in with.words.first().display)
        }
    }

    @Test fun `no single word of the list changes its default, bar the joined forms`() {
        val joined = setOf(
            "ofthe", "ifyou", "forthe", "foryou", "andi", "thankyou", "areyou", "atleast", "everytime", "doyou", "ofyou",
            "ofcourse", "itwas", "infront", "inthe", "eachother", "highschool", "onthe", "toyou", "lfyou", "orthe", "oryou",
        )
        val changed = ArrayList<String>()
        for (mode in Mode.entries) WORDS.forEachIndexed { i, w ->
            if (i % 9 != 0 || w in joined) return@forEachIndexed
            val with = decode(w, mode).words.first().display
            val without = decode(w, mode, splits = false).words.first().display
            if (with != without) changed += "$w: $without > $with ($mode)"
        }
        assertEquals(emptyList<String>(), changed)
    }

    @Test fun `with splits off there are no two-word readings`() {
        for (mode in Mode.entries) for (text in listOf("thankyou", "ofthe", "rightnow")) {
            assertTrue(offered(decode(text, mode, splits = false)).none { ' ' in it })
        }
    }

    @Test fun `halves of contractions are never one of the two words`() {
        val halves = setOf("ll", "re", "ve", "don", "didn", "doesn", "isn", "wasn", "aren", "couldn", "wouldn", "shouldn", "hasn", "haven", "hadn", "weren", "ain")
        for (mode in Mode.entries) for (text in listOf("dont", "youre", "theyll", "ididnt", "wedont", "isnt", "youve", "idont", "shedidnt")) {
            for (word in offered(decode(text, mode))) assertTrue("$text gave '$word'", word.split(' ').none { it in halves })
        }
        assertEquals("don't", best("dont", Mode.FULL))
        // A contraction is a fine second word, though.
        assertTrue("I didn't" in offered(decode("ididnt", Mode.FULL)))
    }

    @Test fun `a pair the user types often becomes the default`() {
        assertFalse("do you" == best("doyou"))
        assertTrue("do you" in offered(decode("doyou", Mode.MINI)))
        val typed = UserModel(File.createTempFile("user", ".tsv").also { it.delete() })
        repeat(3) {
            typed.learn(null, "do", "do")
            typed.learn("do", "you", "you")
        }
        assertEquals("do you", best("doyou", d = Decoder(DICT, typed)))
    }

    @Test fun `a pair removed from the suggestions is not offered again`() {
        val u = UserModel(File.createTempFile("user", ".tsv").also { it.delete() })
        val d = Decoder(DICT, u)
        assertEquals("thank you", best("thankyou", d = d))
        u.forget(Dictionary.keyOf("thank you"), "thank you")
        assertFalse("thank you" in offered(decode("thankyou", Mode.MINI, d = d)))
    }

    @Test fun `too few taps for two words give none`() {
        for (mode in Mode.entries) for (text in listOf("a", "i")) {
            assertTrue(offered(decode(text, mode)).none { ' ' in it })
        }
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        val WORDS: List<String> = File(ASSETS, Dictionary.OPEN_ASSET).readLines().filter { !it.startsWith("#") }
            .map { it.substringBefore(' ') }.filter { w -> w.isNotEmpty() && w.all { it in 'a'..'z' } }
        val DICT: Dictionary = run {
            val wrong = File(ASSETS, Dictionary.MISSPELLINGS_ASSET).inputStream().use(Dictionary::readMisspellings)
            File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong) }
        }
    }
}

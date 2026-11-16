package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A new word (one the word list lacks) typed once comes back as the default when the taps fit it
 * far better than any real word, in either mode, while a typo kept once doesn't take over the
 * word it was a typo of.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewWordTest {
    private val layout = KeyLayout.QWERTZ

    private fun user(vararg once: String) = UserModel(File.createTempFile("user", ".tsv").also { it.delete() }).apply {
        for (w in once) learn(null, w, w)
    }

    private fun mini(word: String) = word.map { Tap(layout.miniSlot[it - 'a'].toFloat(), 0f) }
    private fun full(word: String) = word.map { Tap(layout.fullX[it - 'a'], layout.fullY[it - 'a']) }

    @Test fun `a new word typed once is the default when the taps spell it`() {
        for (w in listOf("nepali", "pokhara", "dhaka", "timro", "hajur", "garnu", "sathi", "zorbix")) {
            assertFalse(w, DICT.contains(w))
            val u = user(w)
            assertFalse(u.isConfirmed(w))
            val d = Decoder(DICT, u)
            assertEquals(w, d.decode(mini(w), Mode.MINI, layout, null).words.first().key)
            assertEquals(w, d.decode(full(w), Mode.FULL, layout, null).words.first().key)
        }
    }

    @Test fun `a typo kept once doesn't replace the word it was a typo of`() {
        for ((word, typo) in listOf("coat" to "caot", "hold" to "ohld", "brains" to "bdains", "hello" to "hwllo", "the" to "teh", "about" to "abuot")) {
            assertFalse(typo, DICT.contains(typo))
            val d = Decoder(DICT, user(typo))
            val r = d.decode(mini(word), Mode.MINI, layout, null)
            assertEquals(typo, word, r.words.first().key)
            assertEquals(typo, word, d.decode(full(word), Mode.FULL, layout, null).words.first().key)
        }
    }

    @Test fun `a word typed once in full mode is typed by space in mini mode`() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        k.focus(); k.type("nepali"); k.space()
        assertEquals("nepali ", k.text)
        k.ime.onModeToggle(); k.idle()
        k.focus(); k.type("nepali")
        assertTrue(k.bar.toString(), "nepali" in k.bar)
        k.space()
        assertEquals("nepali ", k.text)
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        val DICT: Dictionary = run {
            val wrong = File(ASSETS, Dictionary.MISSPELLINGS_ASSET).inputStream().use(Dictionary::readMisspellings)
            val next = File(ASSETS, Dictionary.NEXT_ASSET).inputStream().use(Dictionary::readNext)
            File(ASSETS, Dictionary.OPEN_ASSET).inputStream().use { Dictionary.loadOpen(it, wrong, emptyMap(), next) }
        }
    }
}

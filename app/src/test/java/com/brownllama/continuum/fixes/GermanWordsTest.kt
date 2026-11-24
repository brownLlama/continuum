package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import com.brownllama.continuum.prediction.Dictionary
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
 * A word with German letters ("münchen", typed with the held-key ü) is learned under its base
 * letters, so mini mode, which has no ü, types it from the taps of "munchen", spelled as typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GermanWordsTest {
    private fun keyboard(mode: String, autocorrect: Boolean = false) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to autocorrect)).also { it.waitReady() }

    /** Types [word] in full mode, its German letters as the held keys give them. */
    private fun Keyboard.typeGerman(word: String, shift: Boolean = false) {
        if (shift) ime.onShift()
        for (c in word) if (c in 'a'..'z') type(c.toString(), full = true) else ime.onText(c.toString())
    }

    private fun Keyboard.typed(before: String, word: String): String {
        focus(text = before); type(word); space()
        return text.removePrefix(before)
    }

    @Test fun `German letters count as the letters they are typed with`() {
        assertEquals("munchen", Dictionary.keyOf("München"))
        assertEquals("strasse", Dictionary.keyOf("Straße"))
        assertEquals("fur", Dictionary.keyOf("für"))
        assertTrue(Dictionary.sameWord("Für", "fur"))
        assertTrue(Dictionary.sameWord("straße", "STRASSE"))
        assertFalse(Dictionary.sameWord("I'll", "ill"))
    }

    @Test fun `a German word typed in full mode is typed in mini mode`() {
        val k = keyboard("full")
        k.focus(text = "in ")
        k.typeGerman("münchen"); k.space()
        assertEquals("in münchen ", k.text)
        k.focus(text = "die ")
        k.typeGerman("straße"); k.space()
        assertEquals("die straße ", k.text)
        k.ime.onModeToggle(); k.idle()
        assertEquals("münchen ", k.typed("in ", "munchen"))
        assertEquals("straße ", k.typed("die ", "strasse"))
        // With shift, a capital as always.
        k.focus(text = "in "); k.ime.onShift(); k.type("munchen"); k.space()
        assertEquals("in München ", k.text)
    }

    @Test fun `full mode corrects to the German spelling with autocorrect, and offers it without`() {
        val k = keyboard("full", autocorrect = true)
        k.focus(text = "in "); k.typeGerman("münchen"); k.space()
        assertEquals("münchen ", k.typed("in ", "munchen"))
        k.blur() // the keyboard closing is what writes what it learned
        val exact = keyboard("full")
        exact.focus(text = "in "); exact.type("munchen")
        assertTrue(exact.bar.toString(), "münchen" in exact.bar)
        exact.space()
        assertEquals("in munchen ", exact.text)
    }

    @Test fun `the letters before a German letter are not learned as a word of their own`() {
        val k = keyboard("full")
        repeat(2) { k.focus(text = "in "); k.typeGerman("münchen"); k.space() }
        // A German letter starting a word takes back nothing ("u" is not unlearned).
        k.focus(text = "ok "); k.type("u"); k.space()
        k.focus(text = "ok "); k.typeGerman("über"); k.space()
        assertEquals("ok über ", k.text)
        k.blur()
        k.ime.onDestroy()
        val user = UserModel(File(k.context.filesDir, "user_model.tsv"))
        assertEquals(2, user.count("munchen"))
        assertEquals("münchen", user.formOf("munchen"))
        assertEquals(0, user.count("m"))
        assertEquals(1, user.count("u"))
        assertEquals(1, user.count("uber"))
    }

    @Test fun `an English word stays English unless the user wrote it with German letters`() {
        val k = keyboard("full", autocorrect = true)
        assertEquals("fur ", k.typed("the ", "fur"))
        k.focus(text = "danke "); k.typeGerman("für"); k.space()
        assertEquals("für ", k.typed("danke ", "fur"))
    }
}

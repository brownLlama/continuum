package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Capitals come only from shift: a word typed without it is lowercase, wherever it is and however
 * it was spelled before, except "I" and its contractions. Either way it is one word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CapitalsTest {
    private fun keyboard(mode: String, autocorrect: Boolean = false) =
        Keyboard(mapOf(Prefs.MODE to mode, Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to autocorrect)).also { it.waitReady() }

    private fun Keyboard.typed(before: String, word: String, shift: Boolean = false): String {
        focus(text = before)
        if (shift) ime.onShift()
        type(word)
        space()
        return text.removePrefix(before)
    }

    @Test fun `a capital typed with shift doesn't make the word capital later`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            assertEquals(mode, "hey ", k.typed("", "hey"))
            for (before in listOf("😊 ", "lol, ", "ok ", "")) {
                assertEquals("$mode after '$before'", "Hey ", k.typed(before, "hey", shift = true))
                assertEquals("$mode after '$before'", "hey ", k.typed("ok so ", "hey"))
                assertEquals("$mode after '$before'", "hey ", k.typed("", "hey"))
            }
            // Typed with shift again and again: still lowercase without it.
            repeat(3) { k.typed("ok ", "london", shift = true) }
            assertEquals(mode, "london ", k.typed("i love ", "london"))
            assertEquals(mode, "London ", k.typed("i love ", "london", shift = true))
        }
    }

    @Test fun `I and its contractions always have a capital I`() {
        val mini = keyboard("mini")
        assertEquals("I ", mini.typed("so ", "i"))
        assertEquals("I'm ", mini.typed("so ", "im"))
        // Full mode with autocorrect adds the apostrophe; its taps can't be read as other words.
        val full = keyboard("full", autocorrect = true)
        for ((typed, word) in listOf("i" to "I", "im" to "I'm", "ive" to "I've")) {
            assertEquals(typed, "$word ", full.typed("so ", typed))
        }
        // Without autocorrect full mode types what is tapped, apostrophe included, with a capital I.
        val exact = keyboard("full")
        assertEquals("I ", exact.typed("so ", "i"))
        exact.focus(text = "so ")
        exact.type("i"); exact.ime.onText("'"); exact.type("m"); exact.space()
        assertEquals("so I'm ", exact.text)
    }

    @Test fun `words with a capital inside keep it without shift`() {
        for (mode in listOf("mini", "full")) {
            val k = keyboard(mode)
            assertEquals(mode, "iPhone ", k.typed("my ", "iphone"))
            assertEquals(mode, "YouTube ", k.typed("on ", "youtube"))
        }
        assertEquals("eBay ", keyboard("full").typed("on ", "ebay"))
        // A new word the user typed that way (shift on its inner capital) is spelled so from then
        // on, in both modes.
        val k = keyboard("full")
        k.focus(text = "ok ")
        k.type("brown"); k.ime.onShift(); k.type("llama"); k.space()
        assertEquals("ok brownLlama ", k.text)
        assertEquals("brownLlama ", k.typed("by ", "brownllama"))
        k.ime.onModeToggle(); k.idle()
        assertEquals("brownLlama ", k.typed("by ", "brownllama"))
        // Shift on its first letter still makes that a capital too.
        assertEquals("BrownLlama ", k.typed("by ", "brownllama", shift = true))
    }

    @Test fun `a word learned in lowercase first shows only the spelling typed with a capital`() {
        for (mode in listOf("full", "mini")) {
            val k = keyboard("full")
            // Typed in lowercase first (as it may be from an older version), then with the capital.
            repeat(2) { k.typed("by ", "brownllama") }
            k.focus(text = "by ")
            k.type("brown"); k.ime.onShift(); k.type("llama"); k.space()
            if (mode == "mini") { k.ime.onModeToggle(); k.idle() }
            k.focus(text = "by ")
            k.type("brownllama")
            val versions = k.bar.filter { it.equals("brownllama", ignoreCase = true) }
            assertEquals("$mode bar ${k.bar}", listOf("brownLlama"), versions)
            k.space()
            assertEquals(mode, "by brownLlama ", k.text)
        }
    }

    @Test fun `predictions are lowercase unless shift is on, but I stays capital`() {
        val k = keyboard("mini")
        k.focus(text = "")
        k.type("hey"); k.space()
        val bar = k.bar
        assertTrue(bar.toString(), bar.isNotEmpty())
        for (w in bar) assertEquals(bar.toString(), w.lowercase().replace(Regex("(?<!\\p{L})i(?!\\p{L})"), "I"), w)
    }

    @Test fun `a word counts as one however it is capitalised`() {
        val k = keyboard("full")
        k.typed("ok ", "zorbix", shift = true)
        k.typed("ok ", "zorbix")
        k.blur()
        k.ime.onDestroy()
        assertEquals(2, UserModel(File(k.context.filesDir, "user_model.tsv")).count("zorbix"))
    }
}

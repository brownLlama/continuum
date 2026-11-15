package com.brownllama.continuum.fixes

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.brownllama.continuum.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard service going by the text before the cursor, and typing two words for taps that
 * missed the space between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TypingContextTest {
    private val full = mapOf(Prefs.MODE to "full", Prefs.AUTOCORRECT to true, Prefs.LAYOUT to "qwerty")

    /**
     * Types "purple giraffe" twice, so "giraffe" is what the user types after "purple". (Real words:
     * full mode types them as tapped. A used word may show further along the bar anywhere, so the
     * tests look at the bar's first word.)
     */
    private fun teachPair(k: Keyboard) {
        k.focus()
        repeat(2) {
            k.type("purple"); k.space()
            k.type("giraffe"); k.space()
            k.ime.onEnter()
        }
        assertEquals("purple giraffe purple giraffe ", k.text.replace("\n", ""))
        k.blur()
    }

    @Test fun `predictions go by the text already in the field`() {
        val k = Keyboard(full)
        k.waitReady()
        teachPair(k)
        // A field that already holds text the keyboard didn't type this time.
        k.focus(text = "purple ")
        assertEquals("giraffe", k.bar.first())
        k.blur()
        k.focus(text = "something else ")
        assertFalse("giraffe" == k.bar.first())
    }

    @Test fun `predictions follow the cursor when it is moved`() {
        val k = Keyboard(full)
        k.waitReady()
        teachPair(k)
        k.focus(text = "purple and more")
        assertFalse("giraffe" == k.bar.first())
        k.moveCursor("purple ".length)
        assertEquals("giraffe", k.bar.first())
        k.moveCursor("purple and more".length)
        assertFalse("giraffe" == k.bar.first())
    }

    @Test fun `a word typed after moving the cursor is learned after the word it now follows`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus(text = "purple  and window")
        // Put the cursor after "purple " and type there, twice over.
        repeat(2) {
            k.moveCursor("purple ".length)
            k.type("giraffe")
            k.ime.onEnter() // commits the word without a space of its own
            assertTrue(k.text.startsWith("purple giraffe"))
            k.edit.setText("purple  and window")
        }
        k.blur()
        k.focus(text = "purple ")
        assertEquals("giraffe", k.bar.first())
        k.blur()
        // It was not learned as following "window", the last word of the text.
        k.focus(text = "window ")
        assertFalse("giraffe" == k.bar.first())
    }

    @Test fun `the punctuation bar and a word's alternatives are not replaced when the cursor update arrives`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        k.type("hello"); k.space(); k.space() // double space: ". " and the punctuation bar
        assertEquals("hello. ", k.text)
        val punctuation = k.bar
        assertTrue("?" in punctuation)
        k.moveCursor(k.text.length) // the app reporting the edit, late
        assertEquals(punctuation, k.bar)
        k.type("wor")
        val alternatives = k.bar
        k.ime.onUpdateSelection(0, 0, k.text.length, k.text.length, k.text.length - 3, k.text.length)
        k.idle()
        assertEquals(alternatives, k.bar)
    }

    @Test fun `a field that doesn't show its text keeps the word typed last as the context`() {
        val k = Keyboard(full)
        k.waitReady()
        teachPair(k)
        k.focus()
        // Like some terminals and games: asked for the text before the cursor, it answers with nothing.
        k.ime.ic = object : InputConnectionWrapper(k.ime.ic, true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        }
        k.type("purple"); k.space()
        assertEquals("giraffe", k.bar.first())
    }

    @Test fun `full mode types two words for taps that missed the space, and learns both`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        k.type("thankyou")
        assertEquals("thank you", k.ime.suggestionSlots[1])
        k.space()
        assertEquals("thank you ", k.text)
        assertEquals(1, k.ime.learnedCount("thank"))
        assertEquals(1, k.ime.learnedCount("you"))
        assertEquals(0, k.ime.learnedCount("thankyou"))
        // "you" now follows "thank" in the user's own history.
        k.ime.onEnter()
        k.type("thank"); k.space()
        assertEquals("you", k.bar.first())
    }

    @Test fun `backspace reopens the two words, takes back what they taught and offers the letters as typed`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        k.type("thankyou"); k.space()
        k.ime.onBackspace()
        assertEquals("thank you", k.text)
        assertEquals(0, k.ime.learnedCount("thank"))
        assertEquals(0, k.ime.learnedCount("you"))
        assertTrue("thankyou" in k.bar)
        // Pick what was typed: one word, learned as such.
        k.ime.onSuggestion(k.ime.suggestionSlots.indexOf("thankyou"))
        assertEquals("thankyou ", k.text)
        assertTrue(k.ime.learnedCount("thankyou") > 0)
        assertEquals(0, k.ime.learnedCount("thank"))
        // Picked on purpose once, it is a word of the user's now and no longer split.
        k.type("thankyou"); k.space()
        assertEquals("thankyou thankyou ", k.text)
    }

    @Test fun `mini mode types two words too`() {
        val k = Keyboard(mapOf(Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        k.type("thankyou"); k.space()
        assertEquals("thank you ", k.text)
        k.type("today"); k.space()
        assertEquals("thank you today ", k.text)
    }

    @Test fun `with autocorrect off, full mode types the letters and offers the two words`() {
        val k = Keyboard(full + (Prefs.AUTOCORRECT to false))
        k.waitReady()
        k.focus()
        k.type("thankyou")
        assertTrue("thank you" in k.bar)
        k.space()
        assertEquals("thankyou ", k.text)
    }

    @Test fun `an apostrophe after two words joins only the last of them`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        k.type("thankyou")
        k.ime.onText("'") // commits "thank you" and starts a joined word
        k.type("s"); k.space()
        assertEquals("thank you's ", k.text)
        assertEquals(1, k.ime.learnedCount("thank"))
        assertEquals(0, k.ime.learnedCount("you"))
        assertEquals(1, k.ime.learnedCount("you's"))
    }

    @Test fun `a number's ending is not read as two words`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus(text = "the 16")
        k.type("th"); k.space()
        assertEquals("the 16th ", k.text)
    }
}

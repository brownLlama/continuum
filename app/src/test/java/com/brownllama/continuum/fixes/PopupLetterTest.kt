package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import com.brownllama.continuum.prediction.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A letter picked in the mini zoom ("the popup") in the middle of a word leaves the word as it is shown, and a word
 * joined by a letter or sign typed that way is one word to the swipe that deletes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopupLetterTest {
    private fun keyboard() = Keyboard(mapOf(Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false)).also { it.waitReady(); it.focus() }

    private fun Keyboard.pick(letter: Char) = ime.onLetterTap(Tap(0f, 0f, exact = letter - 'a', picked = true))

    @Test fun `a letter picked from the zoom after a word keeps the word as it is shown and adds the letter`() {
        val k = keyboard()
        k.type("zazzy")
        val shown = k.text
        k.pick('s')
        assertEquals("the word did not change into another one", shown + "s", k.text)
        k.space()
        assertEquals("$shown" + "s ", k.text)
    }

    @Test fun `a letter picked in the middle of a word does not change what was typed before it`() {
        val k = keyboard()
        k.type("zorp")
        val shown = k.text
        k.pick('q')
        k.type("ish")
        assertTrue("the first part is as it was shown: ${k.text}", k.text.startsWith(shown + "q"))
    }

    @Test fun `with a letter picked first there is nothing before it to keep, and it works as ever`() {
        val k = keyboard()
        k.pick('s')
        k.type("un")
        assertTrue(k.text.startsWith("s"))
    }

    @Test fun `a word joined by a popup letter is one word to the swipe that deletes it`() {
        // While it is still being typed...
        val k = keyboard()
        k.type("m")
        k.ime.onText("ü")
        k.type("nchen")
        k.ime.onDeleteWord()
        assertEquals("", k.text)
        // ...and once it is finished.
        val k2 = keyboard()
        k2.type("m")
        k2.ime.onText("ü")
        k2.type("nchen")
        k2.space()
        k2.ime.onDeleteWord()
        assertEquals("", k2.text)
    }

    @Test fun `the same goes for an apostrophe in the middle of a word`() {
        val k = keyboard()
        k.type("zazzy")
        k.ime.onText("'")
        k.type("s")
        k.space()
        k.ime.onDeleteWord()
        assertEquals("one swipe took it all: '${k.text}'", "", k.text)
    }

    @Test fun `only that word goes, not the one before it`() {
        val k = keyboard()
        k.type("hello")
        k.space()
        k.type("m")
        k.ime.onText("ü")
        k.type("nchen")
        k.ime.onDeleteWord()
        assertEquals("hello ", k.text)
    }
}

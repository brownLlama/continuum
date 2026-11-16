package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words the user starts messages with are learned there ("ok", which books write "okay", so
 * the next-word model reads those taps as "I'm"): once fixed a couple of times it is typed, and
 * offered as a prediction in an empty field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartWordsTest {
    @Test fun `a word the user starts messages with is learned there`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        // Typed at the start of a message and, when it came out wrong, fixed from the bar.
        repeat(2) {
            k.focus()
            k.type("ok"); k.space()
            if (k.text != "ok ") {
                k.ime.onBackspace()
                val i = k.ime.suggestionSlots.indexOf("ok")
                assertTrue(k.bar.toString(), i >= 0)
                k.ime.onSuggestion(i)
            }
            assertEquals("ok ", k.text)
        }
        k.focus()
        assertTrue("predictions in an empty field: ${k.bar}", "ok" in k.bar)
        k.type("ok"); k.space()
        assertEquals("ok ", k.text)
        // Still read as "I'm" after a word, where "ok" wasn't learned.
        k.focus(text = "so ")
        k.type("im"); k.space()
        assertEquals("so I'm ", k.text)
    }

    @Test fun `taking a word back at a message start unlearns it there`() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        repeat(3) {
            k.focus()
            k.type("zorbix"); k.space()
            k.ime.onBackspace() // reopened: taken back
            k.ime.onBackspace(); k.ime.onBackspace(); k.ime.onBackspace(); k.ime.onBackspace(); k.ime.onBackspace(); k.ime.onBackspace()
        }
        k.focus()
        assertTrue("predictions in an empty field: ${k.bar}", "zorbix" !in k.bar)
    }
}

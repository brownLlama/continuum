package com.brownllama.continuum.fixes

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.stats.TypingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard counts each typed word once, as what became of it, and nothing in private fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsCountingTest {
    private val mini = mapOf(Prefs.LAYOUT to "qwerty")
    private val full = mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwerty", Prefs.AUTOCORRECT to true)

    private fun counts(k: Keyboard, mode: Mode = Mode.MINI) = TypingStats.get(k.context).counts(null).getValue(mode)
    private fun summary(c: TypingStats.Counts) = listOf(c.kept, c.picked, c.fixed)

    private fun ready(prefs: Map<String, Any>): Keyboard = Keyboard(prefs).also { it.waitReady(); it.focus() }

    @Test fun `words kept as typed count once they're followed by another`() {
        val k = ready(mini)
        k.type("hello"); k.space()
        assertEquals("not yet: it may still be taken back", 0L, counts(k).words)
        k.type("world"); k.space()
        assertEquals(listOf(1L, 0L, 0L), summary(counts(k)))
        k.blur()
        assertEquals(listOf(2L, 0L, 0L), summary(counts(k)))
    }

    @Test fun `a word picked from the bar`() {
        val k = ready(mini)
        k.type("hello")
        val other = k.ime.suggestionSlots.indexOfFirst { it.isNotEmpty() && it != k.ime.suggestionSlots[1] }
        k.ime.onSuggestion(other)
        k.blur()
        assertEquals(listOf(0L, 1L, 0L), summary(counts(k)))
    }

    @Test fun `a word taken back and picked again counts once, as fixed`() {
        val k = ready(mini)
        k.type("hello"); k.space()
        k.ime.onBackspace() // reopens it
        val other = k.ime.suggestionSlots.indexOfFirst { it.isNotEmpty() }
        k.ime.onSuggestion(other)
        k.blur()
        assertEquals(listOf(0L, 0L, 1L), summary(counts(k)))
    }

    @Test fun `deleting letters while typing makes the word a fix`() {
        val k = ready(full)
        k.type("hellp")
        k.ime.onBackspace()
        k.type("o"); k.space()
        k.blur()
        assertEquals(listOf(0L, 0L, 1L), summary(counts(k, Mode.FULL)))
    }

    @Test fun `a word deleted whole and typed again is one fix`() {
        val k = ready(full)
        k.type("hrllo")
        repeat(5) { k.ime.onBackspace() }
        k.type("hello"); k.space()
        k.blur()
        assertEquals(listOf(0L, 0L, 1L), summary(counts(k, Mode.FULL)))
    }

    @Test fun `putting back one of a deleted word's alternatives is a fix`() {
        val k = ready(mini)
        k.type("hello"); k.space()
        k.ime.onDeleteWord()
        val alt = k.ime.suggestionSlots.indexOfFirst { it.isNotEmpty() }
        k.ime.onSuggestion(alt)
        k.blur()
        assertEquals(listOf(0L, 0L, 1L), summary(counts(k)))
        assertEquals(0L, counts(k).predictions)
    }

    @Test fun `a next-word prediction picked from the empty bar is counted as such`() {
        val k = ready(mini)
        k.type("hello"); k.space()
        k.ime.onSuggestion(k.ime.suggestionSlots.indexOfFirst { it.isNotEmpty() })
        k.blur()
        assertEquals(1L, counts(k).predictions)
        assertEquals(1L, counts(k).words)
    }

    @Test fun `two words for a missed space, and undoing them`() {
        val k = ready(full)
        k.type("thankyou"); k.space()
        k.type("so"); k.space()
        assertEquals(1L, counts(k, Mode.FULL).splits)
        k.ime.onEnter()
        k.type("thankyou"); k.space()
        k.ime.onBackspace() // takes "thank you" back
        assertEquals(1L, counts(k, Mode.FULL).splitsUndone)
    }

    @Test fun `private fields are not counted`() {
        val k = Keyboard(full)
        k.waitReady()
        for (info in listOf(
            k.info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            k.info(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )) {
            k.focus(info)
            k.type("hello", full = true); k.space()
            k.type("world", full = true); k.space()
            k.blur()
        }
        assertEquals(0L, counts(k, Mode.FULL).words)
        assertEquals(0L, counts(k, Mode.FULL).predictions)
    }

    @Test fun `counts survive a restart of the keyboard`() {
        val k = ready(mini)
        repeat(3) { k.type("hello"); k.space() }
        k.blur()
        k.ime.onDestroy()
        Thread.sleep(300)
        assertTrue(TypingStats.get(k.context).counts(null).getValue(Mode.MINI).words >= 3)
    }
}

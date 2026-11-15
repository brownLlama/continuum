package com.brownllama.continuum.fixes

import com.brownllama.continuum.prediction.TextContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The previous word, as read from the text before the cursor. */
class TextContextTest {
    private fun prev(before: String) = TextContext.previousWord(before)

    @Test fun `the word before the cursor, as its key`() {
        assertEquals("hello", prev("hello "))
        assertEquals("hello", prev("Hello "))
        assertEquals("world", prev("hello world "))
        assertEquals("world", prev("hello world"))
        assertEquals("dont", prev("I don't "))
        assertEquals("selfaware", prev("so self-aware "))
        assertEquals("im", prev("I’m "))
    }

    @Test fun `nothing at the start of a text, a line or a sentence`() {
        assertNull(prev(""))
        assertNull(prev("   "))
        assertNull(prev("Done. "))
        assertNull(prev("Really? "))
        assertNull(prev("Wow! "))
        assertNull(prev("first line\n"))
        assertNull(prev("first line\n  "))
        assertNull(prev("wait… "))
    }

    @Test fun `commas, brackets, quotes and dashes between words are looked past`() {
        assertEquals("hello", prev("hello, "))
        assertEquals("hello", prev("hello; "))
        assertEquals("hello", prev("hello: \""))
        assertEquals("hello", prev("hello ("))
        assertEquals("hello", prev("hello - "))
        assertEquals("hello", prev("\"hello\" "))
        assertEquals("said", prev("she said, “"))
    }

    @Test fun `what isn't a plain word gives nothing rather than a wrong key`() {
        assertNull(prev("at 16 "))
        assertNull(prev("see some1 "))
        assertNull(prev("für "))
        assertNull(prev("café "))
        assertNull(prev("hello 😀 "))
        assertNull(prev("mail me@example.com/"))
    }

    @Test fun `only the end of a long text matters`() {
        assertEquals("end", prev("x".repeat(500) + " the end "))
        // A word longer than the window still gives a key, just of its end.
        assertEquals("a".repeat(TextContext.WINDOW), prev("a".repeat(TextContext.WINDOW)))
    }
}

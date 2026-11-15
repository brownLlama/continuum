package com.brownllama.continuum.prediction

/**
 * Works out the previous word from the text before the cursor, so predictions and the decoder go
 * by what is actually there (after the cursor was moved, a word deleted, or in a field that
 * already had text) rather than by what was typed last.
 */
object TextContext {
    /** How many characters before the cursor are looked at: room for a long word and what follows it. */
    const val WINDOW = 48

    /**
     * The key of the word the next word follows, or null if it starts afresh: at the start of the
     * text or a line, after a sentence end, or after something that isn't a plain word (a number,
     * an emoji, a word with letters outside a-z, which has no usable key).
     *
     * Spaces, commas, brackets, quotes and dashes between the two words are looked past:
     * in "hello, (wor" the word before "wor" is still "hello".
     */
    fun previousWord(before: CharSequence): String? {
        var end = before.length
        while (true) {
            while (end > 0 && isBetweenWords(before[end - 1])) end--
            var start = end
            while (start > 0 && isWordChar(before[start - 1])) start--
            // Nothing word-like here: the start of the text, ".", "!", "?", a line break, an emoji…
            if (start == end) return null
            val token = before.subSequence(start, end)
            if (token.any { it.isLetterOrDigit() }) {
                val plain = token.all { it in 'a'..'z' || it in 'A'..'Z' || it in JOINERS }
                return if (plain) Dictionary.keyOf(token.toString()) else null
            }
            // A dash or quote standing on its own ("word - next"): look past it.
            end = start
        }
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c in JOINERS

    private fun isBetweenWords(c: Char) = c == ' ' || c == '\t' || c in SOFT_PUNCTUATION

    /** Characters that are part of a word ("don't", "self-aware"). */
    private const val JOINERS = "'’-"
    /** Punctuation that doesn't end the sentence. */
    private const val SOFT_PUNCTUATION = ",;:\"“”„()[]{}"
}

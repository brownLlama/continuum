package com.brownllama.continuum.prediction

/**
 * A language the keyboard types in. Each has its own word list, next-word model and learned words
 * (what is typed in one never shows up in the other), and the things that belong to English
 * spelling only ([english]) are off for the others.
 */
enum class Language(
    /** The saved setting ([Prefs.LANGUAGE]). */
    val code: String,
    /** How the language is named, in its own words. */
    val label: String,
    val wordsAsset: String,
    val nextAsset: String,
    /** Lists of common misspellings (English only so far) and of emoji for words. */
    val misspellingsAsset: String?,
    val emojiAsset: String?,
    /** The file the user's learned words of this language are kept in. */
    val userFile: String,
    /**
     * English things: contractions ("don't" for "dont"), two words written as one ("thankyou"),
     * number endings ("16th") and spellings ("some1"), and a capital "I".
     */
    val english: Boolean,
    /** What the bar offers at the start of a sentence and after a word, when nothing better is known. */
    val startWords: List<String>,
    val followWords: List<String>,
) {
    EN(
        "en", "English", "words.txt", "next_words.tsv", "misspellings.txt", "emoji_keywords.tsv", "user_model.tsv",
        english = true, startWords = listOf("I", "The", "In", "It", "What"), followWords = listOf("the", "to", "and", "a", "is"),
    ),
    DE(
        "de", "Deutsch", "words_de.txt", "next_words_de.tsv", null, "emoji_keywords_de.tsv", "user_model_de.tsv",
        english = false, startWords = listOf("ich", "das", "die", "der", "und"), followWords = listOf("die", "der", "und", "ich", "das"),
    );

    /** The language a swipe switches to. */
    val other: Language get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The language saved as [code], English if none. */
        fun named(code: String?): Language = entries.firstOrNull { it.code == code } ?: EN
    }
}

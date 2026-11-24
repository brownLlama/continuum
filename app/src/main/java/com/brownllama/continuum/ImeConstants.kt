// Times, limits and tables used by the keyboard service's files.
package com.brownllama.continuum

import android.content.ClipDescription
import android.text.InputType
import com.brownllama.continuum.sticker.Sticker

/** ClipDescription.EXTRA_IS_SENSITIVE (Android 13); the same key works as a plain extra before it. */
internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
internal const val VISIBLE_SLOTS = 3
/** The middle slot holds the word space will commit. */
internal const val DEFAULT_SLOT = 1
internal const val MAX_SUGGESTIONS = 12
/** Four rows of the emoji panel's "frequently used" section. */
internal const val FREQUENT_EMOJI = 32
/** Sticker thumbnails are decoded about a quarter of the screen width across (the panel has 4 columns). */
internal const val STICKER_THUMBS_ACROSS = 4
/** Two shift taps within this time turn on caps lock. */
internal const val DOUBLE_TAP_MS = 350L
/** Full-mode autocorrect keeps a real typed word unless a correction scores this much better (log). */
internal const val KEEP_REAL_WORD_MARGIN = 5f
internal const val STALE_UPDATE_MS = 300L
/** How long the cursor must rest before the bar's predictions are redone for its new place. */
internal const val FOLLOW_CURSOR_MS = 150L
internal const val HOUR_MS = 60L * 60 * 1000
/** A newly learned word is saved this long after the last one, so typing a few new words is one write. */
internal const val SAVE_DELAY_MS = 3_000L
/** GIF search: how long typing must pause before the results update. */
internal const val LIVE_SEARCH_MS = 600L
/** Punctuation that sticks to the previous word, taking the space before it. */
internal const val ATTACHING_PUNCTUATION = ".,!?;:"
internal const val SENTENCE_END = ".!?"
/** "i" as a word of its own (or before an apostrophe: "i'm"), not inside one ("in"). */
internal val LONE_I = Regex("(?<!\\p{L})i(?!\\p{L})")
/** Characters that join two parts into one word ("zazzy's", "self-aware"). */
internal const val JOINERS = "'’-"
/** Number spellings offered next to a word ("someone" → "some1"). */
internal val TEXT_SPEAK = mapOf(
    "someone" to "some1", "anyone" to "any1", "everyone" to "every1", "noone" to "no1",
    "today" to "2day", "tonight" to "2nite", "tomorrow" to "2moro", "before" to "b4",
    "forever" to "4ever", "great" to "gr8", "later" to "l8r", "wait" to "w8", "mate" to "m8",
)
/** Typed from a longer hold on a, o, u or s; they join the word around them (see onText). */
internal const val GERMAN_LETTERS = "äöüÄÖÜß"
/** Shown after a double-space period, "." highlighted; the bar scrolls to the rest. */
internal val PUNCTUATION_BAR = listOf("!", ".", "?", ",", ";", ":", "/")

internal val PASSWORD_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
)
internal val EMAIL_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
)
internal val ADDRESS_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    InputType.TYPE_TEXT_VARIATION_URI,
)

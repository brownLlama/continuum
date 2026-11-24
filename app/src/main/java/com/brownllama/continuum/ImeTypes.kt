// The small records the keyboard service keeps: the last commit, glue, shortcut expansion and so on.
package com.brownllama.continuum

import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.stats.TypingStats

/**
 * The last committed word's taps, for [touch] once it's clear the word was kept ([flushTouch]),
 * and the taps that were deleted while typing it, each with the letter typed in its place.
 */
internal class PendingTouch(
    val layout: KeyLayout, val mode: Mode, val taps: List<Tap>, val key: String, val fixed: List<Pair<Tap, Char>>,
)

/** The last committed word, for [stats] once it's clear it was kept ([flushTouch]) or taken back. */
internal class PendingWord(val mode: Mode, val outcome: TypingStats.Outcome, val split: Boolean)

/**
 * The last word committed by space/suggestion, so backspace can reopen it for correction. [text]
 * is what was typed into the field, [learned] what was taught (with its [glue] prefix, if any).
 */
internal class LastCommit(
    val text: String, val spaced: Boolean, val taps: List<Tap>, val mode: Mode, val prevBefore: String?,
    val learned: String, val glue: Glue?, val sentenceStart: Boolean,
)

/**
 * A word that ended in an apostrophe or hyphen right before the cursor ("zazzy'"). The next word
 * typed is learned together with it ("zazzy's", key "zazzys"), replacing what was learned for
 * [prefix] on its own (the key [learnedKey]: "zazzy", or "m" for "mü"; null if nothing was);
 * [absorbed] records that this was undone already.
 */
internal class Glue(val prefix: String, val prevBefore: String?, val sentenceStart: Boolean, val learnedKey: String?) {
    var absorbed = false
}

/** The shortcut just expanded: [typed] ("##e") became [text]; a backspace right after undoes it. */
internal class Expansion(val typed: String, val text: String)

/** The app field's settings, put back when the search closes. */
internal class FieldState(
    val predict: Boolean, val capsAllowed: Boolean, val learning: Boolean, val prevWord: String?, val textReadable: Boolean?,
)

// Composing a word: decoding the taps, committing, learning and the suggestion bar.
package com.brownllama.continuum

import android.os.SystemClock
import com.brownllama.continuum.keyboard.Emoji
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.DecodeResult
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TextContext
import com.brownllama.continuum.prediction.UserModel
import com.brownllama.continuum.stats.TypingStats
import java.util.Locale

// ---- composing ------------------------------------------------------------------------

/**
 * Decodes [taps] and updates the composing text and the suggestion bar. [forceText] keeps a
 * reopened word as it was committed instead of the decoder's best guess; it then leads the bar
 * (and stays what space commits), with the alternatives after it.
 */
internal fun ContinuumIME.redecode(forceText: String? = null) {
    val ic = editor() ?: return
    if (taps.isEmpty()) return
    val d = decoder
    // The end of a joined word ("zazzy'" + "s") or of a number ("16" + "th") is not two words.
    val r = d?.decode(
        taps, tapMode, layout, prevWord, splits = wordGlue == null && afterNumber == null && language.english,
        start = prevWord == null && wordSentenceStart,
    )
    result = r
    predictionsShown = false
    deletedShown = false
    val literal = r?.literal ?: buildString { for (t in taps) append('a' + nearestLetter(t)) }
    // The typed letters spelled the way the word is when that differs only in capitals ("iphone"
    // → "iPhone", "i" → "I", a learned "brownLlama"): no letter changes, so it is still exact.
    val spelled = r?.words?.firstOrNull {
        it.key == literal && it.display.length == literal.length && it.display.equals(literal, ignoreCase = true)
    }?.display ?: literal
    val literalText = applyCase(spelled)
    val words = r?.words.orEmpty().map { applyCase(it.display) }
    // Typo corrections take their place among the runners-up by score.
    val more = (r?.words.orEmpty().drop(1) + r?.completions.orEmpty() + r?.corrections.orEmpty())
        .sortedByDescending { it.score }
        .map { applyCase(it.display) }

    // Right after a number, its ordinal ending is what these letters most likely are ("16" + "th").
    val ordinal = afterNumber?.let { applyCase(it) }
    val best = if (ordinal != null && taps.size == ordinal.length) ordinal else words.firstOrNull() ?: literalText
    // Letters picked exactly with the zoom spell a word the dictionary may not know: when every
    // letter was picked that way, the spelling itself is the default (and gets learned).
    val allExact = taps.all { it.exact >= 0 }
    val anyExact = taps.any { it.exact >= 0 }
    val composing = forceText ?: when (tapMode) {
        Mode.MINI -> if (allExact) literalText else best
        Mode.FULL -> literalText
    }
    ic.setComposingText(composing, 1)
    composingText = composing
    lastEditTime = SystemClock.uptimeMillis()

    // A number spelling of the best word ("someone" → "some1") is offered next to it.
    val speak = if (!language.english) null else TEXT_SPEAK[best.lowercase()]?.let { v -> if (best.first().isUpperCase()) v.replaceFirstChar { it.uppercaseChar() } else v }
    val others = (listOfNotNull(best, ordinal, speak) + more).distinct().filter { it != composing }
    // The bar shows as many words as fit and scrolls sideways to the rest.
    var list = when {
        // Reopened by backspace: the word as it was, then what else the taps could be.
        forceText != null -> {
            val typed = if (tapMode == Mode.FULL || anyExact) listOf(literalText) else emptyList()
            (listOf(forceText) + typed + others).distinct()
        }
        // Mini: best guess in the middle, runners-up either side, more to the right.
        // Partly exact spellings are offered next to the best guess.
        tapMode == Mode.MINI -> {
            val spelled = if (anyExact && literalText != composing) listOf(literalText) else emptyList()
            val ranked = (listOf(composing) + spelled + others).distinct()
            centerOut(ranked.take(VISIBLE_SLOTS), VISIBLE_SLOTS) + ranked.drop(VISIBLE_SLOTS)
        }
        // Full: what you typed on the left, the autocorrection in the middle.
        else -> {
            val pick = autocorrectPick(r, literalText, words)
            val rest = (listOf(pick) + others).distinct().filter { it != literalText && it != pick }
            if (pick == literalText) listOf(rest.getOrElse(0) { "" }, literalText) + rest.drop(1)
            else listOf(literalText, pick) + rest
        }
    }
    defaultSlot = if (forceText != null) 0 else DEFAULT_SLOT
    // Emoji for the best word (CLDR keywords, if the font can draw it) goes right after it.
    val emojiAfter = if (forceText != null) list.indexOf(best) else DEFAULT_SLOT
    r?.words?.firstOrNull()?.emoji?.takeIf { emojiAfter >= 0 && it in drawableEmoji }?.let { e ->
        list = list.take(emojiAfter + 1) + e + list.drop(emojiAfter + 1)
    }
    slots = pad(list.take(MAX_SUGGESTIONS))
    view?.setSuggestions(slots, defaultSlot)
}

/** At least [VISIBLE_SLOTS] entries, so the bar's fixed slots always line up. */
internal fun ContinuumIME.pad(list: List<String>) = list + List(maxOf(0, VISIBLE_SLOTS - list.size)) { "" }

/** Place ranked words so the best is in the middle slot, then alternating left / right. */
internal fun ContinuumIME.centerOut(ranked: List<String>, n: Int): List<String> {
    val out = MutableList(n) { "" }
    val c = n / 2
    for (rank in 0 until minOf(n, ranked.size)) {
        val off = (rank + 1) / 2
        out[if (rank % 2 == 1) c - off else c + off] = ranked[rank]
    }
    return out
}

/** Full mode: the word space will commit. Keeps real words unless the alternative is far more likely. */
internal fun ContinuumIME.autocorrectPick(r: DecodeResult?, literalText: String, words: List<String>): String {
    // The end of a joined word ("zazzy'" + "s") is typed as is, and so is a field that asks for no
    // suggestions (but not the GIF search opened from one).
    if (exactField && queryEditor == null) return literalText
    if (!autocorrect || r == null || wordGlue != null || afterNumber != null || taps.any { it.exact >= 0 }) return literalText
    val best = r.words.firstOrNull() ?: return literalText
    if (best.key == r.literal) {
        // The same letters with an apostrophe, hyphen or space ("dont" → "don't", "zazzys" →
        // "zazzy's", "thankyou" → "thank you") or German letters ("munchen" → "münchen"),
        // unless what was typed is a word as it stands ("its"; not a known misspelling like
        // "ofthe", which the word list also holds).
        val typedIsWord = r.literalIsWord &&
            r.words.any { it.key == r.literal && it.display.equals(r.literal, ignoreCase = true) }
        val respelled = best.display.any { !it.isLetter() || it in GERMAN_LETTERS }
        return if (!typedIsWord && respelled) words.first() else literalText
    }
    if (r.literalIsWord && best.score - r.literalScore < KEEP_REAL_WORD_MARGIN) return literalText
    if (taps.size == 1) return literalText
    return words.first()
}

/**
 * Commits [choice], or the default suggestion, in place of the composing text, and learns it.
 * [keepShown] instead leaves the composing text in the field as it is, without retyping it.
 */
internal fun ContinuumIME.commitWord(spaced: Boolean, choice: String?, keepShown: Boolean = false) {
    val ic = editor() ?: return
    val text = if (keepShown && composingText.isNotEmpty()) composingText
        else choice ?: slots.getOrNull(defaultSlot)?.takeIf { it.isNotEmpty() }
            ?: applyCase(result?.literal ?: "")
    if (keepShown) ic.finishComposingText() else ic.commitText(text + if (spaced) " " else "", 1)
    // A joined word replaces what was learned for its first part: "zazzy" + "'s" → "zazzy's".
    val g = wordGlue?.takeIf { Dictionary.keyOf(text) != null }
    if (g != null && !g.absorbed) {
        if (learning) g.learnedKey?.let { user.unlearn(pairPrev(g.prevBefore, g.sentenceStart), it) }
        g.absorbed = true
    }
    if (g != null) prevWord = g.prevBefore
    val learned = if (g != null) g.prefix + text else text
    val prevBefore = prevWord
    // An ordinal ending ("th" after "16") isn't a word of its own to learn.
    if (afterNumber == null) learn(learned, g?.sentenceStart ?: wordSentenceStart, picked = choice != null)
    // Where the taps landed counts once this word is kept (see flushTouch).
    flushTouch()
    val typedKey = Dictionary.keyOf(text)
    // What became of the word counts once the next one starts (see flushTouch).
    if (learning) {
        val outcome = when {
            fixing || deletedTaps.isNotEmpty() -> TypingStats.Outcome.FIXED
            choice != null -> TypingStats.Outcome.PICKED
            else -> TypingStats.Outcome.KEPT
        }
        pendingWord = PendingWord(tapMode, outcome, ' ' in text)
    }
    fixing = false
    if (learning && touchOn && typedKey != null) {
        // A tap deleted while typing was meant for the letter the word has there now (if the
        // word's letters still line up with its taps).
        val fixed = if (typedKey.length != taps.size) emptyList() else
            deletedTaps.mapNotNull { (at, tap) -> typedKey.getOrNull(at)?.let { tap to it } }
        pendingTouch = PendingTouch(layout, tapMode, ArrayList(taps), typedKey, fixed)
    }
    deletedTaps.clear()
    lastCommit = LastCommit(text, spaced, ArrayList(taps), tapMode, prevBefore, learned, g, g?.sentenceStart ?: wordSentenceStart)
    wordGlue = null
    taps.clear()
    result = null
    autoSpace = spaced
    updateAutoShift()
    showPredictions()
}

/** Commits a word in progress as it stands (when the keyboard closes or switches mode). */
internal fun ContinuumIME.finishComposing(keepShown: Boolean = false) {
    if (taps.isEmpty()) return
    commitWord(spaced = false, choice = null, keepShown = keepShown)
    lastCommit = null
}

/**
 * Records a committed word, and how the user spells it. Only capitals that say something about
 * the word change that spelling: a capital starting a sentence doesn't ("Zorp is…" stays "zorp"),
 * nor do all caps (caps lock, "NEVER"). Only a capital inside a word is shown without shift
 * ("brownLlama"; the first letter's case is the dictionary's, see Decoder.shownForm).
 */
internal fun ContinuumIME.learn(word: String, sentenceStart: Boolean, picked: Boolean = false) {
    // Two words typed as one ("thank you" for "thankyou") are learned one after the other.
    if (' ' in word) {
        word.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, w -> learn(w, sentenceStart && i == 0) }
        return
    }
    // An emoji (or anything without letters) is not learned, and the word before it no longer counts.
    // Nor is a word with digits ("some1"): its key would drop those and match other words. A
    // German word is learned under its base letters ("münchen" as "munchen", see keyOf).
    val key = Dictionary.keyOf(word)?.takeIf { word.none { it.isDigit() } } ?: run { prevWord = null; return }
    // Fields that ask for no learning (incognito) only track the previous word, for predictions.
    if (!learning) {
        prevWord = key
        return
    }
    user.unblock(word)
    // null keeps the spelling learned before (or the dictionary's).
    val form = when {
        word.none { it.isUpperCase() } -> word
        // All caps is caps lock (or emphasis): capitals only ever come from shift.
        word.count { it.isLetter() } > 1 && word.none { it.isLowerCase() } -> null
        sentenceStart && word.drop(1).none { it.isUpperCase() } -> null
        else -> word
    }
    user.learn(pairPrev(prevWord, sentenceStart), key, form)
    // Picking a new word from the bar confirms it, as typing it a second time would.
    if (picked) user.confirm(key)
    prevWord = key
}

/**
 * The word a word is learned after: [prev], or at the start of a sentence, the sentence start
 * ([UserModel.START]), so the words the user begins with are learned as well.
 */
internal fun ContinuumIME.pairPrev(prev: String?, sentenceStart: Boolean) = prev ?: if (sentenceStart) UserModel.START else null

/** Takes back what [learn] taught for [text] typed after [prev] (each word of it, if it is two). */
internal fun ContinuumIME.unlearn(prev: String?, text: String) {
    var before = prev
    for (word in text.split(' ')) {
        val key = Dictionary.keyOf(word) ?: continue
        user.unlearn(before, key)
        before = key
    }
}

/**
 * The glue for a joiner or German letter typed right after the commit [last]: the next word
 * joins its (last) word, and replaces what was learned for that.
 */
internal fun ContinuumIME.glueAfter(last: LastCommit, text: String): Glue {
    val words = last.learned.split(' ')
    if (words.size < 2) return Glue(last.learned + text, last.prevBefore, wordSentenceStart, Dictionary.keyOf(last.learned))
    return Glue(words.last() + text, Dictionary.keyOf(words[words.size - 2]), false, Dictionary.keyOf(words.last()))
}

/**
 * Sets [prevWord] from the text before the cursor ([TextContext]). Fields that don't let the
 * keyboard read their text (some terminals and games answer with nothing) keep the word that
 * was typed last instead: an empty answer only counts once the field has shown it can answer.
 */
internal fun ContinuumIME.refreshContext() {
    if (textReadable == false) return
    val before = editor()?.getTextBeforeCursor(TextContext.WINDOW, 0) ?: return
    if (before.isNotEmpty()) textReadable = true else if (textReadable == null) return
    prevWord = TextContext.previousWord(before)
}

/** Snapshots the word and its alternatives before the first deletion of a run (see [deletedSlots]). */
internal fun ContinuumIME.rememberDeletedWord() {
    if (deletedSlots != null || composingText.isEmpty()) return
    deletedSlots = (listOf(composingText) + slots.filter { it.isNotEmpty() }).distinct()
}

/** The word was deleted: offer it again, then its alternatives (nothing is the default). */
internal fun ContinuumIME.showDeletedWord() {
    val list = deletedSlots
    deletedSlots = null
    if (list == null || !predict) return showPredictions()
    punctuationBar = false
    predictionsShown = false
    deletedShown = true
    slots = pad(list.take(MAX_SUGGESTIONS))
    view?.setSuggestions(slots, -1)
}

/** Next-word predictions for the empty bar (does nothing while a word is being typed). */
internal fun ContinuumIME.showPredictions() {
    punctuationBar = false
    deletedShown = false
    if (taps.isNotEmpty()) return
    predictionsShown = false
    if (predict) refreshContext()
    val v = view ?: return
    val d = decoder
    if (!predict || d == null) {
        slots = listOf("", "", "")
        v.setSuggestions(slots, -1)
        return
    }
    predictionsShown = true
    // Lowercase unless shift is on, like typed words (see applyCase).
    val words = d.predict(prevWord, sentenceStart = shift != Shift.OFF, limit = MAX_SUGGESTIONS).map {
        val plain = plainCase(it)
        when (shift) {
            Shift.OFF -> plain
            Shift.ON -> plain.replaceFirstChar { c -> c.uppercaseChar() }
            Shift.LOCK -> plain.uppercase(Locale.ROOT)
        }
    }
    // Left to right by likelihood ("the | I | in").
    slots = pad(words)
    v.setSuggestions(slots, -1)
}

/**
 * Applies the shift state of the taps to a suggestion. Capitals come only from shift: the word
 * is lowercase but for capitals inside it ([plainCase]: "london", "iPhone"), then all caps if
 * every letter was shifted, else each letter typed with shift is a capital (when the word's
 * letters line up with the taps), else just the first letter if that was shifted.
 */
internal fun ContinuumIME.applyCase(word: String): String {
    if (word.isEmpty() || taps.isEmpty()) return word
    val plain = plainCase(word)
    if (taps.size > 1 && taps.all { it.upper }) return plain.uppercase(Locale.ROOT)
    if (plain.count { it.isLetter() } == taps.size) {
        var t = 0
        return String(CharArray(plain.length) { i ->
            val ch = plain[i]
            if (ch.isLetter() && taps[t++].upper) ch.uppercaseChar() else ch
        })
    }
    return if (taps[0].upper) plain.replaceFirstChar { it.uppercaseChar() } else plain
}

/**
 * How a word is spelled when shift wasn't pressed: lowercase ("hey", "london", even if it was
 * learned or listed with a capital), except a word with a capital inside it ("iPhone",
 * "McDonald", one the user typed that way), and "I" and its contractions ("I'm", "I'll", "I'd",
 * "I've"), which always have a capital I. Two words ("am I") are each treated so.
 */
internal fun ContinuumIME.plainCase(word: String): String = word.split(' ')
    .joinToString(" ") { w -> if (w.drop(1).any { it.isUpperCase() }) w else w.lowercase(Locale.ROOT) }
    .let { if (language.english) it.replace(LONE_I, "I") else it }

/** The letter (0..25) closest to a tap, ignoring word likelihood. */
internal fun ContinuumIME.nearestLetter(t: Tap): Int {
    if (t.exact >= 0) return t.exact
    var best = 0
    var bestD = Float.MAX_VALUE
    for (c in 0 until 26) {
        val d = when (mode) {
            Mode.MINI -> (t.x - layout.miniSlot[c]).let { it * it }
            Mode.FULL -> (t.x - layout.fullX[c]).let { it * it } + (t.y - layout.fullY[c]).let { it * it }
        }
        if (d < bestD) {
            bestD = d
            best = c
        }
    }
    return best
}

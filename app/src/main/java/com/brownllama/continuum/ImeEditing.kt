// What the keys do to the text: typing, backspace, space, enter, suggestions and shortcuts.
package com.brownllama.continuum

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.edit
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.stats.TypingStats
import kotlin.math.abs

internal fun ContinuumIME.handleLetterTap(tap: Tap) {
    val upper = shift != Shift.OFF
    val t = tap.copy(upper = upper)
    lastExpansion = null
    if (taps.isEmpty() && expandShortcut('a' + nearestLetter(t))) return
    if (!predict) {
        val code = nearestLetter(t)
        val ch = ('a' + code).let { if (upper) it.uppercaseChar() else it }
        editor()?.commitText(ch.toString(), 1)
        autoSpace = false
        if (shift == Shift.ON) setShift(Shift.OFF)
        return
    }
    deletedSlots = null
    if (taps.isEmpty()) {
        lastCommit = null
        tapMode = mode
        wordSentenceStart = atSentenceStart()
        afterNumber = ordinalAfterCursor()
        wordGlue = pendingGlue?.takeIf {
            editor()?.getTextBeforeCursor(it.prefix.length, 0)?.toString() == it.prefix
        }
        pendingGlue = null
        refreshContext()
    }
    taps += t
    if (shift == Shift.ON) setShift(Shift.OFF)
    redecode()
}

internal fun ContinuumIME.handleText(text: String) {
    val ic = editor() ?: return
    lastExpansion = null
    if (taps.isEmpty() && text.length == 1 && expandShortcut(text[0])) return
    val german = text.length == 1 && text[0] in GERMAN_LETTERS
    if (german) {
        // A German letter inside a word ("Gr" + "ü"): the letters so far stay exactly as shown,
        // and the rest of the word is typed as is after it (see Glue), not corrected apart.
        val before = pendingGlue?.takeIf { taps.isEmpty() && ic.getTextBeforeCursor(it.prefix.length, 0)?.toString() == it.prefix }
        if (taps.isNotEmpty()) commitWord(spaced = false, choice = null, keepShown = true)
        val last = lastCommit?.takeIf { !it.spaced }
        pendingGlue = when {
            last != null -> glueAfter(last, text)
            before != null -> Glue(before.prefix + text, before.prevBefore, before.sentenceStart, before.learnedKey)
            else -> Glue(text, prevWord, atSentenceStart(), null)
        }
        ic.commitText(text, 1)
        autoSpace = false
        lastCommit = null
        if (shift == Shift.ON) setShift(Shift.OFF)
        updateAutoShift()
        showPredictions()
        return
    }
    if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
    // "zazzy" + "'": the next word joins this one (see Glue).
    val joined = lastCommit?.takeIf { !it.spaced && text.length == 1 && text[0] in JOINERS }
    pendingGlue = joined?.let { glueAfter(it, text) }
    if (queryEditor == null && text.length == 1 && text[0] in '0'..'9') {
        // Digits go as key presses, like other keyboards do: one-time-code boxes often move to
        // the next box on the key event and ignore text committed without one.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (text[0] - '0'))
        autoSpace = false
    } else if (text.length == 1 && text[0] in ATTACHING_PUNCTUATION && autoSpace) {
        // "word |" + "." → "word. |"
        ic.beginBatchEdit()
        if (ic.getTextBeforeCursor(1, 0) == " ") ic.deleteSurroundingText(1, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        autoSpace = true
    } else {
        ic.commitText(text, 1)
        autoSpace = false
    }
    lastCommit = null
    if (text.length == 1 && text[0] in SENTENCE_END) prevWord = null
    updateAutoShift()
    showPredictions()
}

internal fun ContinuumIME.handleBackspace() {
    val ic = editor() ?: return
    if (taps.isNotEmpty()) {
        rememberDeletedWord()
        val removed = taps.removeAt(taps.lastIndex)
        deletedTaps.putIfAbsent(taps.size, removed)
        if (taps.isEmpty()) {
            // The whole word went: what is typed next may be another word altogether (but
            // most likely the same one, typed again: a fix).
            deletedTaps.clear()
            fixing = true
            ic.commitText("", 1)
            result = null
            updateAutoShift()
            showDeletedWord()
        } else {
            redecode()
        }
        return
    }
    if (undoExpansion(ic)) return
    if (reopenLastCommit(ic)) return
    lastCommit = null
    autoSpace = false
    val selected = ic.getSelectedText(0)
    if (!selected.isNullOrEmpty()) ic.commitText("", 1) else deleteBefore(ic)
    updateAutoShift()
    showPredictions()
}

/**
 * Reopens the word just committed (if it's still right before the cursor) so another suggestion
 * can be picked, and takes back what committing it taught the user model.
 */
internal fun ContinuumIME.reopenLastCommit(ic: InputConnection): Boolean {
    val last = lastCommit ?: return false
    val expected = last.text + if (last.spaced) " " else ""
    if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
    // Taken back: its taps don't say where the user's fingers land for those letters, and the
    // word typed or picked in its place is a fix.
    pendingTouch = null
    deletedTaps.clear()
    pendingWord?.let { if (it.split) stats.splitUndone(it.mode) }
    pendingWord = null
    fixing = true
    if (learning) unlearn(pairPrev(last.prevBefore, last.sentenceStart), last.learned)
    prevWord = last.prevBefore
    ic.beginBatchEdit()
    ic.deleteSurroundingText(expected.length, 0)
    afterNumber = ordinalAfterCursor()
    taps.clear()
    taps.addAll(last.taps)
    tapMode = last.mode
    wordGlue = last.glue
    lastCommit = null
    autoSpace = false
    redecode(forceText = last.text)
    ic.endBatchEdit()
    return true
}

/**
 * "##" right before the cursor and a shortcut saved for [key] (a letter or digit, either case):
 * replaces the "##" and the key with the shortcut's text. Not in password and number fields.
 */
internal fun ContinuumIME.expandShortcut(key: Char): Boolean {
    if (!predict || key !in Prefs.SHORTCUT_KEYS) return false
    val text = prefs.getString(Prefs.shortcut(key), null)
    if (text.isNullOrEmpty()) return false
    val ic = editor() ?: return false
    val trigger = Prefs.SHORTCUT_TRIGGER
    if (ic.getTextBeforeCursor(trigger.length, 0)?.toString() != trigger) return false
    ic.beginBatchEdit()
    ic.deleteSurroundingText(trigger.length, 0)
    ic.commitText(text, 1)
    ic.endBatchEdit()
    lastExpansion = Expansion(trigger + key, text)
    lastCommit = null
    pendingGlue = null
    deletedSlots = null
    autoSpace = false
    if (shift == Shift.ON) setShift(Shift.OFF)
    updateAutoShift()
    showPredictions()
    return true
}

/** Backspace right after [expandShortcut]: puts back the "##e" that was typed. */
internal fun ContinuumIME.undoExpansion(ic: InputConnection): Boolean {
    val last = lastExpansion ?: return false
    lastExpansion = null
    if (ic.getTextBeforeCursor(last.text.length, 0)?.toString() != last.text) return false
    ic.beginBatchEdit()
    ic.deleteSurroundingText(last.text.length, 0)
    ic.commitText(last.typed, 1)
    ic.endBatchEdit()
    updateAutoShift()
    showPredictions()
    return true
}

internal fun ContinuumIME.handleDeleteWord() {
    val ic = editor() ?: return
    // A word just committed is reopened first, so its alternatives can be offered once it's gone.
    if (taps.isEmpty()) reopenLastCommit(ic)
    if (taps.isNotEmpty()) {
        rememberDeletedWord()
        taps.clear()
        deletedTaps.clear()
        fixing = true
        ic.commitText("", 1)
        result = null
        lastCommit = null
        autoSpace = false
        updateAutoShift()
        showDeletedWord()
        return
    }
    val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
    var end = before.length
    while (end > 0 && before[end - 1].isWhitespace()) end--
    var start = end
    while (start > 0 && !before[start - 1].isWhitespace()) start--
    // Punctuation after a word ("word." or "word?!") goes on its own; the next swipe takes the word.
    var punct = end
    while (punct > start && !before[punct - 1].isLetterOrDigit()) punct--
    if (punct in (start + 1) until end) start = punct
    val n = before.length - start
    val word = before.substring(start, end)
    if (n > 0) ic.deleteSurroundingText(n, 0) else deleteBefore(ic)
    lastCommit = null
    autoSpace = false
    updateAutoShift()
    deletedSlots = if (predict && n > 0) alternativesFor(word) else null
    if (n > 0) fixing = true
    showDeletedWord()
}

internal fun ContinuumIME.handleSpace() {
    val ic = editor() ?: return
    lastExpansion = null
    pendingGlue = null
    if (taps.isNotEmpty()) {
        commitWord(spaced = true, choice = null)
        return
    }
    // A space right after "word " makes it "word. ", however long ago the first one was
    // (not in passwords, emails or numbers).
    val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
    val doubled = predict && before.length == 2 && before[1] == ' ' &&
        !before[0].isWhitespace() && before[0] !in ATTACHING_PUNCTUATION
    if (doubled) {
        // Double space → ". "
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        prevWord = null
    } else {
        ic.commitText(" ", 1)
    }
    autoSpace = false
    lastCommit = null
    updateAutoShift()
    showPredictions()
    if (doubled) showPunctuationBar(replacePeriod = true)
}

/**
 * After a double-space period the bar offers other punctuation until a letter is typed. The
 * first pick replaces the "."; later picks add to it ("?" then "!" → "?! ").
 */
internal fun ContinuumIME.showPunctuationBar(replacePeriod: Boolean) {
    punctuationBar = true
    deletedShown = false
    predictionsShown = false
    this.replacePeriod = replacePeriod
    slots = PUNCTUATION_BAR
    view?.setSuggestions(slots, if (replacePeriod) PUNCTUATION_BAR.indexOf(".") else -1)
}

/** Deletes the character before the cursor (a key event for apps, which some fields need). */
internal fun ContinuumIME.deleteBefore(ic: InputConnection) {
    if (queryEditor != null) ic.deleteSurroundingText(1, 0) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
}

internal fun ContinuumIME.handleEnter() {
    val ic = editor() ?: return
    lastExpansion = null
    if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
    if (queryEditor != null) {
        // Searching GIFs: show the results in the full grid.
        view?.closeGifSearch()
        onGifSearchClose()
        gifs.search(query.toString())
        return
    }
    val action = currentInputEditorInfo?.let(::enterAction) ?: EditorInfo.IME_ACTION_NONE
    if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
        ic.performEditorAction(action)
    } else {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }
    autoSpace = false
    lastCommit = null
    prevWord = null
    updateAutoShift()
    showPredictions()
}

internal fun ContinuumIME.handleShift() {
    val now = SystemClock.uptimeMillis()
    setShift(
        when (shift) {
            Shift.OFF -> Shift.ON
            Shift.ON -> if (now - lastShiftTap < DOUBLE_TAP_MS) Shift.LOCK else Shift.OFF
            Shift.LOCK -> Shift.OFF
        },
    )
    lastShiftTap = now
    if (taps.isEmpty() && !punctuationBar) showPredictions()
}

internal fun ContinuumIME.handleSuggestion(slot: Int) {
    val word = slots.getOrNull(slot).orEmpty()
    if (word.isEmpty()) return
    val ic = editor() ?: return
    lastExpansion = null
    val replaced = if (replacePeriod) ". " else " "
    if (punctuationBar && taps.isEmpty() && ic.getTextBeforeCursor(2, 0)?.toString()?.endsWith(replaced) == true) {
        // Swap the double-space "." (or add to the punctuation picked before): "word. " → "word? ".
        ic.beginBatchEdit()
        ic.deleteSurroundingText(replaced.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        autoSpace = true
        updateAutoShift()
        showPunctuationBar(replacePeriod = false)
        return
    }
    if (taps.isNotEmpty()) {
        // Words and emoji alike replace the word being typed.
        commitWord(spaced = true, choice = word)
    } else if (Dictionary.keyOf(word) == null) {
        // Punctuation predictions attach to the previous word like typed punctuation.
        onText(word)
    } else {
        // A next-word prediction, or a deleted word's alternative put back in its place.
        if (learning) {
            if (deletedShown) stats.word(mode, TypingStats.Outcome.FIXED) else stats.prediction(mode)
        }
        if (deletedShown) fixing = false
        val sentenceStart = atSentenceStart()
        ic.commitText("$word ", 1)
        learn(word, sentenceStart, picked = true)
        lastCommit = null
        autoSpace = true
        updateAutoShift()
        showPredictions()
    }
}

internal fun ContinuumIME.handleModeToggle() {
    // Password and number fields stay in full mode, also while searching GIFs from one.
    if (!predict || fieldState?.predict == false) {
        Toast.makeText(this, R.string.mode_locked, Toast.LENGTH_SHORT).show()
        return
    }
    finishComposing()
    val next = if (mode == Mode.MINI) Mode.FULL else Mode.MINI
    prefs.edit { putString(Prefs.MODE, if (next == Mode.FULL) "full" else "mini") }
    prefMode = next
    mode = next
    view?.mode = next
    showPredictions()
}

internal fun ContinuumIME.handleAutocorrectToggle() {
    autocorrect = !autocorrect
    prefs.edit { putBoolean(Prefs.AUTOCORRECT, autocorrect) }
    view?.autocorrectOn = autocorrect
    Toast.makeText(this, if (autocorrect) R.string.autocorrect_on else R.string.autocorrect_off, Toast.LENGTH_SHORT).show()
    if (taps.isNotEmpty()) redecode()
}

internal fun ContinuumIME.handleForgetSuggestion(slot: Int) {
    val word = slots.getOrNull(slot).orEmpty()
    if (word.isEmpty()) return
    user.forget(Dictionary.keyOf(word), word)
    if (taps.isNotEmpty()) redecode() else showPredictions()
}

internal fun ContinuumIME.handleEmoji(emoji: String) {
    onText(emoji)
    if (learning) emojiHistory.use(emoji)
    view?.frequentEmoji = emojiHistory.top(FREQUENT_EMOJI)
}

/**
 * Where the selection is in the app's text, as (start, end) with start <= end (equal: just a cursor):
 * what the field says when asked, else what it last told us. Null when neither is known.
 */
private fun ContinuumIME.selectionOf(ic: InputConnection): Pair<Int, Int>? {
    val ex = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 2000 }, 0)
    if (ex != null && ex.selectionStart >= 0 && ex.selectionEnd >= 0) {
        val a = ex.startOffset + ex.selectionStart
        val b = ex.startOffset + ex.selectionEnd
        return minOf(a, b) to maxOf(a, b)
    }
    return if (selStart >= 0 && selEnd >= 0) minOf(selStart, selEnd) to maxOf(selStart, selEnd) else null
}

/**
 * The finger slid along the grass: the cursor moves [steps] characters, as arrow keys would move it,
 * but only within the text (an arrow key at the end of a text can leave the field for another one).
 * With text selected, it stretches or shrinks the selection (its right end) instead. A word in
 * progress is finished first, as it stands. Not while the GIF search has the keyboard.
 */
internal fun ContinuumIME.handleCursorMove(steps: Int) {
    if (queryEditor != null || steps == 0) return
    val ic = editor() ?: return
    finishComposing()
    lastExpansion = null
    autoSpace = false
    deletedSlots = null
    val left = steps < 0
    val n = abs(steps)
    val span = selectionOf(ic)
    if (span != null && span.first != span.second) {
        // Text is selected: move its right end, from the exact position when the app tells us.
        val (s, e) = span
        val room = if (left) e - s else ic.getTextAfterCursor(n, 0)?.length ?: n
        val newEnd = if (left) e - minOf(n, room) else e + minOf(n, room)
        ic.setSelection(s, newEnd)
        return
    }
    // The app didn't say where the cursor is: arrow keys, with shift held if text is selected.
    val selected = if (span == null) ic.getSelectedText(0)?.length ?: 0 else 0
    val room = if (left) (ic.getTextBeforeCursor(n, 0)?.length ?: n) + selected else ic.getTextAfterCursor(n, 0)?.length ?: n
    arrows(ic, right = !left, times = minOf(n, room), shift = selected > 0)
}

/**
 * Selects the word the cursor is in or at the end of (letters, digits, apostrophes and hyphens). It
 * sets the selection by position when the app says where the cursor is; otherwise it uses shifted
 * arrow keys.
 */
private fun ContinuumIME.selectWordAtCursor(ic: InputConnection) {
    fun inWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-'
    var span = selectionOf(ic)
    // Text already selected: the word is looked for at its end.
    if (span != null && span.first != span.second) {
        ic.setSelection(span.second, span.second)
        span = span.second to span.second
    }
    val back = ic.getTextBeforeCursor(WORD_SCAN, 0)?.takeLastWhile(::inWord)?.length ?: 0
    val ahead = ic.getTextAfterCursor(WORD_SCAN, 0)?.takeWhile(::inWord)?.length ?: 0
    if (back + ahead == 0) return
    if (span != null) {
        ic.setSelection(span.first - back, span.first + ahead)
        return
    }
    // The app doesn't say where the cursor is: to the start of the word, then over it with shift held.
    arrows(ic, right = false, times = back, shift = false)
    arrows(ic, right = true, times = back + ahead, shift = true)
}

private fun arrows(ic: InputConnection, right: Boolean, times: Int, shift: Boolean) {
    val key = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
    val now = SystemClock.uptimeMillis()
    repeat(times) {
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
    }
}

/** An item of the grass's hold menu: select the word or everything, cut, copy or paste. */
internal fun ContinuumIME.handleEditAction(action: KeyboardView.EditAction) {
    if (queryEditor != null) return
    val ic = editor() ?: return
    finishComposing()
    lastCommit = null
    lastExpansion = null
    autoSpace = false
    deletedSlots = null
    when (action) {
        KeyboardView.EditAction.SELECT_WORD -> selectWordAtCursor(ic)
        KeyboardView.EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
        KeyboardView.EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
        KeyboardView.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
        KeyboardView.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
    }
}

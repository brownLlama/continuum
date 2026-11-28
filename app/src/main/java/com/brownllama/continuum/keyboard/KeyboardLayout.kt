// Where the keys of the bar, the letters and the number pages go (the layout pass).
package com.brownllama.continuum.keyboard

import android.graphics.RectF
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Mode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sizes each slot to its word (with [MIN_SLOT_FRACTION] of the bar as the least), then widens
 * the slots that fit in the bar so they fill it exactly; the rest continue past the right edge.
 */
internal fun KeyboardView.layoutSuggestions() {
    val area = suggestionArea.width()
    val n = max(suggestions.indexOfLast { it.isNotEmpty() } + 1, MIN_SLOTS)
    if (area <= 0f) {
        slotEdges = FloatArray(n + 1)
        return
    }
    text.typeface = regular
    text.textSize = suggestionTextSize
    val minW = area * MIN_SLOT_FRACTION
    val widths = FloatArray(n) { i ->
        val s = suggestions.getOrNull(i).orEmpty()
        if (s.isEmpty()) minW else (text.measureText(s) + suggestionPad * 2).coerceIn(minW, area)
    }
    var fit = 0
    var used = 0f
    while (fit < n && used + widths[fit] <= area + 0.5f) used += widths[fit++]
    if (fit == 0) fit = 1.also { used = widths[0] }
    val extra = (area - used) / fit
    if (extra > 0f) for (i in 0 until fit) widths[i] += extra
    slotEdges = FloatArray(n + 1).also { e -> for (i in 0 until n) e[i + 1] = e[i] + widths[i] }
}

/** The suggestion slot under view x, or -1. */
internal fun KeyboardView.suggestionAt(x: Float): Int {
    val sx = x - suggestionArea.left + barScroll
    for (i in 0 until slotEdges.size - 1) if (sx >= slotEdges[i] && sx < slotEdges[i + 1]) return i
    return -1
}

/** Rebuilds every key rectangle for the current mode, panel, size and settings. */
internal fun KeyboardView.relayout() {
    layoutKeys()
    accessibility.changed()
}

internal fun KeyboardView.layoutKeys() {
    barKeys.clear()
    headerKeys.clear()
    keys.clear()
    stripRect.setEmpty()
    letterRect.setEmpty()
    val w = width.toFloat()
    if (w <= 0f) return
    val top = barH
    // Buttons of the "Remove from suggestions?" question that covers the bar.
    removeRect.set(w * 0.58f, 0f, w * 0.80f, top)
    cancelRect.set(w * 0.80f, 0f, w, top)
    if (panel == Panel.EMOJI) {
        layoutEmoji(w, top)
        invalidate()
        return
    }
    if (panel == Panel.GIF) {
        layoutGif(w, top)
        invalidate()
        return
    }
    if (panel == Panel.CLIPBOARD) {
        layoutClipboard(w, top)
        invalidate()
        return
    }
    if (gifSearching) layoutGifHeader(w)
    if (mode == Mode.MINI) {
        barKeys += Key(Kind.SHIFT, RectF(0f, 0f, w * 0.12f, top))
        addSuggestionKeys(w * 0.12f, w * 0.88f)
        barKeys += Key(Kind.BACKSPACE, RectF(w * 0.88f, 0f, w, top))
    } else {
        barKeys += Key(Kind.AUTOCORRECT, RectF(0f, 0f, w * 0.12f, top))
        // No ✔ (the back gesture closes the keyboard): the suggestions get the room.
        addSuggestionKeys(w * 0.12f, w)
    }
    // Mini rows share the slot geometry so strip characters sit above their letters.
    stripRect.set(pad, top, w - pad, top + stripH)
    letterRect.set(pad, stripRect.bottom, w - pad, stripRect.bottom + miniRowH)
    when {
        mode == Mode.MINI && panel == Panel.LETTERS -> Unit // the row is drawn and hit-tested by slot, not as keys
        mode == Mode.MINI -> layoutMiniNumbers(w)
        panel == Panel.LETTERS -> layoutFull(w, top)
        else -> layoutFullNumbers(w, top, if (panel == Panel.SYMBOLS) Layouts.FULL_NUMBERS_1 else Layouts.FULL_NUMBERS_2)
    }
    invalidate()
}

internal fun KeyboardView.addSuggestionKeys(l: Float, r: Float) {
    suggestionArea.set(l, 0f, r, barH)
    barKeys += Key(Kind.SUGGESTION, RectF(suggestionArea))
    layoutSuggestions()
}

/** Mini number mode (swipe down to toggle): symbol strip over a big digit row, same footprint as letters. */
internal fun KeyboardView.layoutMiniNumbers(w: Float) {
    for (i in 0 until 26) {
        keys += Key(
            Kind.TEXT, RectF(slotLeft(i), stripRect.top, slotLeft(i + 1), stripRect.bottom),
            Layouts.MINI_NUMBER_STRIP[i], style = Style.STRIP,
        )
    }
    val row = Layouts.MINI_NUMBER_ROW
    val cw = letterRect.width() / row.size
    row.forEachIndexed { i, s ->
        val l = letterRect.left + i * cw
        keys += Key(Kind.TEXT, RectF(l, letterRect.top, l + cw, letterRect.bottom), s, style = Style.BIG)
    }
}

internal fun KeyboardView.letterKey(c: Char, r: RectF, face: RectF = r) = Key(Kind.LETTER, r, c.toString(), layout.fullHints[c], face = face)

/**
 * Full mode letters: 10 key widths per row; the home row is offset by half a key, like a real
 * keyboard. Its outer keys also take the half key left over at each edge, but are drawn one key
 * wide, so their letters line up with the rest of the row.
 */
internal fun KeyboardView.layoutFull(w: Float, top: Float) {
    val kw = w / 10f
    val rh = fullRowH
    layout.top.forEachIndexed { i, c -> keys += letterKey(c, RectF(i * kw, top, (i + 1) * kw, top + rh)) }
    layout.mid.forEachIndexed { i, c ->
        val face = RectF((i + 0.5f) * kw, top + rh, (i + 1.5f) * kw, top + 2 * rh)
        val l = if (i == 0) 0f else face.left
        val r = if (i == layout.mid.lastIndex) w else face.right
        keys += letterKey(c, RectF(l, face.top, r, face.bottom), face)
    }
    val y2 = top + 2 * rh
    keys += Key(Kind.SHIFT, RectF(0f, y2, 1.5f * kw, y2 + rh))
    layout.bot.forEachIndexed { i, c ->
        keys += letterKey(c, RectF((i + 1.5f) * kw, y2, (i + 2.5f) * kw, y2 + rh))
    }
    keys += Key(Kind.BACKSPACE, RectF(8.5f * kw, y2, w, y2 + rh))
    bottomRow(w, top + 3 * rh, Kind.SYMBOLS, "123")
}

/** Full mode number page from one of the [Layouts] grids; digits get filled keys like a phone keypad. */
internal fun KeyboardView.layoutFullNumbers(w: Float, top: Float, rows: List<List<String>>) {
    val kw = w / 10f
    val rh = fullRowH
    rows.forEachIndexed { r, row ->
        row.forEachIndexed { i, s ->
            val rect = RectF(i * kw, top + r * rh, (i + 1) * kw, top + (r + 1) * rh)
            keys += when {
                s == Layouts.MORE -> Key(Kind.MORE, rect, if (panel == Panel.SYMBOLS) "}\\<" else "123", style = Style.FILLED)
                s == Layouts.BACKSPACE -> Key(Kind.BACKSPACE, rect)
                s.length == 1 && s[0].isDigit() -> Key(Kind.TEXT, rect, s, style = Style.FILLED)
                else -> Key(Kind.TEXT, rect, s)
            }
        }
    }
    bottomRow(w, top + 3 * rh, Kind.ABC, "")
}

/** Full mode's bottom row, shared by the letter and number pages. */
internal fun KeyboardView.bottomRow(w: Float, y: Float, first: Kind, firstLabel: String) {
    val kw = w / 10f
    val y1 = y + fullRowH
    keys += Key(first, RectF(0f, y, 1.5f * kw, y1), firstLabel)
    keys += Key(Kind.TEXT, RectF(1.5f * kw, y, 2.5f * kw, y1), if (emailField) "@" else ",", Layouts.EMOJI_SLOT)
    keys += Key(Kind.SPACE, RectF(2.5f * kw, y, 7.5f * kw, y1))
    keys += Key(Kind.TEXT, RectF(7.5f * kw, y, 8.5f * kw, y1), ".", PERIOD_ALTS)
    keys += Key(Kind.ENTER, RectF(8.5f * kw, y, w, y1))
}

internal fun KeyboardView.slotLeft(i: Int) = letterRect.left + i * slotW

internal fun KeyboardView.slotAt(x: Float) = (x - letterRect.left) / slotW - 0.5f

internal fun KeyboardView.nearestSlot(x: Float) = slotAt(x).roundToInt().coerceIn(0, 25)

/**
 * The mini row's height at view y, in rows of the stagger: 0 where the top row's letters are drawn,
 * 1 the home row, 2 the bottom row. A tap on the strip above or the gap below is held to just past
 * the rows, so it doesn't count for more than a tap at the edge of the letters.
 */
internal fun KeyboardView.rowAt(y: Float) =
    (((y - letterRect.top) / miniRowH - KeyLayout.MINI_ROW_TOP) / KeyLayout.MINI_ROW_STEP).coerceIn(-1f, 3f)

internal fun KeyboardView.slotCenter(i: Int) = letterRect.left + (i + 0.5f) * slotW

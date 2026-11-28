// Layout and hit-testing of the emoji, GIF and clipboard panels.
package com.brownllama.continuum.keyboard

import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextUtils
import kotlin.math.floor
import kotlin.math.max

/**
 * Emoji panel: one tab per section plus ⌫ in the bar, and the grid geometry. Sections are laid
 * out one after another, each starting on a new row ([emojiSectionRow] = its first row).
 */
internal fun KeyboardView.layoutEmoji(w: Float, top: Float) {
    emojiSections = listOf(EmojiGroup(Emoji.FREQUENT, frequentShown)) + emojiGroups
    // GIF and ⌫ tabs (or just ⌫); the clipboard is on mini mode's strip.
    val extra = if (showGifs) 2 else 1
    val tabW = w / (emojiSections.size + extra)
    emojiSections.indices.forEach { i ->
        barKeys += Key(Kind.EMOJI_TAB, RectF(i * tabW, 0f, (i + 1) * tabW, top), value = i)
    }
    if (showGifs) {
        barKeys += Key(Kind.GIF_TAB, RectF(w - 2 * tabW, 0f, w - tabW, top))
    }
    barKeys += Key(Kind.BACKSPACE, RectF(w - tabW, 0f, w, top))
    emojiRect.set(0f, top, w, top + bodyHeight())
    emojiCellW = w / EMOJI_COLUMNS
    emojiRowH = emojiCellW * 0.9f
    var rows = 0
    emojiSectionRow = IntArray(emojiSections.size) { i ->
        rows.also { rows += (emojiSections[i].emoji.size + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS }
    }
    emojiRows = rows
    emojiScroll = emojiScroll.coerceIn(0f, maxEmojiScroll)
    keys += closeKey(w, emojiRect.bottom)
}

/** GIF panel: [☺ | search field] in the bar, then the GIF grid with a floating ✕. */
internal fun KeyboardView.layoutGif(w: Float, top: Float) {
    barKeys += Key(Kind.GIF_BACK, RectF(0f, 0f, w * 0.12f, top))
    barKeys += Key(Kind.GIF_SEARCH, RectF(w * 0.12f, 0f, w, top))
    gifRect.set(0f, top, w, top + bodyHeight())
    gifCellW = (w - 2 * pad - (GIF_COLUMNS - 1) * gifGap) / GIF_COLUMNS
    gifCellH = gifCellW * 0.8f
    gifScroll = gifScroll.coerceIn(0f, maxGifScroll)
    keys += closeKey(w, gifRect.bottom)
}

/** The emoji / GIF / clipboard panels' floating ✕, in the bottom-right corner above [bottom]. */
internal fun KeyboardView.closeKey(w: Float, bottom: Float): Key {
    val d = 2 * dpu(CLOSE_RADIUS_DP)
    val right = w - dpu(CLOSE_SIDE_DP)
    val low = bottom - dpu(CLOSE_BOTTOM_DP)
    return Key(Kind.CLOSE, RectF(right - d, low - d, right, low))
}

/** Clipboard panel: [title | bin] in the bar, then two columns of cards with a floating ✕. */
internal fun KeyboardView.layoutClipboard(w: Float, top: Float) {
    barKeys += Key(Kind.CLIP_CLEAR, RectF(w * 0.86f, 0f, w, top))
    clipRect.set(0f, top, w, top + bodyHeight())
    clipCellW = (w - 2 * pad - (CLIP_COLUMNS - 1) * gifGap) / CLIP_COLUMNS
    layoutClipCards()
    clipScroll = clipScroll.coerceIn(0f, maxClipScroll)
    keys += closeKey(w, clipRect.bottom)
}

/**
 * Places the cards Pinterest-style: two columns, each card under the shorter one, each as tall as
 * what it holds: all of the text (up to [clipMaxTextH]), or the whole picture at its own shape (up to [clipMaxCardH]).
 */
internal fun KeyboardView.layoutClipCards() {
    clipCards.clear()
    clipContentH = 0f
    val w = clipCellW
    if (w <= 1f || clipRect.height() <= 0f) return
    val maxH = clipMaxCardH
    if (w != clipLayoutsW || maxH != clipLayoutsH) {
        clipLayouts.clear()
        clipLayoutsW = w
        clipLayoutsH = maxH
    }
    val minH = dpu(CLIP_MIN_CARD_DP)
    val columns = FloatArray(CLIP_COLUMNS) { gifGap }
    for ((i, clip) in clips.withIndex()) {
        val h = if (clip != null) {
            (clipLayout(clip).height + 2 * clipInset).coerceIn(minH, clipMaxTextH)
        } else {
            val image = clipImages.getOrNull(i)
            val aspect = image?.let { it.height.toFloat() / it.width } ?: clipAspects.getOrNull(i)?.takeIf { it > 0f } ?: 1f
            (w * aspect).coerceIn(minH, maxH)
        }
        val col = columns.indices.minBy { columns[it] }
        val l = pad + col * (w + gifGap)
        clipCards += RectF(l, columns[col], l + w, columns[col] + h)
        columns[col] += h + gifGap
    }
    clipContentH = columns.max()
}

/** A text card's laid-out text: all of it, unless more than fits on the tallest text card (then cut off with "…"). */
internal fun KeyboardView.clipLayout(clip: String): StaticLayout = clipLayouts.getOrPut(clip) {
    clipPaint.textSize = dpu(14f)
    clipPaint.typeface = regular
    val textW = (clipCellW - 2 * clipInset).toInt().coerceAtLeast(1)
    val lines = max(1, ((clipMaxTextH - 2 * clipInset) / clipPaint.fontSpacing).toInt())
    val text = clip.take(CLIP_CARD_CHARS)
    StaticLayout.Builder.obtain(text, 0, text.length, clipPaint, textW)
        .setMaxLines(lines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
}

/** Where clip card [i] is on screen (scrolled), into [out]. */
internal fun KeyboardView.clipCardRect(i: Int, out: RectF) {
    val card = clipCards.getOrNull(i) ?: return out.setEmpty()
    out.set(card)
    out.offset(0f, clipRect.top - clipScroll)
}

/** Index of the clip card under (x, y), or null. */
internal fun KeyboardView.clipAt(x: Float, y: Float): Int? {
    if (!clipRect.contains(x, y)) return null
    val r = hitRect
    return clips.indices.firstOrNull { clipCardRect(it, r); r.contains(x, y) }
}

/** The search header above the keyboard's bar: [☺ | search field | ⌄], then the strip of GIFs. */
internal fun KeyboardView.layoutGifHeader(w: Float) {
    val top = -gifHeaderH
    val bottom = top + barH
    headerKeys += Key(Kind.GIF_BACK, RectF(0f, top, w * 0.12f, bottom))
    headerKeys += Key(Kind.GIF_SEARCH, RectF(w * 0.12f, top, w * 0.88f, bottom))
    headerKeys += Key(Kind.GIF_CANCEL, RectF(w * 0.88f, top, w, bottom))
    gifRect.set(0f, -gifStripH, w, 0f)
    gifScroll = gifScroll.coerceIn(0f, maxGifScroll)
}

/**
 * Where GIF tile [i] is on screen (scrolled), into [out]: a cell of the grid, or of the search
 * header's single row.
 */
internal fun KeyboardView.gifTileRect(i: Int, out: RectF) {
    if (gifSearching) {
        val l = gifRect.left + pad + i * (stripCellW + gifGap) - gifScroll
        out.set(l, gifRect.top + gifGap, l + stripCellW, gifRect.top + gifGap + stripCellH)
    } else {
        val l = pad + (i % GIF_COLUMNS) * (gifCellW + gifGap)
        val t = gifRect.top + (i / GIF_COLUMNS) * (gifCellH + gifGap) + gifGap - gifScroll
        out.set(l, t, l + gifCellW, t + gifCellH)
    }
}

/** Index of the GIF tile under (x, y), or null. */
internal fun KeyboardView.gifAt(x: Float, y: Float): Int? {
    if (!gifRect.contains(x, y)) return null
    val r = hitRect
    return gifTiles.indices.firstOrNull { gifTileRect(it, r); r.contains(x, y) }
}

/** Grid section (tab) at the top of the visible grid; the last one once scrolled to the end. */
internal fun KeyboardView.activeEmojiSection(): Int {
    if (emojiScroll >= maxEmojiScroll - 1f && emojiScroll > 0f) return emojiSections.lastIndex
    val row = floor((emojiScroll + 1f) / emojiRowH).toInt()
    return emojiSectionRow.indexOfLast { it <= row }.coerceAtLeast(0)
}

/** The emoji under (x, y), with y in view coordinates. */
internal fun KeyboardView.emojiAt(x: Float, y: Float): String? {
    if (!emojiRect.contains(x, y)) return null
    val row = floor((y - emojiRect.top + emojiScroll) / emojiRowH).toInt()
    val sec = emojiSectionRow.indexOfLast { it <= row }.takeIf { it >= 0 } ?: return null
    val col = (x / emojiCellW).toInt().coerceIn(0, EMOJI_COLUMNS - 1)
    return emojiSections[sec].emoji.getOrNull((row - emojiSectionRow[sec]) * EMOJI_COLUMNS + col)
}

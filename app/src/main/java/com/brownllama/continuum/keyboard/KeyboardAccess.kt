// The keyboard as screen-reader nodes: names, and what activating one does.
package com.brownllama.continuum.keyboard

import android.graphics.Rect
import android.graphics.RectF
import android.view.inputmethod.EditorInfo
import com.brownllama.continuum.R
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything showing that a screen reader can name and activate, top to bottom: the GIF search
 * header, the bar (each suggestion on its own), the grid cells in view, mini mode's symbols and
 * letters, then the keys. Activating a letter types exactly that letter.
 */
internal fun KeyboardView.buildAccessNodes(): List<AccessNode> {
    val out = ArrayList<AccessNode>()
    val dy = overlayH + gifHeaderH
    fun add(l: Float, t: Float, r: Float, b: Float, label: String, activate: () -> Unit) {
        val bounds = Rect(l.roundToInt(), (t + dy).roundToInt(), r.roundToInt(), (b + dy).roundToInt())
        if (label.isNotBlank() && !bounds.isEmpty) out += AccessNode(bounds, label, activate)
    }
    fun add(r: RectF, label: String, activate: () -> Unit) = add(r.left, r.top, r.right, r.bottom, label, activate)
    fun addKey(k: Key) {
        val label = keyLabel(k) ?: return
        add(k.rect, label) { activateKey(k) }
    }

    for (k in headerKeys) addKey(k)
    if (confirmForget >= 0) {
        // The "Remove from suggestions?" question covers the bar.
        add(removeRect, resources.getString(R.string.forget_remove)) {
            val slot = confirmForget
            confirmForget = -1
            if (slot >= 0) listener.onForgetSuggestion(slot)
        }
        add(cancelRect, resources.getString(R.string.forget_cancel)) { confirmForget = -1 }
    } else {
        for (k in barKeys) {
            if (k.kind != Kind.SUGGESTION) {
                addKey(k)
                continue
            }
            for (i in 0 until slotEdges.size - 1) {
                val word = suggestions.getOrNull(i).orEmpty()
                val l = max(suggestionArea.left, suggestionArea.left + slotEdges[i] - barScroll)
                val r = min(suggestionArea.right, suggestionArea.left + slotEdges[i + 1] - barScroll)
                if (word.isNotEmpty() && r > l) add(l, 0f, r, barH, word) { listener.onSuggestion(i) }
            }
        }
    }
    val cell = hitRect
    when {
        panel == Panel.EMOJI -> {
            val first = floor(emojiScroll / emojiRowH).toInt()
            val last = min(emojiRows - 1, floor((emojiScroll + emojiRect.height()) / emojiRowH).toInt())
            for (row in first..last) {
                val sec = emojiSectionRow.indexOfLast { it <= row }.takeIf { it >= 0 } ?: continue
                val base = (row - emojiSectionRow[sec]) * EMOJI_COLUMNS
                val top = max(emojiRect.top, emojiRect.top + row * emojiRowH - emojiScroll)
                val bottom = min(emojiRect.bottom, emojiRect.top + (row + 1) * emojiRowH - emojiScroll)
                for (col in 0 until EMOJI_COLUMNS) {
                    val e = emojiSections[sec].emoji.getOrNull(base + col) ?: break
                    add(col * emojiCellW, top, (col + 1) * emojiCellW, bottom, e) { listener.onEmoji(e) }
                }
            }
        }
        showsGifs -> for (i in gifTiles.indices) {
            gifTileRect(i, cell)
            if (cell.intersect(gifRect)) add(cell, resources.getString(R.string.acc_gif, i + 1)) { listener.onGifPicked(i) }
        }
        panel == Panel.CLIPBOARD -> for ((i, clip) in clips.withIndex()) {
            clipCardRect(i, cell)
            val label = clip?.take(CLIP_PREVIEW_CHARS) ?: resources.getString(R.string.clip_image_label)
            if (cell.intersect(clipRect)) add(cell, label) { listener.onClipPicked(i) }
        }
    }
    if (mode == Mode.MINI && panel == Panel.LETTERS) {
        for (i in 0 until 26) {
            val s = Layouts.MINI_STRIP[i]
            add(slotLeft(i), stripRect.top, slotLeft(i + 1), stripRect.bottom, slotLabel(s)) { text(s) }
        }
        for (i in 0 until 26) {
            val letter = layout.miniOrder[i]
            add(slotLeft(i), letterRect.top, slotLeft(i + 1), letterRect.bottom, upper(letter.toString())) {
                listener.onLetterTap(Tap(i.toFloat(), 0f, exact = letter - 'a'))
            }
        }
    }
    for (k in keys) addKey(k)
    return out
}

/** What a strip entry is called: itself, or the panel its icon opens. */
internal fun KeyboardView.slotLabel(s: String) = when (s) {
    Layouts.EMOJI_SLOT -> resources.getString(R.string.acc_emoji)
    Layouts.CLIPBOARD_SLOT -> resources.getString(R.string.clip_title)
    else -> s
}

/** What a screen reader calls key [k]; null for keys it reaches some other way (the suggestion strip). */
internal fun KeyboardView.keyLabel(k: Key): String? {
    fun s(id: Int) = resources.getString(id)
    return when (k.kind) {
        Kind.LETTER -> upper(k.label)
        Kind.TEXT -> slotLabel(k.label)
        Kind.SHIFT -> s(when (shift) { Shift.OFF -> R.string.acc_shift; Shift.ON -> R.string.acc_shift_on; Shift.LOCK -> R.string.acc_caps_lock })
        Kind.BACKSPACE -> s(R.string.acc_delete)
        Kind.SPACE -> s(R.string.acc_space)
        Kind.ENTER -> s(
            when (if (gifSearching) EditorInfo.IME_ACTION_SEARCH else enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> R.string.acc_search
                EditorInfo.IME_ACTION_SEND -> R.string.acc_send
                EditorInfo.IME_ACTION_GO -> R.string.acc_go
                EditorInfo.IME_ACTION_NEXT -> R.string.acc_next
                EditorInfo.IME_ACTION_DONE -> R.string.acc_done
                else -> R.string.acc_enter
            },
        )
        Kind.SYMBOLS -> s(R.string.acc_symbols)
        Kind.MORE -> s(R.string.acc_more_symbols)
        Kind.ABC -> s(R.string.acc_letters)
        Kind.AUTOCORRECT -> s(if (autocorrectOn) R.string.autocorrect_on else R.string.acc_autocorrect_off)
        Kind.SUGGESTION -> null
        Kind.EMOJI_TAB -> resources.getString(R.string.acc_emoji_group, emojiSections.getOrNull(k.value)?.id.orEmpty())
        Kind.CLOSE -> s(R.string.acc_close)
        Kind.GIF_TAB -> s(R.string.acc_gifs)
        Kind.GIF_BACK -> s(R.string.acc_emoji)
        Kind.GIF_SEARCH -> gifQuery.ifEmpty { s(R.string.gif_search_hint) }
        Kind.GIF_CANCEL -> s(R.string.acc_close_search)
        Kind.CLIP_CLEAR -> s(R.string.acc_clip_clear)
    }
}

/** A screen reader's click on key [k]: what a tap in its middle does, a letter being typed exactly. */
internal fun KeyboardView.activateKey(k: Key) {
    when (k.kind) {
        Kind.BACKSPACE -> backspace()
        Kind.LETTER -> listener.onLetterTap(
            Tap(k.face.centerX() / (width / 10f), (k.face.centerY() - barH) / fullRowH, exact = k.label[0] - 'a'),
        )
        else -> keyTap(k, Pointer(this, -1, k.face.centerX(), k.face.centerY(), Area.KEY, k))
    }
    invalidate()
    accessibility.changed()
}

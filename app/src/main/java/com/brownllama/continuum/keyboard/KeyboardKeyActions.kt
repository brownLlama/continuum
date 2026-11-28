// What a tap on a key does, and the GIF search keyboard.
package com.brownllama.continuum.keyboard

import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Tap

internal fun KeyboardView.toggleNumbers() = showPanel(if (panel == Panel.LETTERS) Panel.SYMBOLS else Panel.LETTERS)

/** Types [s], or opens the emoji panel for the smiley and the clipboard panel for its icon. */
internal fun KeyboardView.text(s: String) {
    when (s) {
        Layouts.EMOJI_SLOT -> showPanel(Panel.EMOJI)
        Layouts.CLIPBOARD_SLOT -> showPanel(Panel.CLIPBOARD)
        else -> listener.onText(s)
    }
}

internal fun KeyboardView.backspace() = listener.onBackspace()

/** A tap (or a released long press, see [Pointer.longPressed]) on key [k]. */
internal fun KeyboardView.keyTap(k: Key, p: Pointer) {
    val hint = k.hint
    when (k.kind) {
        Kind.LETTER -> when {
            p.longPressed && p.options != null -> listener.onText(p.options!![p.alt.coerceIn(0, p.options!!.lastIndex)].toString())
            p.longPressed && hint != null -> listener.onText(hint)
            else -> listener.onLetterTap(Tap(p.downX / (width / 10f), (p.downY - barH) / fullRowH))
        }
        Kind.TEXT -> text(
            when {
                !p.longPressed || hint == null -> k.label
                hint == Layouts.EMOJI_SLOT -> hint
                else -> hint[p.alt.coerceIn(0, hint.length - 1)].toString()
            },
        )
        Kind.SHIFT -> when {
            !p.longPressed -> listener.onShift()
            else -> when (SHIFT_MENU.getOrNull(shiftMenuChoice(k, p))) {
                MENU_SETTINGS -> listener.onOpenSettings()
                MENU_KEYBOARDS -> listener.onPickInputMethod()
            }
        }
        Kind.BACKSPACE -> {} // handled on touch down, with auto-repeat
        Kind.SPACE -> listener.onSpace()
        Kind.ENTER -> listener.onEnter()
        Kind.SYMBOLS -> showPanel(Panel.SYMBOLS)
        Kind.MORE -> showPanel(if (panel == Panel.SYMBOLS) Panel.SYMBOLS2 else Panel.SYMBOLS)
        Kind.ABC -> showPanel(Panel.LETTERS)
        Kind.AUTOCORRECT -> listener.onAutocorrectToggle()
        Kind.SUGGESTION -> {
            val i = suggestionAt(p.downX)
            if (suggestions.getOrNull(i).orEmpty().isNotEmpty()) listener.onSuggestion(i)
        }
        Kind.EMOJI_TAB -> {
            gridScroller.forceFinished(true)
            emojiScroll = (emojiSectionRow[k.value] * emojiRowH).coerceIn(0f, maxEmojiScroll)
        }
        Kind.CLOSE -> showPanel(Panel.LETTERS)
        Kind.GIF_TAB -> showPanel(Panel.GIF)
        Kind.GIF_BACK -> showPanel(Panel.EMOJI)
        Kind.GIF_SEARCH -> openGifSearch()
        Kind.GIF_CANCEL -> closeGifSearch()
        Kind.CLIP_CLEAR -> if (clips.isNotEmpty()) listener.onClipClear()
    }
}

/**
 * The search field was tapped: the keyboard (letters, in the current mode) comes back under the
 * search header, and the GIFs move into the header's strip.
 */
internal fun KeyboardView.openGifSearch() {
    if (gifSearching) return
    markPanelChange()
    gifSearching = true
    panel = Panel.LETTERS
    gifScroll = 0f
    gridScroller.forceFinished(true)
    requestLayout()
    relayout()
    listener.onGifSearchOpen()
}

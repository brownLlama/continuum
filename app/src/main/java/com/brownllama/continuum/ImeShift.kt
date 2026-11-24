// Shift and caps, the text around the cursor, and the system bars.
package com.brownllama.continuum

import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputConnection
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.theme.KeyboardTheme

/**
 * The system draws its IME switcher and hide-keyboard buttons in a bar under the keys, which
 * reports itself as the caption bar. Since Android 16 an IME may hide it; switching keyboards
 * is then a long press on the space bar.
 */
internal fun ContinuumIME.showSystemButtons(show: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val c = window?.window?.insetsController ?: return
    // The keyboard-switch and hide buttons (Android 16+), and, where Android lets a keyboard ask,
    // the gesture line under the keys. Android decides whether it obeys; nothing breaks if it doesn't.
    val types = WindowInsets.Type.navigationBars() or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) WindowInsets.Type.captionBar() else 0
    if (show) c.show(types) else c.hide(types)
}

/** Matches the navigation bar area under the keyboard (and its button colours) to the theme. */
internal fun ContinuumIME.styleNavigationBar(theme: KeyboardTheme) {
    val w = window?.window ?: return
    @Suppress("DEPRECATION")
    w.navigationBarColor = theme.background
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        w.insetsController?.setSystemBarsAppearance(
            if (theme.isLight) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }
}

// ---- shift ----------------------------------------------------------------------------

internal fun ContinuumIME.setShift(s: Shift) {
    shift = s
    view?.shift = s
}

/** Capitalise at the start of a field and after sentence-ending punctuation. */
internal fun ContinuumIME.updateAutoShift() {
    if (shift == Shift.LOCK) return
    if (!autoCaps || !capsAllowed || taps.isNotEmpty()) {
        if (taps.isEmpty() && shift == Shift.ON && !autoCaps) setShift(Shift.OFF)
        return
    }
    val ic = editor() ?: return
    setShift(if (atSentenceStart(ic)) Shift.ON else Shift.OFF)
}

/** The ordinal ending for the number right before the cursor ("1" → "st", "12" → "th"), or null. */
internal fun ContinuumIME.ordinalAfterCursor(): String? {
    if (!language.english) return null
    val before = editor()?.getTextBeforeCursor(8, 0)?.toString() ?: return null
    val digits = before.takeLastWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    val n = digits.takeLast(2).toInt()
    return when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}

/**
 * Swipe-deleting a finished word: its alternatives, as the decoder reads the word's own letters
 * (the taps behind it are gone), so a slip like "your" for "some" can be picked again. The word
 * itself comes first.
 */
internal fun ContinuumIME.alternativesFor(word: String): List<String>? {
    val d = decoder ?: return null
    val letters = word.lowercase().filter { it in 'a'..'z' }
    if (letters.isEmpty() || letters.length > 20 || word.any { !it.isLetter() && it !in JOINERS }) return null
    val synthetic = letters.map { c ->
        val i = c - 'a'
        if (mode == Mode.MINI) Tap(layout.miniSlot[i].toFloat(), 0f) else Tap(layout.fullX[i], layout.fullY[i])
    }
    val r = d.decode(synthetic, mode, layout, null, adapt = false)
    val cap = word.first().isUpperCase()
    val alts = (r.words + r.corrections + r.completions).sortedByDescending { it.score }
        .map { c -> plainCase(c.display).let { w -> if (cap) w.replaceFirstChar { it.uppercaseChar() } else w } }
    return (listOf(word) + alts).distinct()
}

/** The cursor is at the start of the field, a line, or a sentence ("Done. |"). */
internal fun ContinuumIME.atSentenceStart(ic: InputConnection? = editor()): Boolean {
    val before = ic?.getTextBeforeCursor(3, 0)?.toString() ?: return false
    val trimmed = before.trimEnd(' ')
    return trimmed.isEmpty() && before.length < 3 ||
        trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END && before.endsWith(" ") ||
        before.endsWith("\n")
}

// Long-press popups and the mini row zoom.
package com.brownllama.continuum.keyboard

import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import kotlin.math.max

internal fun KeyboardView.restartZoomGerman() {
    timer.removeCallbacks(zoomGerman)
    val z = zoom ?: return
    if (!z.numbers && z.row == 1 && z.german == null && layout.miniOrder[z.slot] in GERMAN) {
        timer.postDelayed(zoomGerman, GERMAN_WAIT_MS)
    }
}

/** Left edge and cell width of the long-press popup for a multi-character hint. */
internal fun KeyboardView.altPopup(k: Key, options: String = k.hint!!): Pair<Float, Float> {
    val n = options.length
    val cellW = max(k.rect.width(), dpu(40f))
    val left = (k.rect.centerX() - n * cellW / 2).coerceIn(0f, width - n * cellW)
    return left to cellW
}

/** Mini number mode: holding the symbol strip or the digit row opens the zoom, as with letters. */
internal fun KeyboardView.zoomsInNumbers(k: Key?) =
    mode == Mode.MINI && panel != Panel.LETTERS && !panel.big &&
        k != null && (k.style == Style.STRIP || k.style == Style.BIG) && !isIconSlot(k.label)

/** Mini number mode: which digit-row key (0..12) is under x. */
internal fun KeyboardView.digitAt(x: Float) =
    ((x - letterRect.left) / letterRect.width() * Layouts.MINI_NUMBER_ROW.size).toInt()
        .coerceIn(0, Layouts.MINI_NUMBER_ROW.lastIndex)

/** Opens the zoom around the finger, on the row it is on (strip = 0, letters or digits = 1). */
internal fun KeyboardView.startZoom(p: Pointer) {
    val slot = nearestSlot(p.downX)
    val numbers = mode == Mode.MINI && panel != Panel.LETTERS
    zoom = Zoom((slot - 2).coerceIn(0, 21), slot, if (p.downY < letterRect.top) 0 else 1, numbers, digitAt(p.downX))
    p.zooming = true
    restartZoomGerman()
}

/** The finger picks the slot under it; the magnified window scrolls when it reaches an edge. */
internal fun KeyboardView.updateZoom(p: Pointer) {
    val z = zoom ?: return
    val slot = nearestSlot(p.x)
    if (slot < z.start) z.start = slot
    if (slot > z.start + 4) z.start = slot - 4
    val row = if (p.y < letterRect.top) 0 else 1
    val digit = digitAt(p.x)
    if (slot != z.slot || row != z.row || z.numbers && row == 1 && digit != z.digit) {
        // Sliding up from the letter to its offered German form keeps the offer; moving to
        // another letter takes it back (and that letter may offer its own after a rest).
        if (slot != z.germanSlot) z.german = null
        z.slot = slot
        z.row = row
        z.digit = digit
        restartZoomGerman()
    }
}

/** The finger lifted during the zoom: type the selected symbol, or an exact letter tap. */
internal fun KeyboardView.commitZoom() {
    val z = zoom ?: return
    zoom = null
    timer.removeCallbacks(zoomGerman)
    val german = z.german
    if (german != null && z.row == 0 && z.slot == z.germanSlot) {
        listener.onText(german)
    } else if (z.numbers) {
        text(if (z.row == 0) Layouts.MINI_NUMBER_STRIP[z.slot] else Layouts.MINI_NUMBER_ROW[z.digit])
    } else if (z.row == 0) {
        text(Layouts.MINI_STRIP[z.slot])
    } else {
        val letter = layout.miniOrder[z.slot]
        listener.onLetterTap(Tap(z.slot.toFloat(), 0f, exact = letter - 'a'))
    }
}

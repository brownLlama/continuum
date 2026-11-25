// Selecting text in the cursor mode: double tap, double tap and slide, triple tap; then copy or cut by choosing a swarm of fireflies.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where selecting has got to: [SELECTING] while fingers are making the selection, [CHOOSING] once it is made and the fireflies wait to be picked. */
internal enum class Selection { NONE, SELECTING, CHOOSING }

/** Selecting has begun (a double or triple tap selected something): the fireflies turn amber. */
internal fun KeyboardView.beginSelecting() {
    selection = Selection.SELECTING
    fireflies.amber = true
    fireflies.endChoice()
    timer.removeCallbacks(chooseTick)
    keyVibration.tap()
    postInvalidateOnAnimation()
}

/** Chooses are offered a moment after the last finger lifted, so a slide that stretches the selection is not interrupted. */
internal fun KeyboardView.scheduleChoosing() {
    timer.removeCallbacks(chooseTick)
    timer.postDelayed(chooseTick, CHOOSE_DELAY_MS)
}

/**
 * The selection is made: the swarm splits in two, one half circling a spot for copy and the other a spot for
 * cut, left and right of where the finger last was (near the thumb, and inside the screen, well apart).
 */
internal fun KeyboardView.startChoosing() {
    if (selection != Selection.SELECTING || pointers.values.any { it.area == Area.CURSOR }) return
    val fx = if (cursorFingerX >= 0f) cursorFingerX else width / 2f
    val fy = (if (cursorFingerY >= 0f) cursorFingerY else keyboardHeight / 2f).coerceIn(dpu(64f), max(dpu(64f), keyboardHeight - dpu(40f)))
    val gap = dpu(SPOT_GAP_DP)
    val edge = dpu(SPOT_EDGE_DP)
    var left = fx - gap
    var right = fx + gap
    if (left < edge) { left = edge; right = max(right, left + 2 * gap) }
    if (right > width - edge) { right = width - edge; left = min(left, right - 2 * gap) }
    left = max(left, edge)
    spotCopyX = left
    spotCutX = right
    spotY = fy
    fireflies.choose(left, fy, right, fy)
    selection = Selection.CHOOSING
    keyVibration.tap()
    timer.removeCallbacks(cursorTimeout)
    timer.postDelayed(cursorTimeout, CURSOR_MODE_TIMEOUT_MS)
    postInvalidateOnAnimation()
}

/** 1 if (x, y) is on the copy spot, 2 if on the cut spot, 0 if on neither. */
internal fun KeyboardView.buttonAt(x: Float, y: Float): Int {
    val r = dpu(SPOT_TOUCH_DP)
    return when {
        hypot(x - spotCopyX, y - spotY) <= r -> 1
        hypot(x - spotCutX, y - spotY) <= r -> 2
        else -> 0
    }
}

/** Selecting is over without an action (a tap elsewhere, or the mode ended): the swarm is one again; the selection is dropped if [collapse]. */
internal fun KeyboardView.endSelection(collapse: Boolean) {
    tapCount = 0
    lastTapUpAt = 0L
    timer.removeCallbacks(chooseTick)
    if (selection == Selection.NONE) return
    selection = Selection.NONE
    fireflies.amber = false
    fireflies.endChoice()
    listener.onSelectionEnd(collapse)
    invalidate()
}

/** A spot was tapped: the selection is copied or cut, and the swarm is one again. */
internal fun KeyboardView.performSelectionButton(cut: Boolean) {
    listener.onSelectionAction(cut)
    selection = Selection.NONE
    tapCount = 0
    lastTapUpAt = 0L
    fireflies.amber = false
    fireflies.endChoice()
    keyVibration.tap()
    invalidate()
}

/** The two choices: a pixel icon and a label over each swarm of fireflies (copy: two sheets; cut: scissors that snip). */
internal fun KeyboardView.drawSelectionChoices(c: Canvas) {
    if (selection != Selection.CHOOSING) return
    val now = SystemClock.uptimeMillis()
    val u = spritePx
    val iconTop = spotY - 5 * u
    // Copy: two sheets of paper, the front one over the back.
    run {
        val x0 = spotCopyX - 4.5f * u
        fill.color = COPY_ICON
        outline(c, x0 + 3 * u, iconTop, 6, 6, u)
        fill.color = ColorUtils.setAlphaComponent(NIGHT, 255)
        c.drawRect(x0, iconTop + 3 * u, x0 + 6 * u, iconTop + 9 * u, fill)
        fill.color = COPY_ICON
        outline(c, x0, iconTop + 3 * u, 6, 6, u)
    }
    // Cut: scissors, open for a moment and closed for a moment.
    val open = (now / SNIP_MS) % 3L != 0L
    fill.color = CUT_ICON
    pixels(c, if (open) SCISSORS_OPEN else SCISSORS_CLOSED, spotCutX - 4.5f * u, iconTop, u)
    val size = dpu(12f)
    drawText(c, resources.getString(android.R.string.copy), spotCopyX, spotY + 7 * u, size, COPY_ICON, regular)
    drawText(c, resources.getString(android.R.string.cut), spotCutX, spotY + 7 * u, size, CUT_ICON, regular)
}

/** A rectangle outline [w] × [h] sprite pixels, one pixel thick, with its top left at ([x], [y]). */
private fun KeyboardView.outline(c: Canvas, x: Float, y: Float, w: Int, h: Int, u: Float) {
    c.drawRect(x, y, x + w * u, y + u, fill)
    c.drawRect(x, y + (h - 1) * u, x + w * u, y + h * u, fill)
    c.drawRect(x, y, x + u, y + h * u, fill)
    c.drawRect(x + (w - 1) * u, y, x + w * u, y + h * u, fill)
}

/** Pixel art: a rectangle for each '#', with the top left at ([x], [y]), [u] px a pixel. */
private fun KeyboardView.pixels(c: Canvas, rows: Array<String>, x: Float, y: Float, u: Float) {
    for ((r, row) in rows.withIndex()) {
        var col = 0
        while (col < row.length) {
            if (row[col] != '#') { col++; continue }
            var end = col
            while (end < row.length && row[end] == '#') end++
            c.drawRect(x + col * u, y + r * u, x + end * u, y + (r + 1) * u, fill)
            col = end
        }
    }
}

private val SCISSORS_OPEN = arrayOf(
    "#.......#",
    ".#.....#.",
    "..#...#..",
    "...#.#...",
    "....#....",
    "...#.#...",
    "..##.##..",
    "..#...#..",
    "..##.##..",
)
private val SCISSORS_CLOSED = arrayOf(
    "....#....",
    "....#....",
    "....#....",
    "....#....",
    "....#....",
    "...#.#...",
    "..##.##..",
    "..#...#..",
    "..##.##..",
)

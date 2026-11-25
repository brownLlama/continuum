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
    // The selection becomes a constellation: one star for about every three characters, in a pattern of its own.
    val length = listener.selectionLength()
    choiceStars = (length / 3 + 4).coerceIn(4, 9)
    choiceSeed = length * 7 + 3
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

/**
 * The two choices are constellations made from the selection (as many stars as it is long, up to nine, in a
 * pattern of their own), with the fireflies circling each. Copy is a pair of twins: the same constellation
 * twice, in pale blue, with an "=" between them. Cut is the same constellation split along a diagonal into
 * two halves drifting apart, in red-orange, with a dotted slash through the gap; every couple of seconds a
 * shooting star streaks along the slash.
 */
internal fun KeyboardView.drawSelectionChoices(c: Canvas) {
    if (selection != Selection.CHOOSING) return
    val now = SystemClock.uptimeMillis()
    val u = spritePx
    val top = spotY - CONSTELLATION_H * u / 2f - u
    // Copy: the twins.
    val twinsLeft = spotCopyX - (2 * CONSTELLATION_W + TWIN_GAP) * u / 2f
    constellation(c, twinsLeft, top, COPY_ICON, now, cut = false, shift = 0)
    constellation(c, twinsLeft + (CONSTELLATION_W + TWIN_GAP) * u, top, COPY_ICON, now, cut = false, shift = 0)
    fill.color = ColorUtils.setAlphaComponent(COPY_ICON, 230)
    val eqX = twinsLeft + (CONSTELLATION_W + TWIN_GAP / 2f - 1) * u
    c.drawRect(eqX, spotY - 2 * u, eqX + 2 * u, spotY - u, fill)
    c.drawRect(eqX, spotY, eqX + 2 * u, spotY + u, fill)
    // Cut: the one constellation, cut in two.
    constellation(c, spotCutX - CONSTELLATION_W * u / 2f, top, CUT_ICON, now, cut = true, shift = 2)
    val size = dpu(12f)
    drawText(c, resources.getString(android.R.string.copy), spotCopyX, spotY + (CONSTELLATION_H / 2f + 3) * u, size, COPY_ICON, regular)
    drawText(c, resources.getString(android.R.string.cut), spotCutX, spotY + (CONSTELLATION_H / 2f + 3) * u, size, CUT_ICON, regular)
}

/**
 * One constellation, [CONSTELLATION_W] × [CONSTELLATION_H] sprite pixels with its top left at ([left], [top]): the
 * stars of [choiceStars] and [choiceSeed], each a bright pixel with a cross of light that twinkles, joined in
 * order by one-pixel lines. If [cut], a diagonal slash from the bottom left to the top right splits it: the
 * stars and lines on each side stay on their side (no line crosses it) and the two halves are [shift] pixels
 * apart, a dotted slash between them, and a shooting star runs along it now and then.
 */
private fun KeyboardView.constellation(c: Canvas, left: Float, top: Float, color: Int, now: Long, cut: Boolean, shift: Int) {
    val u = spritePx
    val w = CONSTELLATION_W
    val h = CONSTELLATION_H
    val n = choiceStars
    fun starX(i: Int) = (((i + 0.1f + 0.75f * GrassField.noise(choiceSeed + i, 401)) * (w - 1)) / n).toInt().coerceIn(0, w - 1)
    fun starY(i: Int) = (GrassField.noise(choiceSeed + i, 402) * (h - 1)).toInt()
    // Which side of the slash a cell is on: -1 above and left of it, 1 below and right of it.
    fun side(x: Int, y: Int) = if (x * h + y * w < w * h) -1 else 1
    fun dx(s: Int) = if (cut) s * shift * u else 0f
    fun dy(s: Int) = if (cut) s * shift * u * 0.5f else 0f
    // The lines between one star and the next, a pixel at a time (not across the slash).
    fill.color = ColorUtils.setAlphaComponent(color, 110)
    for (i in 0 until n - 1) {
        var x0 = starX(i); var y0 = starY(i)
        val x1 = starX(i + 1); val y1 = starY(i + 1)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        val ddx = kotlin.math.abs(x1 - x0)
        val ddy = -kotlin.math.abs(y1 - y0)
        var err = ddx + ddy
        val s0 = side(x0, y0)
        while (true) {
            if (!cut || side(x0, y0) == s0 && side(x1, y1) == s0) {
                val s = if (cut) side(x0, y0) else 0
                val px = left + x0 * u + dx(s)
                val py = top + y0 * u + dy(s)
                c.drawRect(px, py, px + u, py + u, fill)
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= ddy) { err += ddy; x0 += sx }
            if (e2 <= ddx) { err += ddx; y0 += sy }
        }
    }
    // The stars, each twinkling at its own pace.
    for (i in 0 until n) {
        val s = if (cut) side(starX(i), starY(i)) else 0
        val px = left + starX(i) * u + dx(s)
        val py = top + starY(i) * u + dy(s)
        val tw = kotlin.math.sin(now / 1000.0 * (2.0 + (i % 3)) + i * 1.7).let { 0.5 + 0.5 * it * it }
        fill.color = ColorUtils.setAlphaComponent(color, (80 + 90 * tw).toInt())
        c.drawRect(px - u, py, px, py + u, fill)
        c.drawRect(px + u, py, px + 2 * u, py + u, fill)
        c.drawRect(px, py - u, px + u, py, fill)
        c.drawRect(px, py + u, px + u, py + 2 * u, fill)
        fill.color = ColorUtils.setAlphaComponent(0xFFFFFF, (160 + 95 * tw).toInt())
        c.drawRect(px, py, px + u, py + u, fill)
    }
    if (!cut) return
    // The slash: dots along the diagonal from the bottom left to the top right, and now and then a shooting star.
    val steps = maxOf(w, h)
    fill.color = ColorUtils.setAlphaComponent(color, 190)
    for (k in 0..steps step 2) {
        val x = k * (w - 1) / steps
        val y = (h - 1) - k * (h - 1) / steps
        c.drawRect(left + x * u, top + y * u, left + (x + 1) * u, top + (y + 1) * u, fill)
    }
    val t = (now % SHOOT_PERIOD_MS).toFloat() / SHOOT_MS
    if (t < 1f) {
        for (tail in 0..5) {
            val p = (t - tail * 0.05f).coerceIn(0f, 1f)
            val x = (p * (w - 1)).toInt()
            val y = ((h - 1) - p * (h - 1)).toInt()
            fill.color = ColorUtils.setAlphaComponent(0xFFFFFF, 255 - tail * 42)
            c.drawRect(left + x * u, top + y * u, left + (x + 1) * u, top + (y + 1) * u, fill)
        }
    }
}

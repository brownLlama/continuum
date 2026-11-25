// Selecting text in the cursor mode: double tap, double tap and slide, triple tap; then copy or cut by choosing a glass jar for the fireflies.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where selecting has got to: [SELECTING] while fingers are making the selection, [CHOOSING] once it is made and two jars wait for the fireflies. */
internal enum class Selection { NONE, SELECTING, CHOOSING }

/** Selecting has begun (a double or triple tap selected something): the fireflies turn amber. */
internal fun KeyboardView.beginSelecting() {
    selection = Selection.SELECTING
    fireflies.amber = true
    fireflies.release()
    fireflies.amber = true
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
 * The selection is made: two jars appear, copy on the left and cut on the right of where the finger last was (near
 * the thumb, and inside the screen, well apart), their lids open, and the fireflies hover between them.
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
    fireflies.hover((left + right) / 2f, fy)
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
    fireflies.release()
    listener.onSelectionEnd(collapse)
    invalidate()
}

/**
 * A jar was tapped: the selection is copied or cut, and the fireflies stream into that jar one after another
 * (the other jar stays until they are in). Then the lid clicks shut ([JAR_SHUT_MS]) with a flash and a tick, the
 * other jar fades away, the chosen jar glows a moment and fades, and the cursor mode ends ([JAR_TOTAL_MS]).
 */
internal fun KeyboardView.performSelectionButton(cut: Boolean) {
    listener.onSelectionAction(cut)
    val now = SystemClock.uptimeMillis()
    selection = Selection.NONE
    tapCount = 0
    lastTapUpAt = 0L
    jarChosen = if (cut) 2 else 1
    jarAt = now
    val u = spritePx
    fireflies.funnel(if (cut) spotCutX else spotCopyX, jarTop() + 13 * u, cut, now)
    timer.removeCallbacks(jarClick)
    timer.removeCallbacks(jarDone)
    timer.postDelayed(jarClick, JAR_SHUT_MS)
    timer.postDelayed(jarDone, JAR_TOTAL_MS)
    keyVibration.tap()
    invalidate()
}

/** The jars' lids have clicked shut. */
internal fun KeyboardView.jarClicked() {
    keyVibration.tap()
    postInvalidateOnAnimation()
}

/** The jar has shut and faded: that was the last thing to do, so the day comes back (the cursor mode ends). */
internal fun KeyboardView.jarFinished() {
    jarChosen = 0
    fireflies.release(SystemClock.uptimeMillis())
    setCursorMode(false)
    invalidate()
}

/** Nothing of the jars is left (the mode ended). */
internal fun KeyboardView.dropJars() {
    timer.removeCallbacks(jarClick)
    timer.removeCallbacks(jarDone)
    jarChosen = 0
}

/** The top of the jars, which are centred on the height the finger lifted at. */
internal fun KeyboardView.jarTop() = spotY - JAR_ROWS * spritePx / 2f

/**
 * The two choices are glass jars, each with its name on a paper sticker stuck to the glass: copy on the left (pale blue
 * glass), cut on the right (red-orange). Their lids hover open, bobbing a little, while the fireflies wait between
 * them. When one is chosen the fireflies pour into it first and light it up from inside; once they are in, the lid
 * drops and clicks shut (a flash of sparks) and the other jar disappears; then the chosen one fades too. (The
 * fireflies themselves are drawn by [Fireflies].)
 */
internal fun KeyboardView.drawJars(c: Canvas) {
    val choosing = selection == Selection.CHOOSING
    if (!choosing && jarChosen == 0) return
    val now = SystemClock.uptimeMillis()
    val t = now - jarAt
    val u = spritePx
    val top = jarTop()
    for (j in 1..2) {
        val cut = j == 2
        val x = if (cut) spotCutX else spotCopyX
        val chosen = jarChosen == j
        var alpha = 255
        var lift = 5f * u + (kotlin.math.sin(now / 320.0 + j).toFloat() * 0.6f * u)
        var glow = 0f
        var sparks = 0f
        if (!choosing) {
            if (!chosen) {
                // The other jar stays until the fireflies are in and the lid has shut, then it goes.
                if (t > JAR_SHUT_MS) alpha = (255 * (1f - (t - JAR_SHUT_MS) / JAR_OTHER_FADE_MS.toFloat())).toInt().coerceIn(0, 255)
                lift = if (t < JAR_LID_MS) lift else 5f * u
            } else {
                lift = when {
                    t < JAR_LID_MS -> 5f * u
                    t < JAR_SHUT_MS -> 5f * u * (1f - (t - JAR_LID_MS).toFloat() / (JAR_SHUT_MS - JAR_LID_MS))
                    else -> 0f
                }
                // Lit from inside as the fireflies come in, brightest just after the click.
                glow = (t / JAR_LID_MS.toFloat()).coerceIn(0f, 1f) * 0.6f + if (t >= JAR_SHUT_MS) 0.4f else 0f
                if (t in JAR_SHUT_MS until JAR_SHUT_MS + 260) sparks = (t - JAR_SHUT_MS) / 260f
                if (t > JAR_FADE_MS) alpha = (255 * (1f - (t - JAR_FADE_MS).toFloat() / (JAR_TOTAL_MS - JAR_FADE_MS))).toInt().coerceIn(0, 255)
            }
        }
        if (alpha <= 0) continue
        drawJar(c, x, top, cut, resources.getString(if (cut) android.R.string.cut else android.R.string.copy), lift, glow, sparks, alpha)
    }
    // Keeps going while the jars move: they bob while they wait, and the click is quick.
    if (animating && !choosing && jarChosen != 0) postInvalidateOnAnimation()
}

/** One jar with its lid [lift] px up, its glass lit by [glow] (0..1), [sparks] (0..1, 0: none) flying off the lid, all at [alpha]. */
private fun KeyboardView.drawJar(c: Canvas, cx: Float, top: Float, cut: Boolean, label: String, lift: Float, glow: Float, sparks: Float, alpha: Int) {
    val u = spritePx
    val left = cx - JAR_W * u / 2f
    val accent = if (cut) CUT_ICON else COPY_ICON
    fun fillColor(argb: Int, a: Int = 255) { fill.color = ColorUtils.setAlphaComponent(argb, (a * alpha / 255).coerceIn(0, 255)) }
    // The glass: a tint of the jar's colour, brighter when it is lit from inside, with a pale highlight down its left.
    for (r in 3 until JAR_ROWS) {
        val row = JAR[r]
        var col = 0
        while (col < JAR_W) {
            val ch = row[col]
            if (ch == '.') { col++; continue }
            var end = col
            while (end < JAR_W && row[end] == ch) end++
            when (ch) {
                '#' -> fillColor(0xFFFFFF, 190)
                'g' -> fillColor(accent, (50 + 120 * glow).toInt())
                'h' -> fillColor(0xFFFFFF, 120)
            }
            c.drawRect(left + col * u, top + r * u, left + end * u, top + (r + 1) * u, fill)
            col = end
        }
    }
    // The lid: a metal band lifted [lift] px above the neck while it is open.
    val lidTop = top - lift
    for (r in 0 until 3) {
        val row = JAR[r]
        for (col in 0 until JAR_W) {
            val ch = row[col]
            if (ch == '.') continue
            fillColor(if (ch == 'l') LID_DARK else LID_LIGHT)
            c.drawRect(left + col * u, lidTop + r * u, left + (col + 1) * u, lidTop + (r + 1) * u, fill)
        }
    }
    // A flash of sparks off the lid at the click.
    if (sparks > 0f) {
        fillColor(0xFFFFFF, (255 * (1f - sparks)).toInt())
        val d = (2 + 4 * sparks) * u
        val mx = cx
        val my = lidTop + u
        c.drawRect(mx - d - u, my, mx - d, my + u, fill)
        c.drawRect(mx + d, my, mx + d + u, my + u, fill)
        c.drawRect(mx, my - d, mx + u, my - d + u, fill)
        c.drawRect(mx - d, my - d * 0.7f, mx - d + u, my - d * 0.7f + u, fill)
        c.drawRect(mx + d, my - d * 0.7f, mx + d + u, my - d * 0.7f + u, fill)
    }
    // The label: a paper sticker on the glass, with the name on it and a stripe of the jar's colour along its foot.
    val stickerLeft = left + STICKER_COL * u
    val stickerTop = top + STICKER_ROW * u
    fillColor(TAG_PAPER)
    c.drawRect(stickerLeft, stickerTop, stickerLeft + STICKER_W * u, stickerTop + STICKER_H * u, fill)
    fillColor(accent)
    c.drawRect(stickerLeft, stickerTop + (STICKER_H - 1) * u, stickerLeft + STICKER_W * u, stickerTop + STICKER_H * u, fill)
    drawText(c, label, cx, stickerTop + (STICKER_H - 1) * u / 2f, dpu(11f), ColorUtils.setAlphaComponent(TAG_INK, alpha), medium)
}

/**
 * The jar, [JAR_W] sprite pixels wide and [JAR_ROWS] high: the lid (rows 0-2: 'L' light, 'l' dark), then the neck
 * and the glass ('#' its edge, 'g' inside, 'h' a highlight down its left).
 */
private val JAR: Array<String> = run {
    val w = JAR_W
    fun centred(core: String) = core.padStart((w + core.length) / 2, '.').padEnd(w, '.')
    val rows = ArrayList<String>()
    val lid = 12
    rows += centred("L".repeat(lid))
    rows += centred("Ll".repeat(lid / 2))
    rows += centred("L".repeat(lid))
    rows += centred("#" + ".".repeat(lid / 2) + "#")
    rows += centred("#" + "g".repeat(w - 4) + "#")
    for (r in 5 until JAR_ROWS - 2) rows += "." + "#" + (if (r in 6..JAR_ROWS - 5) "h" + "g".repeat(w - 5) else "g".repeat(w - 4)) + "#" + "."
    rows += centred("#" + "g".repeat(w - 4) + "#")
    rows += centred("#".repeat(w - 4))
    rows.toTypedArray()
}

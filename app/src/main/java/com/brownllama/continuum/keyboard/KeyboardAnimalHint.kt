// The animal visits and the language hint on the space bar.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Language
import kotlin.math.abs
import kotlin.math.max

/** When the next animal visit is due (uptime ms; 0: the first time the keyboard opens). Kept while the app runs. */
internal var nextAnimalAt = 0L

/** Checks for a visit after a pause in typing of [ANIMAL_IDLE_MS] (or after [delay]). */
internal fun KeyboardView.scheduleAnimal(delay: Long = ANIMAL_IDLE_MS) {
    timer.removeCallbacks(animalCheck)
    if (animals.isNotEmpty() && isAttachedToWindow && windowVisibility == View.VISIBLE) timer.postDelayed(animalCheck, delay)
}

/** Ends a visit at once (the keyboard changed under it); [cancel] also drops the next check. */
internal fun KeyboardView.endAnimal(cancel: Boolean = false) {
    if (cancel) timer.removeCallbacks(animalCheck)
    if (animal.visiting) {
        animal.end()
        invalidate()
    }
}

/**
 * Starts a visit if one is due ([nextAnimalAt]) and the letters are showing in a pause in typing
 * (no finger down, no zoom, no GIF search, and the IME agrees).
 */
internal fun KeyboardView.maybeStartAnimal() {
    if (animals.isEmpty() || !animating || animal.visiting) return
    val now = SystemClock.uptimeMillis()
    // "Every time" and this opening's visit is over: nothing to wait for until the keyboard opens again.
    if (nextAnimalAt == Long.MAX_VALUE) return
    if (now < nextAnimalAt) return scheduleAnimal(max(ANIMAL_IDLE_MS, nextAnimalAt - now))
    val idle = pointers.isEmpty() && zoom == null && !gifSearching && panel == Panel.LETTERS &&
        now - lastTouchAt >= ANIMAL_IDLE_MS && listener.animalMayVisit()
    if (!idle) return scheduleAnimal()
    // They walk along the ground line under the keys, in the grass when there is some (with none, along the bottom of the keys).
    val feet = keyboardHeight + dpu(grassDp)
    animal.start(animals.random(), width.toFloat(), feet, now)
    // "Every time": the next one comes when the keyboard opens again.
    nextAnimalAt = animalFrequency.gapMs?.let { now + randomBetween(it) } ?: Long.MAX_VALUE
    invalidate()
}

internal fun KeyboardView.randomBetween(range: LongRange) = range.first + kotlin.random.Random.nextLong(range.last - range.first)

/** The label's colour: the accent while the hint shows, else the quiet one. */
internal fun KeyboardView.languageLabelColor() = if (languageHintShowing) theme.accent else theme.dim

internal fun KeyboardView.drawLanguageHint(c: Canvas) {
    val to = langTo ?: return
    val from = langFrom ?: to
    val elapsed = SystemClock.uptimeMillis() - langHintAt
    if (elapsed >= LANG_HINT_MS) {
        langTo = null
        return
    }
    val t = theme
    val moving = animating
    val grow = if (moving) (elapsed / LANG_IN_MS.toFloat()).coerceIn(0f, 1f) else 1f
    val fadeOut = if (moving) ((elapsed - (LANG_HINT_MS - LANG_OUT_MS)) / LANG_OUT_MS.toFloat()).coerceIn(0f, 1f) else 0f
    val slide = if (moving) ((elapsed - LANG_SLIDE_DELAY_MS) / LANG_SLIDE_MS.toFloat()).coerceIn(0f, 1f) else 1f
    val easedSlide = 1f - (1f - slide) * (1f - slide)
    val alpha = (grow * (1f - fadeOut)).coerceIn(0f, 1f)
    val langs = Language.entries
    val chipW = dpu(46f)
    val chipH = dpu(28f)
    val pad = dpu(4f)
    val nameSize = chipH * 0.5f
    text.typeface = regular
    text.textSize = nameSize
    val nameW = text.measureText(to.label)
    val gap = dpu(12f)
    val w = pad * 2 + chipW * langs.size + gap + nameW + gap
    val h = chipH + pad * 2
    val cx = width / 2f
    val cy = barH / 2f
    val left = cx - w / 2
    val top = cy - h / 2
    val save = c.saveLayerAlpha(left - dpu(8f), top - dpu(8f), left + w + dpu(8f), top + h + dpu(8f), (255 * alpha).toInt())
    // Grows in from a little smaller, easing out, round its centre.
    val scale = 0.86f + 0.14f * (1f - (1f - grow) * (1f - grow))
    c.scale(scale, scale, cx, cy)
    tmp.set(left, top, left + w, top + h)
    shadowOn()
    fill.color = t.zoom
    c.drawRoundRect(tmp, h / 2, h / 2, fill)
    shadowOff()
    // The highlight slides from the old language's chip to the new one's.
    val at = langs.indexOf(from) + (langs.indexOf(to) - langs.indexOf(from)) * easedSlide
    tmp.set(left + pad + at * chipW, top + pad, left + pad + (at + 1) * chipW, top + pad + chipH)
    fill.color = t.accent
    c.drawRoundRect(tmp, chipH / 2, chipH / 2, fill)
    for ((i, l) in langs.withIndex()) {
        val near = (1f - abs(i - at)).coerceIn(0f, 1f)
        drawText(c, l.code.uppercase(), left + pad + (i + 0.5f) * chipW, top + h / 2, chipH * 0.46f,
            ColorUtils.blendARGB(t.text, t.onAccent, near), regular)
    }
    drawText(c, to.label, left + pad + chipW * langs.size + gap + nameW / 2, top + h / 2, nameSize, t.text, regular)
    c.restoreToCount(save)
    // Keeps redrawing while it moves; with animations off, once more when it is over.
    if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(LANG_HINT_MS - elapsed + 16)
}

internal fun KeyboardView.drawAnimal(c: Canvas) {
    if (!animal.visiting) return
    val next = animal.draw(c, width.toFloat(), SystemClock.uptimeMillis())
    // Redraw only when it next moves a pixel or changes frame, not every display frame.
    if (next >= 0) postInvalidateDelayed(next) else scheduleAnimal()
}

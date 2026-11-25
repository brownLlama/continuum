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

/** When the animal next sets off for a walk (uptime ms). Kept while the app runs. */
internal var nextWanderAt = 0L

/** Checks on the animal (puts it on the grass, or sends it for a walk when it is due) after [delay]. */
internal fun KeyboardView.scheduleAnimal(delay: Long = ANIMAL_IDLE_MS) {
    timer.removeCallbacks(animalCheck)
    if (animals.isNotEmpty() && isAttachedToWindow && windowVisibility == View.VISIBLE) timer.postDelayed(animalCheck, delay)
}

/** Takes the animal off the grass at once (the keyboard changed under it); [cancel] also drops the next check. */
internal fun KeyboardView.endAnimal(cancel: Boolean = false) {
    if (cancel) timer.removeCallbacks(animalCheck)
    if (animal.visiting) {
        animal.end()
        invalidate()
    }
}

/**
 * The animal lives on the grass while the keyboard shows the letters or numbers: it is put there if
 * it is not, and when a walk is due ([nextWanderAt]) in a pause in typing (no finger down, no zoom,
 * and the IME agrees) it takes a short one. Mostly it just stands, so it costs next to nothing.
 */
internal fun KeyboardView.maybeStartAnimal() {
    if (animals.isEmpty() || !isAttachedToWindow || width <= 0) return
    val now = SystemClock.uptimeMillis()
    // No grass, no animal (it has nowhere to stand); nor over the emoji or GIF panels, or in password fields.
    if (grassHeight <= 0 || panel.big || gifSearching || !listener.animalMayVisit()) return endAnimal()
    if (!animal.visiting) {
        animal.start(animals.random(), width.toFloat(), animalFeet(), now, groundRows = animalFeetRows(), resident = true)
        nextWanderAt = now + randomBetween(animalFrequency.wanderMs)
        invalidate()
    }
    if (animal.resting && now >= nextWanderAt) {
        val idle = animating && pointers.isEmpty() && zoom == null && panel == Panel.LETTERS &&
            now - lastTouchAt >= ANIMAL_IDLE_MS
        if (idle) {
            animal.wander(width.toFloat(), now)
            nextWanderAt = now + randomBetween(animalFrequency.wanderMs)
            invalidate()
            return
        }
    }
    scheduleAnimal(max(ANIMAL_IDLE_MS, nextWanderAt - now))
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
    val now = SystemClock.uptimeMillis()
    val next = animal.draw(c, width.toFloat(), now)
    when {
        // Redraw only when it next moves a pixel or changes frame, not every display frame.
        next >= 0 -> postInvalidateDelayed(next)
        // At rest (or held by a finger, which redraws it as it moves): nothing to draw until the next walk is due.
        next == Animal.REST -> scheduleAnimal(max(ANIMAL_IDLE_MS, nextWanderAt - now))
        else -> scheduleAnimal()
    }
}

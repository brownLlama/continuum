package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import kotlin.math.max

/**
 * The keyboard's line icons (shift, ⌫, enter, the emoji tabs, ☺ …), drawn as paths so they take
 * the theme's colours and scale with the keys. Each is centred on (cx, cy) and sized by [s], about
 * half its width. [KeyboardView] decides where they go and in which colour.
 */
class KeyIcons(private val density: Float) {

    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tmp = RectF()

    private fun dpu(v: Float) = v * density
    private val iconStroke get() = dpu(1.8f)

    /** Shift arrow: outlined in [outline] when off, filled with [filled] when on, plus a bar underneath for caps lock. */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, state: KeyboardView.Shift, outline: Int, filled: Int) {
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.9f, cy)
        path.lineTo(cx + s * 0.4f, cy)
        path.lineTo(cx + s * 0.4f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.4f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.4f, cy)
        path.lineTo(cx - s * 0.9f, cy)
        path.close()
        if (state == KeyboardView.Shift.OFF) {
            stroke.color = outline
            stroke.strokeWidth = iconStroke
            c.drawPath(path, stroke)
        } else {
            fill.color = filled
            c.drawPath(path, fill)
            if (state == KeyboardView.Shift.LOCK) c.drawRect(cx - s * 0.6f, cy + s * 1.0f, cx + s * 0.6f, cy + s * 1.2f, fill)
        }
    }

    fun backspace(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx - s * 1.1f, cy)
        path.lineTo(cx - s * 0.5f, cy - s * 0.6f)
        path.lineTo(cx + s * 1.0f, cy - s * 0.6f)
        path.lineTo(cx + s * 1.0f, cy + s * 0.6f)
        path.lineTo(cx - s * 0.5f, cy + s * 0.6f)
        path.close()
        // Outlined, like the other line icons.
        stroke.color = color
        stroke.strokeWidth = iconStroke
        c.drawPath(path, stroke)
        val x0 = cx + s * 0.25f
        val d = s * 0.28f
        c.drawLine(x0 - d, cy - d, x0 + d, cy + d, stroke)
        c.drawLine(x0 - d, cy + d, x0 + d, cy - d, stroke)
    }

    /** Enter key icon for the editor's [action]: magnifier for search, ✔ for done, → for go/next/send. */
    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, action: Int, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        when (action) {
            EditorInfo.IME_ACTION_SEARCH -> {
                val r = s * 0.6f
                c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, r, stroke)
                c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.85f, cy + s * 0.85f, stroke)
            }
            EditorInfo.IME_ACTION_DONE -> check(c, cx, cy, s, color)
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEND -> {
                c.drawLine(cx - s * 0.8f, cy, cx + s * 0.8f, cy, stroke)
                c.drawLine(cx + s * 0.8f, cy, cx + s * 0.3f, cy - s * 0.5f, stroke)
                c.drawLine(cx + s * 0.8f, cy, cx + s * 0.3f, cy + s * 0.5f, stroke)
            }
            else -> {
                path.reset()
                path.moveTo(cx + s * 0.8f, cy - s * 0.6f)
                path.lineTo(cx + s * 0.8f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.8f, cy + s * 0.25f)
                c.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - s * 0.4f, cy - s * 0.15f)
                path.lineTo(cx - s * 0.8f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.65f)
                c.drawPath(path, stroke)
            }
        }
    }

    /** The enter key's ✔ for a "done" action. */
    private fun check(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        path.reset()
        path.moveTo(cx - s * 0.8f, cy)
        path.lineTo(cx - s * 0.25f, cy + s * 0.55f)
        path.lineTo(cx + s * 0.85f, cy - s * 0.6f)
        c.drawPath(path, stroke)
    }

    /** Full mode's autocorrect toggle: an "A" with a check when [on], struck through when off. */
    fun autocorrect(c: Canvas, cx: Float, cy: Float, s: Float, on: Boolean, color: Int) {
        drawText(c, "A", cx - s * 0.35f, cy, s * 1.9f, color, medium)
        stroke.color = color
        stroke.strokeWidth = iconStroke
        if (on) {
            path.reset()
            path.moveTo(cx + s * 0.45f, cy + s * 0.35f)
            path.lineTo(cx + s * 0.75f, cy + s * 0.65f)
            path.lineTo(cx + s * 1.3f, cy + s * 0.05f)
            c.drawPath(path, stroke)
        } else {
            c.drawLine(cx - s * 1.2f, cy + s * 0.9f, cx + s * 0.9f, cy - s * 0.9f, stroke)
        }
    }

    /** The "back to letters" key in number mode: Q A Z staggered like the mini row, on a key [h] tall. */
    fun qaz(c: Canvas, cx: Float, cy: Float, h: Float, color: Int) {
        val size = h * 0.3f
        val step = size * 0.62f
        drawText(c, "Q", cx - step, cy - size * 0.28f, size * 0.85f, color, regular)
        drawText(c, "A", cx, cy, size, color, regular)
        drawText(c, "Z", cx + step, cy + size * 0.2f, size, color, regular)
    }

    /** Settings: three slider tracks with their knobs at different spots, about 2 × [s] across. */
    fun sliders(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        fill.color = color
        val knobs = floatArrayOf(0.35f, -0.3f, 0.05f)
        for (i in 0 until 3) {
            val y = cy + (i - 1) * s * 0.7f
            c.drawLine(cx - s, y, cx + s, y, stroke)
            val kx = cx + knobs[i] * s
            tmp.set(kx - s * 0.16f, y - s * 0.3f, kx + s * 0.16f, y + s * 0.3f)
            c.drawRoundRect(tmp, s * 0.1f, s * 0.1f, fill)
        }
    }

    /** Switch keyboard: a small keyboard outline with two rows of keys and a space bar. */
    fun keyboard(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        tmp.set(cx - s * 1.1f, cy - s * 0.75f, cx + s * 1.1f, cy + s * 0.75f)
        c.drawRoundRect(tmp, s * 0.2f, s * 0.2f, stroke)
        fill.color = color
        val d = s * 0.11f
        for (row in 0 until 2) for (col in 0 until 5) {
            c.drawCircle(cx + (col - 2) * s * 0.4f, cy - s * 0.38f + row * s * 0.36f, d, fill)
        }
        c.drawLine(cx - s * 0.45f, cy + s * 0.4f, cx + s * 0.45f, cy + s * 0.4f, stroke)
    }

    /** "GIF" in a rounded box: the emoji panel's GIF tab. */
    fun gifBadge(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = max(dpu(1.4f), s * 0.14f)
        tmp.set(cx - s * 1.25f, cy - s * 0.8f, cx + s * 1.25f, cy + s * 0.8f)
        c.drawRoundRect(tmp, s * 0.3f, s * 0.3f, stroke)
        drawText(c, "GIF", cx, cy, s * 0.95f, color, bold)
    }

    /** ⌄, about 2 × [s] across: hides the search keyboard. */
    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        path.reset()
        path.moveTo(cx - s, cy - s * 0.45f)
        path.lineTo(cx, cy + s * 0.55f)
        path.lineTo(cx + s, cy - s * 0.45f)
        c.drawPath(path, stroke)
    }

    /** Line icons for the emoji tabs (group [id]), about 2 × [s] across. */
    fun emojiTab(c: Canvas, id: String, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        fill.color = color
        when (id) {
            Emoji.FREQUENT -> { // clock
                c.drawCircle(cx, cy, s, stroke)
                c.drawLine(cx, cy, cx, cy - s * 0.6f, stroke)
                c.drawLine(cx, cy, cx + s * 0.45f, cy + s * 0.2f, stroke)
            }
            "smileys" -> smiley(c, cx, cy, s, color)
            "nature" -> { // leaf
                path.reset()
                path.moveTo(cx - s * 0.7f, cy + s * 0.7f)
                path.quadTo(cx - s * 0.9f, cy - s * 0.9f, cx + s * 0.9f, cy - s * 0.9f)
                path.quadTo(cx + s * 0.9f, cy + s * 0.9f, cx - s * 0.7f, cy + s * 0.7f)
                c.drawPath(path, stroke)
                c.drawLine(cx - s, cy + s, cx + s * 0.3f, cy - s * 0.3f, stroke)
            }
            "food" -> { // cup
                path.reset()
                path.moveTo(cx - s * 0.85f, cy - s * 0.2f)
                path.lineTo(cx + s * 0.55f, cy - s * 0.2f)
                path.lineTo(cx + s * 0.4f, cy + s * 0.85f)
                path.lineTo(cx - s * 0.7f, cy + s * 0.85f)
                path.close()
                c.drawPath(path, stroke)
                tmp.set(cx + s * 0.35f, cy, cx + s * 1.05f, cy + s * 0.55f)
                c.drawArc(tmp, -90f, 180f, false, stroke)
                c.drawLine(cx - s * 0.4f, cy - s * 0.95f, cx - s * 0.4f, cy - s * 0.55f, stroke)
                c.drawLine(cx + s * 0.05f, cy - s * 0.95f, cx + s * 0.05f, cy - s * 0.55f, stroke)
            }
            "activities" -> { // ball
                c.drawCircle(cx, cy, s, stroke)
                tmp.set(cx - s * 1.9f, cy - s, cx - s * 0.1f, cy + s)
                c.drawArc(tmp, -60f, 120f, false, stroke)
                tmp.set(cx + s * 0.1f, cy - s, cx + s * 1.9f, cy + s)
                c.drawArc(tmp, 120f, 120f, false, stroke)
            }
            "travel" -> { // car
                tmp.set(cx - s, cy - s * 0.15f, cx + s, cy + s * 0.5f)
                c.drawRoundRect(tmp, s * 0.2f, s * 0.2f, stroke)
                path.reset()
                path.moveTo(cx - s * 0.65f, cy - s * 0.15f)
                path.lineTo(cx - s * 0.4f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.4f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.65f, cy - s * 0.15f)
                c.drawPath(path, stroke)
                c.drawCircle(cx - s * 0.55f, cy + s * 0.6f, s * 0.22f, fill)
                c.drawCircle(cx + s * 0.55f, cy + s * 0.6f, s * 0.22f, fill)
            }
            "objects" -> { // light bulb
                c.drawCircle(cx, cy - s * 0.3f, s * 0.65f, stroke)
                c.drawLine(cx - s * 0.3f, cy + s * 0.6f, cx + s * 0.3f, cy + s * 0.6f, stroke)
                c.drawLine(cx - s * 0.2f, cy + s * 0.9f, cx + s * 0.2f, cy + s * 0.9f, stroke)
            }
            "symbols" -> drawText(c, "&%", cx, cy, s * 1.5f, color, regular)
            "flags" -> {
                c.drawLine(cx - s * 0.7f, cy - s, cx - s * 0.7f, cy + s, stroke)
                path.reset()
                path.moveTo(cx - s * 0.7f, cy - s)
                path.lineTo(cx + s * 0.9f, cy - s * 0.55f)
                path.lineTo(cx - s * 0.7f, cy - s * 0.1f)
                path.close()
                c.drawPath(path, fill)
            }
            else -> drawText(c, id.take(1).uppercase(), cx, cy, s * 1.5f, color, regular)
        }
    }

    /** A clipboard (board with a clip and two lines of text), about 2 × [s] across: opens the clipboard panel. */
    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = max(dpu(1.2f), s * 0.14f)
        tmp.set(cx - s * 0.75f, cy - s * 0.8f, cx + s * 0.75f, cy + s)
        c.drawRoundRect(tmp, s * 0.2f, s * 0.2f, stroke)
        fill.color = color
        tmp.set(cx - s * 0.35f, cy - s * 1.05f, cx + s * 0.35f, cy - s * 0.6f)
        c.drawRoundRect(tmp, s * 0.12f, s * 0.12f, fill)
        c.drawLine(cx - s * 0.4f, cy - s * 0.1f, cx + s * 0.4f, cy - s * 0.1f, stroke)
        c.drawLine(cx - s * 0.4f, cy + s * 0.4f, cx + s * 0.15f, cy + s * 0.4f, stroke)
    }

    /** A bin, about 2 × [s] across: clears the clipboard. */
    fun trash(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = iconStroke
        c.drawLine(cx - s * 0.85f, cy - s * 0.6f, cx + s * 0.85f, cy - s * 0.6f, stroke)
        c.drawLine(cx - s * 0.25f, cy - s * 0.85f, cx + s * 0.25f, cy - s * 0.85f, stroke)
        path.reset()
        path.moveTo(cx - s * 0.6f, cy - s * 0.6f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx + s * 0.45f, cy + s * 0.9f)
        path.lineTo(cx + s * 0.6f, cy - s * 0.6f)
        c.drawPath(path, stroke)
        c.drawLine(cx, cy - s * 0.25f, cx, cy + s * 0.55f, stroke)
    }

    /** Line-drawn ☺ of radius [r] (the font's emoji would be coloured), used wherever the emoji panel can be opened. */
    fun smiley(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = max(dpu(1.2f), r * 0.14f)
        c.drawCircle(cx, cy, r, stroke)
        fill.color = color
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.13f, fill)
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.13f, fill)
        tmp.set(cx - r * 0.5f, cy - r * 0.4f, cx + r * 0.5f, cy + r * 0.55f)
        c.drawArc(tmp, 20f, 140f, false, stroke)
    }

    /** Draws [s] centred on (cx, cy), vertically centred on its glyphs. */
    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, face: Typeface) {
        text.typeface = face
        text.textSize = size
        text.color = color
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }
}

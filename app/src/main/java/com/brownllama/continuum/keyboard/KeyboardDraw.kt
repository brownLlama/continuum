// Drawing: panel fades, keys, the suggestion strip and shared drawing helpers.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.brownllama.continuum.R
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Mode
import kotlin.math.min

internal fun KeyboardView.markPanelChange(ms: Long = FADE_MS, slide: Float = 0f) {
    panelChangedAt = SystemClock.uptimeMillis()
    fadeMs = ms
    fadeSlide = slide
    endAnimal()
}

/** 0..1 progress of an animation that started at [since]; keeps redrawing until it's done. */
internal fun KeyboardView.progress(since: Long, ms: Long): Float {
    if (!animating) return 1f
    val t = ((SystemClock.uptimeMillis() - since) / ms.toFloat()).coerceIn(0f, 1f)
    if (t < 1f) postInvalidateOnAnimation()
    return 1f - (1f - t) * (1f - t) // ease out
}

/** Draws a popup that fades and scales in from (px, py), starting at [since]. */
internal inline fun KeyboardView.popIn(c: Canvas, since: Long, px: Float, py: Float, draw: () -> Unit) {
    val e = progress(since, POP_MS)
    if (e >= 1f) return draw()
    val save = c.saveLayerAlpha(null, (255 * e).toInt())
    val sc = 0.86f + 0.14f * e
    c.scale(sc, sc, px, py)
    draw()
    c.restoreToCount(save)
}

/** Popups float on a soft shadow. */
internal fun KeyboardView.shadowOn() {
    fill.setShadowLayer(dpu(8f), 0f, dpu(2f), 0x40000000)
}

internal fun KeyboardView.shadowOff() = fill.clearShadowLayer()

internal fun KeyboardView.drawContent(canvas: Canvas) {
    val t = theme
    fill.color = barFill
    canvas.drawRect(0f, 0f, width.toFloat(), barH, fill)
    if (panel == Panel.EMOJI) {
        drawEmojiGrid(canvas)
        for (k in barKeys) drawKey(canvas, k, true)
        for (k in keys) drawKey(canvas, k, false)
        return
    }
    if (panel == Panel.GIF) {
        drawGifGrid(canvas)
        for (k in barKeys) drawKey(canvas, k, true)
        for (k in keys) drawKey(canvas, k, false)
        return
    }
    if (panel == Panel.CLIPBOARD) {
        drawClipboard(canvas)
        for (k in barKeys) drawKey(canvas, k, true)
        for (k in keys) drawKey(canvas, k, false)
        return
    }
    if (panel == Panel.STICKERS) {
        drawStickers(canvas)
        for (k in barKeys) drawKey(canvas, k, true)
        for (k in keys) drawKey(canvas, k, false)
        return
    }
    if (gifSearching) {
        drawGifGrid(canvas)
        for (k in headerKeys) drawKey(canvas, k, true)
    }
    if (mode == Mode.MINI) drawGlows(canvas)
    drawSuggestions(canvas)
    for (k in barKeys) drawKey(canvas, k, true)
    if (confirmForget >= 0) drawForgetConfirm(canvas)
    if (mode == Mode.MINI && panel == Panel.LETTERS) drawMiniLetters(canvas)
    for (k in keys) drawKey(canvas, k, false)
    if (mode == Mode.MINI) {
        drawText(canvas, languageCode, slotCenter(25), letterRect.bottom - miniRowH * 0.12f, miniRowH * 0.3f, languageLabelColor(), regular)
    }
    drawAnimal(canvas)
    drawGrassFront(canvas)
    drawTree(canvas, held = true)
    drawPreviews(canvas)
    drawLanguageHint(canvas)
    drawZoom(canvas)
}

internal fun KeyboardView.isPressed(k: Key) = pointers.values.any { it.key === k && !it.consumed }

/** Draws one key: its pressed background, then its label or icon. */
internal fun KeyboardView.drawKey(c: Canvas, k: Key, inBar: Boolean) {
    val t = theme
    val r = k.face
    val cx = r.centerX()
    val cy = r.centerY()
    val icon = min(r.height(), dpu(40f) * scale) * 0.34f
    // Keys that show a preview popup (or the bar's own highlight) get no pressed background.
    val previewed = (k.kind == Kind.LETTER || k.kind == Kind.TEXT) && !inBar ||
        k.kind == Kind.SUGGESTION
    if (k.style == Style.FILLED) {
        fill.color = if (isPressed(k)) t.keyPressed else t.keyFill
        tmp.set(r); tmp.inset(dpu(2.5f), dpu(2.5f))
        c.drawRoundRect(tmp, dpu(3f), dpu(3f), fill)
    } else if (isPressed(k) && !previewed && k.kind != Kind.SPACE) {
        fill.color = t.keyPressed
        tmp.set(r); tmp.inset(dpu(2f), dpu(3f))
        c.drawRoundRect(tmp, dpu(10f), dpu(10f), fill)
    }
    when (k.kind) {
        Kind.LETTER -> {
            val color = if (layout.row(k.label[0]) == 1) t.homeRow else t.text
            drawText(c, upper(k.label), cx, cy, r.height() * 0.46f, color, regular)
            k.hint?.let { drawHint(c, r, it) }
        }
        Kind.TEXT -> drawTextKey(c, k)
        Kind.SHIFT -> {
            icons.shift(c, cx, cy, icon * SMALL_ICON, shift, iconColor, t.accent)
            // "⋯": holding shift opens its menu.
            fill.color = hintColor
            val d = icon * 0.14f
            val y = cy - icon * 0.8f
            for (i in -1..1) c.drawCircle(cx + icon * 0.85f + i * d * 2.6f, y, d * 0.55f, fill)
        }
        Kind.BACKSPACE -> icons.backspace(c, cx, cy, icon * SMALL_ICON, iconColor)
        Kind.SPACE -> {
            // A slim pill with a quiet language label.
            val h = r.height() * 0.56f
            tmp.set(r.left + dpu(2.5f), cy - h / 2, r.right - dpu(2.5f), cy + h / 2)
            fill.color = if (isPressed(k)) softPressedFill else softFill
            c.drawRoundRect(tmp, h / 2, h / 2, fill)
            drawText(c, languageCode, cx, cy, h * 0.38f, languageLabelColor(), regular)
        }
        Kind.ENTER -> icons.enter(c, cx, cy, icon, if (gifSearching) EditorInfo.IME_ACTION_SEARCH else enterAction, t.text)
        Kind.SYMBOLS -> drawText(c, k.label, cx, cy, r.height() * 0.36f, t.text, regular)
        Kind.MORE -> drawText(c, k.label, cx, cy, r.height() * 0.32f, t.text, regular)
        Kind.ABC -> icons.qaz(c, cx, cy, r.height(), t.text)
        Kind.EMOJI_TAB -> {
            val active = k.value == activeEmojiSection()
            if (active) {
                tmp.set(r); tmp.inset(dpu(5f), r.height() * 0.16f)
                fill.color = softFill
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
            }
            icons.emojiTab(c, emojiSections[k.value].id, cx, cy, min(r.width(), r.height()) * 0.22f, if (active) t.text else t.dim)
        }
        Kind.CLOSE -> { // emoji / GIF panel's floating ✕, a little see-through
            val rad = r.width() / 2
            val save = c.saveLayerAlpha(r.left - rad, r.top - rad, r.right + rad, r.bottom + rad, CLOSE_ALPHA)
            fill.color = softFill
            shadowOn()
            c.drawCircle(cx, cy, rad, fill)
            shadowOff()
            stroke.color = t.text
            stroke.strokeWidth = dpu(1.6f)
            c.drawCircle(cx, cy, rad * 0.8f, stroke)
            val d = rad * 0.3f
            c.drawLine(cx - d, cy - d, cx + d, cy + d, stroke)
            c.drawLine(cx - d, cy + d, cx + d, cy - d, stroke)
            c.restoreToCount(save)
        }
        Kind.GIF_TAB -> icons.gifBadge(c, cx, cy, min(r.width(), r.height()) * 0.22f, t.dim)
        Kind.GIF_BACK -> icons.smiley(c, cx, cy, min(r.width(), r.height()) * 0.22f, t.dim)
        Kind.GIF_SEARCH -> drawGifSearchField(c, r)
        Kind.GIF_CANCEL -> icons.chevronDown(c, cx, cy, min(r.width(), r.height()) * 0.16f, t.dim)
        Kind.CLIP_CLEAR -> icons.trash(c, cx, cy, min(r.width(), r.height()) * 0.2f, if (clips.isEmpty()) hintColor else t.dim)
        Kind.STICKER_TAB -> icons.sticker(c, cx, cy, min(r.width(), r.height()) * 0.2f, t.dim)
        Kind.STICKER_BACK -> icons.smiley(c, cx, cy, min(r.width(), r.height()) * 0.22f, t.dim)
        Kind.STICKER_ADD -> icons.plus(c, cx, cy, min(r.width(), r.height()) * 0.22f, t.dim)
        Kind.AUTOCORRECT -> icons.autocorrect(c, cx, cy, barH * 0.22f, autocorrectOn, if (autocorrectOn) t.accent else t.dim)
        Kind.SUGGESTION -> {} // drawn by drawSuggestions
    }
}

/** Label of a [Kind.TEXT] key, sized by its [Style]. */
internal fun KeyboardView.drawTextKey(c: Canvas, k: Key) {
    val t = theme
    val r = k.face
    val cx = r.centerX()
    val cy = r.centerY()
    when {
        isIconSlot(k.label) -> drawSlotIcon(c, k.label, cx, cy, r.height() * 0.3f, if (k.style == Style.STRIP) t.dim else t.text)
        k.style == Style.STRIP -> drawText(c, k.label, cx, cy, r.height() * 0.66f, stripColor, regular)
        k.style == Style.BIG -> drawText(c, k.label, cx, cy - r.height() * 0.1f, r.height() * 0.48f, t.text, regular)
        k.label == "," || k.label == "." -> drawText(c, k.label, cx, cy, r.height() * 0.5f, t.text, medium)
        else -> drawText(c, k.label, cx, cy, r.height() * 0.4f, t.text, regular)
    }
    k.hint?.let { drawHint(c, r, it) }
}

/** Small long-press hint in the key's top-right corner. */
internal fun KeyboardView.drawHint(c: Canvas, r: RectF, hint: String) {
    val x = r.right - r.width() * (if (hint.length > 1) 0.3f else 0.26f)
    val y = r.top + r.height() * 0.28f
    if (hint == Layouts.EMOJI_SLOT) icons.smiley(c, x, y + r.height() * 0.03f, r.height() * 0.1f, hintColor)
    else drawText(c, hint, x, y, r.height() * 0.19f, hintColor, regular)
}

/** The scrollable suggestion strip: hairlines between words, a fade where more words continue. */
internal fun KeyboardView.drawSuggestions(c: Canvas) {
    val area = suggestionArea
    c.withClip(area) {
        for (i in 0 until slotEdges.size - 1) {
            val l = area.left + slotEdges[i] - barScroll
            val r = area.left + slotEdges[i + 1] - barScroll
            if (r < area.left || l > area.right) continue
            tmp.set(l, 0f, r, barH)
            drawSuggestion(c, suggestions.getOrNull(i).orEmpty(), i == highlightSlot, tmp)
        }
        // A short, quiet line between two neighbouring words (not next to blank slots).
        stroke.color = dividerColor
        stroke.strokeWidth = dpu(1f)
        val h = barH * 0.22f
        for (i in 1 until slotEdges.size - 1) {
            if (suggestions.getOrNull(i - 1).isNullOrEmpty() || suggestions.getOrNull(i).isNullOrEmpty()) continue
            val x = area.left + slotEdges[i] - barScroll
            if (x <= area.left || x >= area.right) continue
            c.drawLine(x, barH / 2 - h, x, barH / 2 + h, stroke)
        }
        // Fades hint that the strip scrolls further in that direction.
        val fadeW = area.width() * MIN_SLOT_FRACTION * 0.5f
        if (barScroll < maxBarScroll - 1f) drawFade(c, area.right - fadeW, area.right, toRight = true)
        if (barScroll > 1f) drawFade(c, area.left, area.left + fadeW, toRight = false)
    }
}

/** Covers the bar with: “word”  Remove from suggestions?  [Remove] [Cancel]. */
internal fun KeyboardView.drawForgetConfirm(c: Canvas) {
    val t = theme
    val w = width.toFloat()
    fill.color = barFill
    c.drawRect(0f, 0f, w, barH, fill)
    val word = suggestions.getOrNull(confirmForget).orEmpty()
    text.textAlign = Paint.Align.LEFT
    text.typeface = regular
    var size = barH * 0.38f
    val label = resources.getString(R.string.forget_question, word)
    text.textSize = size
    val maxW = removeRect.left - dpu(16f)
    val lw = text.measureText(label)
    if (lw > maxW) size *= maxW / lw
    text.textSize = size
    text.color = t.text
    val fm = text.fontMetrics
    c.drawText(label, dpu(10f), barH / 2 - (fm.ascent + fm.descent) / 2, text)
    text.textAlign = Paint.Align.CENTER
    fill.color = t.accent
    tmp.set(removeRect); tmp.inset(dpu(4f), barH * 0.16f)
    c.drawRoundRect(tmp, dpu(4f), dpu(4f), fill)
    drawText(c, resources.getString(R.string.forget_remove), tmp.centerX(), tmp.centerY(), barH * 0.36f, t.onAccent, bold)
    drawText(c, resources.getString(R.string.forget_cancel), cancelRect.centerX(), cancelRect.centerY(), barH * 0.36f, t.text, regular)
}

internal fun KeyboardView.drawFade(c: Canvas, l: Float, r: Float, toRight: Boolean) {
    val solid = barFill
    val i = if (toRight) 1 else 0
    val cached = fades[i]
    fill.shader = if (cached != null && fadeL[i] == l && fadeR[i] == r && fadeColor[i] == solid) cached else {
        val clear = ColorUtils.setAlphaComponent(solid, 0)
        LinearGradient(l, 0f, r, 0f, if (toRight) clear else solid, if (toRight) solid else clear, Shader.TileMode.CLAMP)
            .also { fades[i] = it; fadeL[i] = l; fadeR[i] = r; fadeColor[i] = solid }
    }
    c.drawRect(l, 0f, r, barH, fill)
    fill.shader = null
}

/** One suggestion, shrunk only if it is wider than the whole bar; the default pick sits on a pill. */
internal fun KeyboardView.drawSuggestion(c: Canvas, s: String, highlighted: Boolean, r: RectF) {
    if (s.isEmpty()) return
    val t = theme
    var size = suggestionTextSize
    text.typeface = regular
    text.textSize = size
    val maxW = r.width() - suggestionPad * 2
    val w = text.measureText(s)
    if (w > maxW) size *= maxW / w
    // The pick sits in a soft pill and alternatives are dimmer, so the eye lands on it.
    if (highlighted) {
        text.textSize = size
        val pw = min(r.width() - dpu(14f), text.measureText(s) + barH * 0.9f)
        val ph = barH * 0.62f
        tmp.set(r.centerX() - pw / 2, r.centerY() - ph / 2, r.centerX() + pw / 2, r.centerY() + ph / 2)
        fill.color = softFill
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill)
    }
    val color = if (highlighted || highlightSlot < 0) t.text else ColorUtils.blendARGB(t.text, t.background, 0.35f)
    drawText(c, s, r.centerX(), r.centerY(), size, color, regular)
}

/** Strip entries drawn as icons rather than text: ☺ (emoji panel) and the clipboard. */
internal fun KeyboardView.isIconSlot(s: String) = s == Layouts.EMOJI_SLOT || s == Layouts.CLIPBOARD_SLOT

internal fun KeyboardView.drawSlotIcon(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
    if (s == Layouts.CLIPBOARD_SLOT) icons.clipboard(c, cx, cy, size, color) else icons.smiley(c, cx, cy, size, color)
}

/** Draws [s] centred on (cx, cy), vertically centred on its glyphs. */
internal fun KeyboardView.drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, face: Typeface) {
    text.typeface = face
    text.textSize = size
    text.color = color
    val fm = text.fontMetrics
    c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
}

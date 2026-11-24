// Drawing: the finger glow, key previews, the zoom and the shift menu.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Light under touches on mini mode's row ("designed for big fingers"), as a smooth column:
 * brightest over the finger, falling off over about two letters to
 * each side, across the symbol strip and the letters.
 */
internal fun KeyboardView.drawGlows(c: Canvas) {
    if (glows.isEmpty()) return
    val now = SystemClock.uptimeMillis()
    val half = slotW * GLOW_HALF_SLOTS
    val top = stripRect.top
    val bottom = letterRect.bottom
    val it = glows.iterator()
    while (it.hasNext()) {
        val g = it.next()
        val p = g.pointer
        val held = pointers[p.id] === p
        if (held) g.x = p.x
        if (!held && g.releasedAt == 0L) g.releasedAt = now
        // The zoom takes over while it's open.
        if (held && p.zooming) continue
        val fadeIn = progress(p.downAt, GLOW_IN_MS)
        val fadeOut = if (g.releasedAt == 0L) 0f else progress(g.releasedAt, GLOW_OUT_MS)
        if (fadeOut >= 1f) {
            it.remove()
            continue
        }
        fill.shader = glowShader(half)
        fill.alpha = (GLOW_ALPHA * fadeIn * (1f - fadeOut)).roundToInt()
        c.withTranslation(g.x, 0f) { c.drawRect(-half, top, half, bottom, fill) }
        fill.shader = null
        fill.alpha = 255
    }
    if (glows.isNotEmpty() && animating) postInvalidateOnAnimation()
}

/**
 * The glow's bell curve in the theme's text colour, centred on x = 0 (drawn translated to each
 * finger; the paint's alpha fades it). Built once per colour and width rather than every frame.
 */
internal fun KeyboardView.glowShader(half: Float): Shader {
    val color = theme.text
    glowCache?.takeIf { glowColor == color && glowHalf == half }?.let { return it }
    val colors = IntArray(GLOW_STOPS.size) { i -> ColorUtils.setAlphaComponent(color, (255 * GLOW_STOPS[i]).roundToInt()) }
    return LinearGradient(-half, 0f, half, 0f, colors, GLOW_POSITIONS, Shader.TileMode.CLAMP).also {
        glowCache = it
        glowColor = color
        glowHalf = half
    }
}

/** Mini mode's symbol strip and staggered letter row (drawn directly, they aren't [Key]s). */
internal fun KeyboardView.drawMiniLetters(c: Canvas) {
    val t = theme
    // Symbol strip: one character above each letter slot.
    val sy = stripRect.centerY()
    for (i in 0 until 26) {
        val x = slotCenter(i)
        val s = Layouts.MINI_STRIP[i]
        if (isIconSlot(s)) drawSlotIcon(c, s, x, sy, stripH * 0.32f, stripColor)
        else drawText(c, s, x, sy, stripH * 0.66f, stripColor, regular)
    }
    // Letters, staggered by their keyboard row (top row raised, bottom row lowered).
    val size = miniRowH * 0.5f
    for (i in 0 until 26) {
        val ch = layout.miniOrder[i]
        val row = layout.row(ch)
        val y = letterRect.top + miniRowH * when (row) { 0 -> 0.26f; 1 -> 0.44f; else -> 0.62f }
        drawText(c, upper(ch.toString()), slotCenter(i), y, size, if (row == 1) t.homeRow else t.text, regular)
    }
}

/** Popups above pressed keys: an enlarged key, or the pick-one popup of a long-pressed hint. */
internal fun KeyboardView.drawPreviews(c: Canvas) {
    val t = theme
    for (p in pointers.values) {
        if (p.grass && p.longPressed) {
            drawEditMenu(c, p)
            continue
        }
        if (p.grass) continue
        val k = p.key ?: continue
        if (k.kind == Kind.SHIFT && p.longPressed && !p.consumed) {
            drawShiftMenu(c, k, p)
            continue
        }
        if (p.consumed || p.zooming || k.rect.top < barH) continue
        if (k.kind != Kind.LETTER && k.kind != Kind.TEXT) continue
        val hint = p.options ?: k.hint
        if (p.longPressed && hint != null && hint.length > 1) {
            // Pick-one popup (e.g. ",!?" on the period key, or "#ß" held on s).
            val (left, cellW) = altPopup(k, hint)
            val h = k.rect.height()
            val top = max(-overlayH, k.rect.top - h * 1.05f)
            popIn(c, p.longPressAt, k.rect.centerX(), top + h) {
                fill.color = t.zoom
                tmp.set(left, top, left + cellW * hint.length, top + h)
                shadowOn()
                c.drawRoundRect(tmp, popupRadius, popupRadius, fill)
                shadowOff()
                hint.forEachIndexed { i, ch ->
                    val l = left + i * cellW
                    val sel = i == p.alt
                    if (sel) {
                        fill.color = t.zoomSelect
                        tmp.set(l + dpu(3f), top + dpu(3f), l + cellW - dpu(3f), top + h - dpu(3f))
                        c.drawRoundRect(tmp, popupRadius * 0.7f, popupRadius * 0.7f, fill)
                    }
                    drawText(c, ch.toString(), l + cellW / 2, top + h / 2, h * 0.5f, if (sel) t.onZoomSelect else t.text, regular)
                }
            }
            continue
        }
        val label = if (p.longPressed && hint != null) hint
        else if (k.kind == Kind.LETTER) upper(k.label) else k.label
        val w = max(k.face.width() * 1.25f, dpu(44f))
        val h = max(k.face.height() * 1.15f, dpu(48f))
        val left = (k.face.centerX() - w / 2).coerceIn(0f, width - w)
        val bottom = k.face.top + k.face.height() * 0.15f
        val top = max(-overlayH, bottom - h)
        popIn(c, if (p.longPressed) p.longPressAt else p.downAt, k.face.centerX(), bottom) {
            fill.color = t.zoom
            tmp.set(left, top, left + w, top + h)
            shadowOn()
            c.drawRoundRect(tmp, dpu(12f), dpu(12f), fill)
            shadowOff()
            if (isIconSlot(label)) drawSlotIcon(c, label, tmp.centerX(), tmp.centerY(), h * 0.25f, t.text)
            else drawText(c, label, tmp.centerX(), tmp.centerY(), h * 0.5f, t.text, regular)
        }
    }
}

/**
 * The zoom: a popup above the keyboard (symbols over big letters) joined to the five
 * magnified slots by a funnel, with a light beam from the chosen letter down to the finger.
 */
internal fun KeyboardView.drawZoom(c: Canvas) {
    val z = zoom ?: return
    popIn(c, z.openedAt, slotCenter(z.slot), letterRect.top) { drawZoomPopup(c, z) }
}

internal fun KeyboardView.drawZoomPopup(c: Canvas, z: Zoom) {
    val t = theme
    val cellW = zoomCellW
    val bottom = barH
    val top = bottom - zoomSymH - zoomLetH
    val mid = top + zoomSymH
    val groupL = slotLeft(z.start)
    val groupR = slotLeft(z.start + 5)
    val left = ((groupL + groupR) / 2 - 2.5f * cellW).coerceIn(0f, width - 5 * cellW)
    val right = left + 5 * cellW

    // Funnel from the popup down to the magnified slots, then over their column of the row.
    fill.color = t.zoom
    fill.alpha = 170
    path.reset()
    path.moveTo(left, bottom)
    path.lineTo(right, bottom)
    path.lineTo(groupR, stripRect.centerY())
    path.lineTo(groupR, letterRect.bottom)
    path.lineTo(groupL, letterRect.bottom)
    path.lineTo(groupL, stripRect.centerY())
    path.close()
    c.drawPath(path, fill)
    fill.alpha = 255

    // Popup: darker symbol row over the letter row.
    val rad = popupRadius
    tmp.set(left, top, right, bottom)
    fill.color = t.zoom
    shadowOn()
    c.drawRoundRect(tmp, rad, rad, fill)
    shadowOff()
    fill.color = ColorUtils.blendARGB(t.zoom, Color.BLACK, 0.18f)
    tmp.set(left, top, right, mid + rad)
    c.drawRoundRect(tmp, rad, rad, fill)
    fill.color = t.zoom
    c.drawRect(left, mid, right, mid + rad, fill)

    // Number mode's bottom row shows five digit-row keys around the finger's.
    val digits = Layouts.MINI_NUMBER_ROW
    val digitW = letterRect.width() / digits.size
    val digitStart = (z.digit - 2).coerceIn(0, digits.size - 5)
    val col = if (z.numbers && z.row == 1) z.digit - digitStart else z.slot - z.start
    val cl = left + col * cellW
    fill.color = t.zoomSelect
    if (z.row == 1) {
        // Selected letter cell plus the beam down to the letter's slot and the finger.
        c.drawRect(cl, mid, cl + cellW, bottom, fill)
        val sl = if (z.numbers) letterRect.left + z.digit * digitW else slotLeft(z.slot) - slotW * 0.1f
        val sr = if (z.numbers) sl + digitW else slotLeft(z.slot + 1) + slotW * 0.1f
        fill.alpha = 225
        path.reset()
        path.moveTo(cl, bottom)
        path.lineTo(cl + cellW, bottom)
        path.lineTo(sr, stripRect.centerY())
        path.lineTo(sr, letterRect.bottom)
        path.lineTo(sl, letterRect.bottom)
        path.lineTo(sl, stripRect.centerY())
        path.close()
        c.drawPath(path, fill)
        fill.alpha = 255
    } else {
        c.drawRect(cl, top, cl + cellW, mid, fill)
    }

    for (i in 0 until 5) {
        val s = z.start + i
        val cx = left + (i + 0.5f) * cellW
        val symSel = z.row == 0 && s == z.slot
        val offered = z.german != null && s == z.germanSlot
        val sym = if (offered) z.german!! else (if (z.numbers) Layouts.MINI_NUMBER_STRIP else Layouts.MINI_STRIP)[s]
        val symColor = when {
            symSel -> t.onZoomSelect
            offered -> t.accent
            else -> t.text
        }
        if (isIconSlot(sym)) drawSlotIcon(c, sym, cx, top + zoomSymH / 2, zoomSymH * 0.22f, symColor)
        else drawText(c, sym, cx, top + zoomSymH / 2, zoomSymH * 0.62f, symColor, regular)
        val letSel = z.row == 1 && if (z.numbers) digitStart + i == z.digit else s == z.slot
        val big = if (z.numbers) digits[digitStart + i] else upper(layout.miniOrder[s].toString())
        drawText(c, big, cx, mid + zoomLetH * 0.48f, zoomLetH * 0.66f, if (letSel) t.onZoomSelect else t.text, regular)
    }
}

/**
 * Shift's hold menu: [settings | switch keyboard], one square cell each, above the key (for the
 * bar's shift, in the space over the keyboard). The first cell sits over shift itself.
 */
internal fun KeyboardView.shiftMenuRect(k: Key, out: RectF) {
    val s = max(dpu(48f), k.rect.height() * 1.1f)
    val w = s * SHIFT_MENU.size
    val left = (k.rect.centerX() - s / 2).coerceIn(dpu(4f), width - w - dpu(4f))
    val top = max(-overlayH + dpu(2f), k.rect.top - s - dpu(6f))
    out.set(left, top, left + w, top + s)
}

/** The menu cell the finger is over (or above / below), or -1 once it has slid away. */
internal fun KeyboardView.shiftMenuChoice(k: Key, p: Pointer): Int {
    val r = hitRect
    shiftMenuRect(k, r)
    val near = nearRect.apply { set(r); inset(-dpu(12f), -dpu(12f)); union(k.rect) }
    if (!near.contains(p.x, p.y)) return -1
    return ((p.x - r.left) / r.height()).toInt().coerceIn(0, SHIFT_MENU.lastIndex)
}

internal fun KeyboardView.drawShiftMenu(c: Canvas, k: Key, p: Pointer) {
    val t = theme
    val r = RectF()
    shiftMenuRect(k, r)
    val choice = shiftMenuChoice(k, p)
    val s = r.height()
    popIn(c, p.longPressAt, k.rect.centerX(), r.bottom) {
        fill.color = t.zoom
        shadowOn()
        c.drawRoundRect(r, popupRadius, popupRadius, fill)
        shadowOff()
        for (i in SHIFT_MENU.indices) {
            val l = r.left + i * s
            val sel = i == choice
            if (sel) {
                fill.color = t.zoomSelect
                tmp.set(l + dpu(4f), r.top + dpu(4f), l + s - dpu(4f), r.bottom - dpu(4f))
                c.drawRoundRect(tmp, popupRadius * 0.7f, popupRadius * 0.7f, fill)
            }
            val color = if (sel) t.onZoomSelect else t.text
            val cx = l + s / 2
            when (SHIFT_MENU[i]) {
                MENU_SETTINGS -> icons.sliders(c, cx, r.centerY(), s * 0.22f, color)
                MENU_KEYBOARDS -> icons.keyboard(c, cx, r.centerY(), s * 0.22f, color)
            }
        }
    }
}

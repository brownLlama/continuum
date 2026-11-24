// The grass's hold menu: Select, All, Cut, Copy, Paste.
package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import com.brownllama.continuum.R
import com.brownllama.continuum.keyboard.KeyboardView.EditAction
import kotlin.math.min

/**
 * The menu's place: one row of cells just above the grass, centred on where the finger went down
 * (kept on screen). It is drawn over the bottom keys, like shift's hold menu.
 */
internal fun KeyboardView.editMenuRect(out: RectF, centerX: Float) {
    val cell = min(dpu(EDIT_CELL_DP), (width - dpu(8f)) / EDIT_MENU.size)
    val w = cell * EDIT_MENU.size
    val h = dpu(EDIT_HEIGHT_DP)
    val left = (centerX - w / 2).coerceIn(dpu(4f), width - w - dpu(4f))
    val bottom = keyboardHeight - dpu(8f)
    out.set(left, bottom - h, left + w, bottom)
}

/**
 * The menu item the finger is over, or -1: on the grass itself (so lifting there does nothing),
 * or too far from the menu. The finger slides up from the grass onto the item it wants.
 */
internal fun KeyboardView.editMenuChoice(p: Pointer): Int {
    val r = hitRect
    editMenuRect(r, p.downX)
    val near = nearRect.apply { set(r); inset(-dpu(12f), -dpu(12f)) }
    if (!near.contains(p.x, p.y)) return -1
    return ((p.x - r.left) / (r.width() / EDIT_MENU.size)).toInt().coerceIn(0, EDIT_MENU.lastIndex)
}

/** A hold on the grass: show the menu (not over the emoji, GIF, clipboard and sticker panels). */
internal fun KeyboardView.openEditMenu(p: Pointer) {
    if (panel.big) return
    p.longPressed = true
    p.longPressAt = SystemClock.uptimeMillis()
    p.alt = -1
    keyVibration.tap()
    invalidate()
}

/** The finger lifted with the menu open: do what the item under it says (nothing if it is not over one). */
internal fun KeyboardView.applyEditMenu(p: Pointer) {
    when (EDIT_MENU.getOrNull(editMenuChoice(p))) {
        EDIT_SELECT -> selecting = !selecting
        EDIT_ALL -> { listener.onEditAction(EditAction.SELECT_ALL); selecting = false }
        EDIT_CUT -> { listener.onEditAction(EditAction.CUT); selecting = false }
        EDIT_COPY -> { listener.onEditAction(EditAction.COPY); selecting = false }
        EDIT_PASTE -> { listener.onEditAction(EditAction.PASTE); selecting = false }
    }
}

private fun KeyboardView.editLabel(item: Int) = resources.getString(
    when (item) {
        EDIT_SELECT -> R.string.edit_select
        EDIT_ALL -> R.string.edit_all
        EDIT_CUT -> R.string.edit_cut
        EDIT_COPY -> R.string.edit_copy
        else -> R.string.edit_paste
    },
)

internal fun KeyboardView.drawEditMenu(c: Canvas, p: Pointer) {
    val t = theme
    val r = RectF()
    editMenuRect(r, p.downX)
    val choice = editMenuChoice(p)
    val cell = r.width() / EDIT_MENU.size
    popIn(c, p.longPressAt, p.downX.coerceIn(r.left, r.right), r.bottom) {
        fill.color = t.zoom
        shadowOn()
        c.drawRoundRect(r, popupRadius, popupRadius, fill)
        shadowOff()
        for (i in EDIT_MENU.indices) {
            val l = r.left + i * cell
            val sel = i == choice
            if (sel) {
                fill.color = t.zoomSelect
                tmp.set(l + dpu(3f), r.top + dpu(4f), l + cell - dpu(3f), r.bottom - dpu(4f))
                c.drawRoundRect(tmp, popupRadius * 0.7f, popupRadius * 0.7f, fill)
            }
            // Select shows in the accent colour while it is on.
            val color = when {
                sel -> t.onZoomSelect
                EDIT_MENU[i] == EDIT_SELECT && selecting -> t.accent
                else -> t.text
            }
            val label = editLabel(EDIT_MENU[i])
            // Long words ("Ausschneiden") shrink to fit their cell.
            text.typeface = regular
            text.textSize = dpu(14f)
            val size = dpu(14f) * min(1f, (cell - dpu(8f)) / text.measureText(label))
            drawText(c, label, l + cell / 2, r.centerY(), size, color, regular)
        }
    }
}

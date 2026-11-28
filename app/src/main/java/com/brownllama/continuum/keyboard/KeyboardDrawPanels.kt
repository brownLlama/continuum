// Drawing: the clipboard, emoji and GIF panels.
package com.brownllama.continuum.keyboard

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import com.brownllama.continuum.R
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The clipboard panel: its title in the bar, then the cards (see [layoutClipCards]): the text, or
 * the whole picture, the one under a resting finger highlighted. A hint shows while it's empty.
 */
internal fun KeyboardView.drawClipboard(c: Canvas) {
    val t = theme
    text.textAlign = Paint.Align.LEFT
    drawText(c, resources.getString(R.string.clip_title), pad * 2, barH / 2, barH * 0.42f, t.text, medium)
    text.textAlign = Paint.Align.CENTER
    c.withClip(clipRect) {
        if (clips.isEmpty()) {
            val message = clipMessage ?: resources.getString(R.string.clip_empty)
            // A longer message is shrunk to the panel's width.
            var size = dp(14f)
            text.typeface = regular
            text.textSize = size
            val room = clipRect.width() - 4 * pad
            val w = text.measureText(message)
            if (w > room && w > 0f) size *= room / w
            drawText(c, message, clipRect.centerX(), clipRect.centerY(), size, t.dim, regular)
            return@withClip
        }
        val pressed = pointers.values.firstOrNull { it.area == Area.GRID && !it.moved && !it.consumed }
            ?.let { clipAt(it.downX, it.downY) }
        val radius = dpu(10f)
        val inset = clipInset
        clipPaint.color = t.text
        for ((i, clip) in clips.withIndex()) {
            clipCardRect(i, tmp)
            if (!RectF.intersects(tmp, clipRect)) continue
            fill.color = if (i == pressed) t.keyPressed else t.keyFill
            c.drawRoundRect(tmp, radius, radius, fill)
            if (clip == null) {
                clipImages.getOrNull(i)?.let { drawClipImage(c, it, radius) }
                continue
            }
            val layout = clipLayout(clip)
            c.withSave {
                c.clipRect(tmp)
                c.translate(tmp.left + inset, tmp.top + (tmp.height() - layout.height) / 2)
                layout.draw(c)
            }
        }
    }
}

/**
 * A copied picture on its card ([tmp]), whole: the card has its shape, so it fills it inside a thin
 * edge (which shows the card's highlight); one taller than a card may be is shown smaller, centred.
 */
private fun KeyboardView.drawClipImage(c: Canvas, image: Bitmap, radius: Float) {
    val edge = dpu(3f)
    tmp.inset(edge, edge)
    val scale = min(tmp.width() / image.width, tmp.height() / image.height)
    val w = image.width * scale
    val h = image.height * scale
    clipDst.set(tmp.centerX() - w / 2, tmp.centerY() - h / 2, tmp.centerX() + w / 2, tmp.centerY() + h / 2)
    // The picture as the fill of a rounded rectangle: cheaper while scrolling than clipping to a path.
    val shader = clipShaders.getOrPut(image) { BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    clipMatrix.setScale(scale, scale)
    clipMatrix.postTranslate(clipDst.left, clipDst.top)
    shader.setLocalMatrix(clipMatrix)
    clipImagePaint.shader = shader
    c.drawRoundRect(clipDst, radius - edge, radius - edge, clipImagePaint)
    clipImagePaint.shader = null
}

/** The visible rows of the emoji grid, with a highlight under a finger that's about to tap. */
internal fun KeyboardView.drawEmojiGrid(c: Canvas) = c.withClip(emojiRect) {
    val t = theme
    for (p in pointers.values) {
        if (p.area != Area.GRID || p.moved || emojiAt(p.downX, p.downY) == null) continue
        val row = floor((p.downY - emojiRect.top + emojiScroll) / emojiRowH)
        val col = floor(p.downX / emojiCellW)
        val top = emojiRect.top + row * emojiRowH - emojiScroll
        fill.color = t.keyPressed
        tmp.set(col * emojiCellW, top, (col + 1) * emojiCellW, top + emojiRowH)
        tmp.inset(dpu(3f), dpu(3f))
        c.drawRoundRect(tmp, dpu(8f), dpu(8f), fill)
    }
    val first = floor(emojiScroll / emojiRowH).toInt()
    val last = min(emojiRows - 1, floor((emojiScroll + emojiRect.height()) / emojiRowH).toInt())
    var sec = 0
    for (row in first..last) {
        while (sec + 1 < emojiSections.size && emojiSectionRow[sec + 1] <= row) sec++
        val list = emojiSections[sec].emoji
        val base = (row - emojiSectionRow[sec]) * EMOJI_COLUMNS
        val cy = emojiRect.top + (row + 0.5f) * emojiRowH - emojiScroll
        for (col in 0 until EMOJI_COLUMNS) {
            val e = list.getOrNull(base + col) ?: break
            drawText(c, e, (col + 0.5f) * emojiCellW, cy, emojiCellW * 0.46f, t.text, emojiFace)
        }
    }
}

/**
 * The GIF grid: rounded tiles in [GIF_COLUMNS] columns (one sideways row while searching), each
 * GIF scaled to fill its tile. Only visible GIFs animate. Without tiles, [gifMessage] is shown instead.
 */
internal fun KeyboardView.drawGifGrid(c: Canvas) = c.withClip(gifRect) {
    val t = theme
    if (gifTiles.isEmpty()) {
        gifMessage?.let { drawText(c, it, gifRect.centerX(), gifRect.centerY(), dp(14f), t.dim, regular) }
        return@withClip
    }
    val radius = dpu(10f)
    val pressed = pointers.values.firstOrNull { it.area == Area.GRID && !it.moved }?.let { gifAt(it.downX, it.downY) }
    for ((i, tile) in gifTiles.withIndex()) {
        gifTileRect(i, tmp)
        val anim = tile.image as? Animatable
        if (!RectF.intersects(tmp, gifRect)) {
            if (anim?.isRunning == true) anim.stop()
            continue
        }
        fill.color = t.keyFill
        c.drawRoundRect(tmp, radius, radius, fill)
        val image = tile.image ?: continue
        if (anim != null && !anim.isRunning) anim.start()
        c.withSave {
            path.reset()
            path.addRoundRect(tmp, radius, radius, Path.Direction.CW)
            c.clipPath(path)
            // Centre-crop: scale to cover the tile, keep the aspect ratio.
            val iw = image.intrinsicWidth.coerceAtLeast(1).toFloat()
            val ih = image.intrinsicHeight.coerceAtLeast(1).toFloat()
            val scale = max(tmp.width() / iw, tmp.height() / ih)
            val dw = iw * scale
            val dh = ih * scale
            val dl = tmp.centerX() - dw / 2
            val dt = tmp.centerY() - dh / 2
            image.setBounds(dl.roundToInt(), dt.roundToInt(), (dl + dw).roundToInt(), (dt + dh).roundToInt())
            image.draw(c)
            if (i == pressed) {
                fill.color = t.text
                fill.alpha = 60
                c.drawRect(tmp, fill)
                fill.alpha = 255
            }
        }
    }
}

/** The search field in the GIF panel's bar: a pill with a magnifier, the query or a hint, and a cursor while typing. */
internal fun KeyboardView.drawGifSearchField(c: Canvas, r: RectF) {
    val t = theme
    tmp.set(r.left + dpu(4f), r.top + r.height() * 0.17f, r.right - dpu(10f), r.bottom - r.height() * 0.17f)
    val h = tmp.height()
    fill.color = if (gifSearching) softPressedFill else softFill
    c.drawRoundRect(tmp, h / 2, h / 2, fill)
    // Magnifier.
    val s = h * 0.2f
    val mx = tmp.left + h * 0.55f
    val my = tmp.centerY()
    stroke.color = t.dim
    stroke.strokeWidth = dpu(1.8f)
    c.drawCircle(mx - s * 0.2f, my - s * 0.2f, s * 0.75f, stroke)
    c.drawLine(mx + s * 0.35f, my + s * 0.35f, mx + s * 0.95f, my + s * 0.95f, stroke)
    // Query (end shown if too long) or hint.
    val x0 = mx + h * 0.5f
    val maxW = tmp.right - x0 - h * 0.4f
    text.typeface = regular
    text.textSize = h * 0.45f
    text.textAlign = Paint.Align.LEFT
    val fm = text.fontMetrics
    val baseline = my - (fm.ascent + fm.descent) / 2
    if (gifQuery.isEmpty() && !gifSearching) {
        text.color = t.dim
        c.drawText(resources.getString(R.string.gif_search_hint), x0, baseline, text)
    } else {
        var q = gifQuery
        while (q.isNotEmpty() && text.measureText(q) > maxW) q = q.drop(1)
        text.color = t.text
        c.drawText(q, x0, baseline, text)
        if (gifSearching) {
            // Right against the last letter: a gap here reads as a space that isn't there.
            val cx = x0 + text.measureText(q)
            fill.color = t.accent
            c.drawRect(cx, my - h * 0.25f, cx + dpu(1.5f), my + h * 0.25f, fill)
        }
    }
    text.textAlign = Paint.Align.CENTER
}

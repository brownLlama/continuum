// Touch handling: fingers down, moving and up, long presses and swipe gestures.
package com.brownllama.continuum.keyboard

import android.os.SystemClock
import android.view.MotionEvent
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Swipe or tap? A swipe is a fairly straight stroke that is either quick and at least
 * [swipeMinDp] long, or long at any speed ([swipeLongDp]). A tap that lands and rolls or
 * drags a little is slow and short, and a wobbly path is a sloppy tap, not a swipe.
 */
internal fun KeyboardView.isSwipe(p: Pointer): Boolean {
    val distPx = hypot(p.x - p.downX, p.y - p.downY)
    val dist = distPx / density
    if (dist < swipeMinDp) return false
    if (p.pathLen > 0f && distPx / p.pathLen < swipeStraightness) return false
    val ms = max(1L, touchTime - p.downTime)
    return dist / ms >= swipeSpeed || dist >= swipeLongDp
}

internal fun KeyboardView.handleTouch(e: MotionEvent) {
    touchTime = e.eventTime
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            // An animal walks on (touches go to the keys); a visit only starts in a pause in typing.
            lastTouchAt = e.eventTime
            scheduleAnimal()
            // A new finger landing finishes simple taps still in progress, keeping fast two-thumb typing in order.
            for (p in pointers.values.toList()) if (p.flushable) release(p)
            val i = e.actionIndex
            down(e.getPointerId(i), e.getX(i), e.getY(i))
        }
        MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
            pointers[e.getPointerId(i)]?.let { p ->
                // Batched intermediate points make the path length (straightness) accurate.
                for (h in 0 until e.historySize) move(p, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                move(p, e.getX(i), e.getY(i))
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val i = e.actionIndex
            pointers[e.getPointerId(i)]?.let {
                move(it, e.getX(i), e.getY(i))
                release(it)
            }
        }
        MotionEvent.ACTION_CANCEL -> {
            for (p in pointers.values) {
                timer.removeCallbacks(p.longPress)
                p.repeat?.let(timer::removeCallbacks)
                if (p.area == Area.TREE) tree.drop(SystemClock.uptimeMillis())
            }
            pointers.clear()
            zoom = null
            invalidate()
        }
    }
}

/** The key under (x, y), or the nearest one so a touch just between keys still counts. */
internal fun KeyboardView.hitKey(list: List<Key>, x: Float, y: Float): Key? =
    list.firstOrNull { it.rect.contains(x, y) }
        ?: list.minByOrNull { hypot(it.rect.centerX() - x, it.rect.centerY() - y) }

/** A finger landed: work out what it's on and start the long-press / backspace-repeat timers. */
internal fun KeyboardView.down(id: Int, x: Float, y: Float) {
    val letters = mode == Mode.MINI && panel == Panel.LETTERS
    if (y < -gifHeaderH) return // the transparent overlay above the keyboard
    if (confirmForget >= 0) {
        // Any touch answers the "Remove from suggestions?" question; only Remove removes.
        val slot = confirmForget
        confirmForget = -1
        invalidate()
        if (y < barH) {
            if (removeRect.contains(x, y)) listener.onForgetSuggestion(slot)
            return
        }
    }
    val p = pickUpTree(id, x, y) ?: when {
        // The GIF search header: its keys, then the strip of GIFs.
        y < -gifStripH -> hitKey(headerKeys, x, y)?.let { Pointer(this, id, x, y, Area.KEY, it) }
        y < 0f -> Pointer(this, id, x, y, Area.GRID, null)
        y < barH -> hitKey(barKeys, x, y)?.let { Pointer(this, id, x, y, Area.KEY, it) }
        letters && y < stripRect.bottom -> Pointer(this, id, x, y, Area.STRIP, null)
        // Below the row (the gap above the screen edge) still counts as the row.
        letters -> Pointer(this, id, x, y, Area.LETTERS, null)
        // Emoji / GIF grid: the floating ✕ is a key, the rest scrolls or picks.
        panel.big -> keys.firstOrNull { it.rect.contains(x, y) }?.let { Pointer(this, id, x, y, Area.KEY, it) }
            ?: Pointer(this, id, x, y, Area.GRID, null)
        else -> hitKey(keys, x, y)?.let { Pointer(this, id, x, y, Area.KEY, it) }
    } ?: return
    pointers[id] = p
    // A tick for a key or the letter row; not for the emoji / GIF / sticker grids, which scroll.
    if (p.area != Area.GRID) keyVibration.tap()
    if (p.area == Area.LETTERS || p.area == Area.STRIP) glows += Glow(p)
    if (p.key?.kind == Kind.BACKSPACE) {
        backspace()
        val r = object : Runnable {
            override fun run() {
                backspace()
                timer.postDelayed(this, REPEAT_MS)
            }
        }
        p.repeat = r
        timer.postDelayed(r, REPEAT_DELAY_MS)
    } else if (p.area != Area.GRID || panel == Panel.CLIPBOARD || panel == Panel.STICKERS) {
        // Clip cards and the user's stickers can be held (to delete them); emoji and GIFs can't.
        timer.postDelayed(p.longPress, holdMs)
    }
    invalidate()
}

/** A finger moved: scroll, update the zoom or popup selection, or note that it's no longer a tap. */
internal fun KeyboardView.move(p: Pointer, x: Float, y: Float) {
    p.pathLen += hypot(x - p.x, y - p.y)
    p.x = x
    p.y = y
    val k = p.key
    if (p.area == Area.TREE) return treeMove(p, x, y)
    if (p.area == Area.GRID) {
        // The emoji / GIF grid scrolls vertically (the search header's strip sideways); any real
        // movement means it's not a tap.
        val sideways = gifSearching
        val d = if (sideways) x - p.downX else y - p.downY
        if (!p.moved && hypot(x - p.downX, y - p.downY) > slop) p.moved = true
        if (!p.scrolling && abs(d) > slop) {
            p.scrolling = true
            p.scrollStart = gridScroll + d
            gridScroller.forceFinished(true)
        }
        if (p.scrolling) {
            gridScroll = (p.scrollStart - d).coerceIn(0f, maxGridScroll)
            val now = SystemClock.uptimeMillis()
            if (now > p.lastT) {
                if (sideways) p.vx = (x - p.lastX) * 1000f / (now - p.lastT)
                else p.vy = (y - p.lastY) * 1000f / (now - p.lastT)
            }
            p.lastX = x
            p.lastY = y
            p.lastT = now
        }
        invalidate()
        return
    }
    if (k?.kind == Kind.SUGGESTION) {
        val dx = x - p.downX
        if (!p.scrolling && abs(dx) > slop && abs(dx) > abs(y - p.downY)) {
            p.scrolling = true
            p.moved = true
            p.scrollStart = barScroll
            scroller.forceFinished(true)
            timer.removeCallbacks(p.longPress)
        }
        if (p.scrolling) {
            barScroll = (p.scrollStart - dx).coerceIn(0f, maxBarScroll)
            val now = SystemClock.uptimeMillis()
            if (now > p.lastT) p.vx = (x - p.lastX) * 1000f / (now - p.lastT)
            p.lastX = x
            p.lastT = now
            invalidate()
            return
        }
    }
    if (p.zooming) {
        updateZoom(p)
    } else if (p.longPressed && k?.kind == Kind.SHIFT) {
        val a = shiftMenuChoice(k, p)
        p.alt = a
    } else if (p.longPressed && k != null && (p.options ?: k.hint).orEmpty().length > 1) {
        val opts = p.options ?: k.hint!!
        val (left, cellW) = altPopup(k, opts)
        val a = ((x - left) / cellW).toInt().coerceIn(0, opts.length - 1)
        p.alt = a
    } else if (!p.moved && hypot(x - p.downX, y - p.downY) > slop) {
        p.moved = true
        if (!p.longPressed) timer.removeCallbacks(p.longPress)
    }
    invalidate()
}

/**
 * Still holding a letter after its long press: its German form ("ß" for s) joins the popup after
 * the hint ("#"). The hint stays selected; slide onto the German letter to pick it.
 */
internal fun KeyboardView.onLongerPress(p: Pointer) {
    val k = p.key ?: return
    if (pointers[p.id] !== p || !p.longPressed || p.consumed || p.options != null) return
    val g = GERMAN[k.label.firstOrNull() ?: return] ?: return
    val opts = (k.hint ?: "") + germanCase(g)
    p.options = opts
    p.alt = 0
    p.longPressAt = SystemClock.uptimeMillis()
    invalidate()
}

/** The hold timer ([holdMs]) fired while the finger is still down and hasn't moved. */
internal fun KeyboardView.onLongPress(p: Pointer) {
    if (pointers[p.id] !== p || p.moved) return
    when (p.area) {
        Area.TREE -> {}
        Area.STRIP, Area.LETTERS -> startZoom(p)
        Area.GRID -> if (panel == Panel.CLIPBOARD) {
            clipAt(p.downX, p.downY)?.let {
                p.consumed = true
                listener.onClipRemove(it)
            }
        } else if (panel == Panel.STICKERS) {
            stickerAt(p.downX, p.downY)?.takeIf { stickerRemovable.getOrElse(it) { false } }?.let {
                p.consumed = true
                listener.onStickerRemove(it)
            }
        }
        Area.KEY -> when (p.key!!.kind) {
            Kind.SPACE -> {
                p.consumed = true
                listener.onPickInputMethod()
            }
            // Holding shift offers settings and switching keyboards (drawShiftMenu).
            Kind.SHIFT -> {
                p.longPressed = true
                p.longPressAt = SystemClock.uptimeMillis()
                p.alt = 0
            }
            Kind.SUGGESTION -> {
                val i = suggestionAt(p.downX)
                if (suggestions.getOrNull(i).orEmpty().isNotEmpty()) {
                    p.consumed = true
                    confirmForget = i
                }
            }
            Kind.LETTER, Kind.TEXT -> if (p.key.hint != null) {
                p.longPressed = true
                p.longPressAt = SystemClock.uptimeMillis()
                p.alt = 0
                if (p.key.kind == Kind.LETTER && p.key.label.firstOrNull() in GERMAN) timer.postDelayed(p.longerPress, GERMAN_WAIT_MS)
            } else if (zoomsInNumbers(p.key)) {
                startZoom(p)
            }
            else -> {}
        }
    }
    invalidate()
    accessibility.changed()
}

/** A finger lifted: finish a scroll or zoom, or decide between a swipe gesture and a tap. */
internal fun KeyboardView.release(p: Pointer) {
    pointers.remove(p.id)
    timer.removeCallbacks(p.longPress)
    p.repeat?.let(timer::removeCallbacks)
    if (p.area == Area.TREE) {
        // Let go: the cursor stops, and the tree slides back to where it stands.
        tree.drop(SystemClock.uptimeMillis())
        invalidate()
        return
    }
    when {
        p.consumed || p.key?.kind == Kind.BACKSPACE -> {}
        p.scrolling && p.area == Area.GRID -> {
            val max = maxGridScroll.roundToInt()
            if (gifSearching) gridScroller.fling(gridScroll.roundToInt(), 0, (-p.vx).roundToInt(), 0, 0, max, 0, 0)
            else gridScroller.fling(0, gridScroll.roundToInt(), 0, (-p.vy).roundToInt(), 0, 0, 0, max)
            postInvalidateOnAnimation()
        }
        p.scrolling -> {
            scroller.fling(barScroll.roundToInt(), 0, (-p.vx).roundToInt(), 0, 0, maxBarScroll.roundToInt(), 0, 0)
            postInvalidateOnAnimation()
        }
        p.zooming -> commitZoom()
        else -> {
            val dx = p.x - p.downX
            val dy = p.y - p.downY
            // Swipes start on the keyboard, not on the GIF search header above it.
            if (!p.longPressed && !panel.big && p.downY >= 0f && isSwipe(p)) {
                gesture(dx, dy, fromBar = p.downY < barH)
            } else {
                tap(p)
            }
        }
    }
    invalidate()
    accessibility.changed()
}

/**
 * Swipe shortcuts.
 * Horizontal (not from the bar): left deletes a word, right types a space.
 * Straight up: mini → full mode. Straight down: text ↔ numbers, except a swipe down that
 * starts on the suggestion bar in full mode, which goes back to mini mode.
 * Up and left: the other language (English ↔ German), in a wide sector (about 30° to 68° above
 * flat, [LANGUAGE_MIN_SLOPE]..[LANGUAGE_MAX_SLOPE] across per up), so a natural thumb swipe lands in it:
 * flatter is the delete-word swipe, steeper the full keyboard. Up and right: enter.
 */
internal fun KeyboardView.gesture(dx: Float, dy: Float, fromBar: Boolean) {
    val ax = abs(dx)
    val ay = abs(dy)
    when {
        dx < 0 && dy < 0 && ax >= ay * LANGUAGE_MIN_SLOPE && ax < ay * LANGUAGE_MAX_SLOPE -> listener.onLanguageToggle()
        ax >= ay * 1.2f -> if (!fromBar) {
            if (dx < 0) listener.onDeleteWord() else listener.onSpace()
        }
        dy > 0 -> if (mode == Mode.FULL && fromBar) listener.onModeToggle() else toggleNumbers()
        ax < ay * 0.6f -> if (mode == Mode.MINI) listener.onModeToggle()
        dx < 0 -> listener.onLanguageToggle()
        else -> listener.onEnter()
    }
}

/**
 * The finger carrying the tree moved: the tree follows it, and the cursor moves with it, one
 * character for each [CURSOR_STRIDE_DP] sideways and one line for each [CURSOR_LINE_DP] up or down.
 */
internal fun KeyboardView.treeMove(p: Pointer, x: Float, y: Float) {
    if (!tree.carried) return
    p.moved = true
    tree.carry(x, y)
    val stride = dpu(CURSOR_STRIDE_DP)
    val steps = ((x - p.cursorX) / stride).toInt()
    if (steps != 0) {
        p.cursorX += steps * stride
        listener.onCursorMove(steps)
        keyVibration.tap()
    }
    val lineH = dpu(CURSOR_LINE_DP)
    val lines = ((y - p.cursorY) / lineH).toInt()
    if (lines != 0) {
        p.cursorY += lines * lineH
        listener.onCursorLines(lines)
        keyVibration.tap()
    }
    invalidate()
}

/**
 * A finger landed on the tree (in the grass between the keys and Android's gesture bar, where nothing
 * else is touched): it is picked up, and from then on carried by that finger. Returns the pointer, or
 * null when the touch is not on the tree.
 */
internal fun KeyboardView.pickUpTree(id: Int, x: Float, y: Float): Pointer? {
    if (gifSearching || panel.big) return null
    if (!tree.hit(x, y, dpu(TREE_SLACK_DP), keyboardHeight, treeBase())) return null
    val p = Pointer(this, id, x, y, Area.TREE, null)
    p.cursorX = x
    p.cursorY = y
    tree.pickUp(x, y)
    keyVibration.tap()
    return p
}

/** A finger lifted without swiping. */
internal fun KeyboardView.tap(p: Pointer) {
    when (p.area) {
        // A quick tap on the strip is a letter tap too: symbols are only typed via the hold popup.
        Area.LETTERS, Area.STRIP -> listener.onLetterTap(Tap(slotAt(p.downX).coerceIn(-0.5f, 25.5f), 0f))
        Area.GRID -> if (!p.moved) {
            when {
                showsGifs -> gifAt(p.downX, p.downY)?.let(listener::onGifPicked)
                panel == Panel.CLIPBOARD -> clipAt(p.downX, p.downY)?.let(listener::onClipPicked)
                panel == Panel.STICKERS -> stickerAt(p.downX, p.downY)?.let(listener::onStickerPicked)
                else -> emojiAt(p.downX, p.downY)?.let(listener::onEmoji)
            }
        }
        Area.KEY -> keyTap(p.key!!, p)
        Area.TREE -> {}
    }
}

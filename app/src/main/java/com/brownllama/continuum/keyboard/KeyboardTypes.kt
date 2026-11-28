// The small types the keyboard view works with: keys, fingers, glows and the zoom.
package com.brownllama.continuum.keyboard

import android.graphics.RectF
import android.os.SystemClock

/** What a key does when tapped (see [keyTap]) and how it's drawn (see [drawKey]). */
internal enum class Kind {
    LETTER, // full-mode letter; the tap position is decoded, not the label
    TEXT, // types its label (digits, symbols, "," and ".")
    SHIFT, BACKSPACE, SPACE, ENTER,
    SYMBOLS, // "123": open the number page
    MORE, // flip between the two number pages
    ABC, // back to letters
    AUTOCORRECT, // full mode's bar switch
    SUGGESTION, // the whole scrollable suggestion strip (one key)
    EMOJI_TAB, CLOSE, // emoji panel: jump to a group, close the panel (also in the GIF panel)
    GIF_TAB, // emoji panel → GIF panel
    GIF_BACK, // GIF panel → emoji panel
    GIF_SEARCH, // the search field in the GIF panel's bar
    GIF_CANCEL, // the search header's ⌄: hide the keyboard, back to the GIF grid
    CLIP_CLEAR, // the clipboard panel's bin: delete every clip
}

/** How a TEXT key is drawn. */
internal enum class Style { PLAIN, STRIP, BIG, FILLED }

/**
 * One touchable key. [hint] is shown small in the corner and typed on long-press; several
 * characters (",!?") open a popup to pick one. [value] is the group index for [Kind.EMOJI_TAB].
 * [face] is where the key is drawn; it's smaller than [rect] for keys whose touch area is
 * stretched to the keyboard's edge (full mode's first and last home-row letters).
 */
internal class Key(
    val kind: Kind, val rect: RectF, val label: String = "", val hint: String? = null,
    val value: Int = 0, val style: Style = Style.PLAIN, val face: RectF = rect,
)

// ---- touch ----------------------------------------------------------------------------

/** Where a finger landed: on a [Key], mini mode's strip or letter row, or the emoji / GIF grid. */
internal enum class Area { KEY, STRIP, LETTERS, GRID, TREE, CURSOR }

/** One finger on the keyboard, from down to up. */
internal class Pointer(private val view: KeyboardView, val id: Int, val downX: Float, val downY: Float, val area: Area, val key: Key?) {
    var x = downX
    var y = downY
    /** When the finger landed (event time) and how far it has travelled along its path, in px. */
    val downTime = view.touchTime
    /** Wall-clock times for the pop-in animations of the key preview and long-press popup. */
    val downAt = SystemClock.uptimeMillis()
    var longPressAt = 0L
    var pathLen = 0f
    /** Travelled further than [slop]: no longer a tap or a long press. */
    var moved = false
    /** Held long enough to show the key's long-press character(s). */
    var longPressed = false
    /** Held long enough on mini mode's row to open the zoom. */
    var zooming = false
    /** Already handled (e.g. a long press acted); releasing it does nothing. */
    var consumed = false
    /** Selected index in a multi-character long-press popup. */
    var alt = 0
    /** Dragging the suggestion strip sideways or the emoji grid up and down (its fling: [flingSpeed]). */
    var scrolling = false
    var scrollStart = 0f
    val longPress = Runnable { view.onLongPress(this) }
    /** Holding on after the long press: a letter's German form joins the popup (see [GERMAN]). */
    val longerPress = Runnable { view.onLongerPress(this) }
    /** The long-press popup's characters once [longerPress] added the German letter, else null (the key's hint). */
    var options: String? = null
    /** Sliding the cursor ([Area.CURSOR]): where it was last moved a character ([cursorX]) or a line ([cursorY]). */
    var cursorX = downX
    var cursorY = downY
    /** In the cursor mode: 1 or 2 if this finger landed on the copy or the cut choice (a tap on it acts), else 0. */
    var button = 0
    /** Backspace auto-repeat while held. */
    var repeat: Runnable? = null

    /** A plain tap still in progress, which another finger landing may finish early (see [handleTouch]). */
    val flushable get() = area != Area.TREE && area != Area.CURSOR && !zooming && !moved && !longPressed &&
        (area != Area.KEY || key?.kind == Kind.LETTER || key?.kind == Kind.TEXT)
}

/**
 * The soft light column under a finger on mini mode's row: it fades in on touch, follows the
 * finger and fades out after the finger lifts ([releasedAt]), see [drawGlows].
 */
internal class Glow(val pointer: Pointer) {
    var x = pointer.x
    var releasedAt = 0L
}

/**
 * Mini zoom: five slots from [start] magnified; [row] 0 = symbols, 1 = letters. In number mode
 * ([numbers]) row 1 is the digit row instead, magnified around [digit].
 */
internal class Zoom(var start: Int, var slot: Int, var row: Int, val numbers: Boolean, var digit: Int = 0) {
    val openedAt = SystemClock.uptimeMillis()
    /**
     * Rested on a letter that has a German form ("ß" for s): it's offered in the symbol cell above
     * that letter ([germanSlot]); sliding up onto it and lifting types it. Lifting on the letter
     * still types the letter.
     */
    var german: String? = null
    var germanSlot = -1
}

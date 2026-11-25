// Sizes, times and tables shared by the keyboard view's files.
package com.brownllama.continuum.keyboard

import kotlin.math.exp

/** The pause in typing a visit starts in. */
internal const val ANIMAL_IDLE_MS = 1_500L
/** The strip always has at least this many slots, so a lone word still sits where it would among three. */
internal const val MIN_SLOTS = 3
/** The narrowest a suggestion slot gets, as a share of the strip (so up to six short words fit). */
internal const val MIN_SLOT_FRACTION = 1f / 6
internal const val EMOJI_COLUMNS = 8
internal const val GIF_COLUMNS = 3
/** Slowest GIF frame interval while typing a search (see [invalidateDrawable]). */
internal const val GIF_TYPING_FRAME_MS = 80L
internal const val CLIP_COLUMNS = 2
internal const val STICKER_COLUMNS = 4
/** Whether the emoji panel offers the sticker tab. Off for now, until the built-in stickers are redrawn. */
internal const val STICKERS = false
/** Lines of text shown on a clip card before it's cut off. */
internal const val CLIP_LINES = 3
internal const val CLIP_PREVIEW_CHARS = 300
/** The up-and-left swipe's sector, as across ÷ up: 0.4 is about 22° off vertical, 1.73 about 60° (see [gesture]). */
internal const val LANGUAGE_MIN_SLOPE = 0.4f
internal const val LANGUAGE_MAX_SLOPE = 1.73f
/** The language hint: grows in, the highlight slides, it stays a moment, and fades out. */
internal const val LANG_HINT_MS = 1_300L
internal const val LANG_IN_MS = 160L
internal const val LANG_SLIDE_DELAY_MS = 90L
internal const val LANG_SLIDE_MS = 260L
internal const val LANG_OUT_MS = 320L
/** Long-press choices on the period key. */
internal const val PERIOD_ALTS = ",!?"
/** Radius of the emoji / GIF panels' floating ✕. */
internal const val CLOSE_RADIUS_DP = 14f
/** Its gap to the panel's right edge and (smaller, so it sits low) to its bottom edge. */
internal const val CLOSE_SIDE_DP = 12f
internal const val CLOSE_BOTTOM_DP = 4f
/** Its opacity (0..255): 50%, so the GIFs, emoji and clips under it show through. */
internal const val CLOSE_ALPHA = 128
/** Height of the GIF strip in the search header. */
internal const val GIF_STRIP_DP = 92f
/** Shift and backspace are drawn smaller than other icons, to match the lowercase letters. */
internal const val SMALL_ICON = 0.7f
/** How long a popup takes to fade and scale in (see [popIn]). */
internal const val POP_MS = 110L
/** Shift's hold menu, left to right. */
internal const val MENU_SETTINGS = 0
internal const val MENU_KEYBOARDS = 1
internal val SHIFT_MENU = intArrayOf(MENU_SETTINGS, MENU_KEYBOARDS)
/** The finger glow: fade in / out times, peak opacity (0..255) and reach to each side, in letters. */
internal const val GLOW_IN_MS = 90L
internal const val GLOW_OUT_MS = 260L
internal const val GLOW_ALPHA = 46f
internal const val GLOW_HALF_SLOTS = 2.6f
/** A bell curve sampled across the glow, so it fades smoothly to nothing at its edges. */
internal val GLOW_POSITIONS = FloatArray(13) { it / 12f }
internal val GLOW_STOPS = FloatArray(13) { i ->
    val d = (i / 12f - 0.5f) * 2f
    ((exp(-3f * d * d) - exp(-3f)) / (1f - exp(-3f))).coerceAtLeast(0f)
}
/** How long new content takes to fade in after a panel or mode switch. */
internal const val FADE_MS = 160L
/** Switching between mini and full mode: a slightly longer fade, rising this far into place. */
internal const val MODE_FADE_MS = 260L
internal const val MODE_SLIDE_DP = 10f
/** Letters with a German form, offered when a held letter is held a little longer. */
internal val GERMAN = mapOf('a' to "ä", 'o' to "ö", 'u' to "ü", 's' to "ß")
/**
 * How long to keep holding (after the zoom or the hint popup is up) before the German letter
 * is offered: long enough to slide on to a number or symbol first without it getting in the way.
 */
internal const val GERMAN_WAIT_MS = 700L
/** Backspace: hold this long before it repeats, then repeat this often. */
internal const val REPEAT_DELAY_MS = 400L
internal const val REPEAT_MS = 55L

/** Sliding on the grass: the finger travels this far (dp) for each character the cursor moves. */
internal const val CURSOR_STRIDE_DP = 4f
/** Carrying the tree: one line up or down for this much finger travel (dp). */
internal const val CURSOR_LINE_DP = 26f
/** Kept clear above Android's gesture bar (dp), where its swipes begin: the tree stands above it. */
internal const val GESTURE_BAR_MARGIN_DP = 6f
/** A finger this near (dp) the tree still picks it up. */
internal const val TREE_SLACK_DP = 8f
/** How tall the tree is, in dp (it rises over the keys). */
internal const val TREE_HEIGHT_DP = 60f
/** The tree stands this far (dp) from the right edge, clear of Android's back swipe. */
internal const val TREE_RIGHT_GAP_DP = 30f
/** The tree stays this far (dp) from either edge of the keyboard wherever it is put down. */
internal const val TREE_EDGE_GAP_DP = 12f
/** Holding a finger on the tree over the keys this long picks it up; a quicker tap goes through to the key under it. */
internal const val TREE_HOLD_MS = 300L
/** Cursor moves made while the tree is carried are collected for this long (about a frame) and sent together. */
internal const val CURSOR_FLUSH_MS = 16L

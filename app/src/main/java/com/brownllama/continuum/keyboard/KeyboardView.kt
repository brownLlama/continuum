package com.brownllama.continuum.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.R
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.theme.KeyboardTheme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole keyboard surface: one custom-drawn View. What it shows depends on [mode] and [panel]:
 *
 * - Mini letters:  bar [shift | suggestions | ⌫], a symbol strip, then all 26 letters in one
 *                  staggered row, with "en" in the corner.
 * - Mini numbers:  the same bar, a symbol strip over a row of big digits "1 2 … 0 - / :".
 * - Full letters:  bar [autocorrect switch | suggestions | ✔], three letter rows with long-press
 *                  hints, then [123 | , | en (space) | . | enter].
 * - Full numbers:  the same bar over a 10-column symbol grid with a filled phone keypad.
 * - Emoji:         full-keyboard sized in either mode: group tabs (+ GIF, ⌫) in place of the bar,
 *                  over one grid that scrolls through "frequently used" and every group; ✕ closes it.
 * - Clipboard:     same size: bar ["Clipboard" | bin] over cards of recently copied text; tap one
 *                  to paste it, hold one to delete it.
 * - GIF:           same size: bar [☺ | search field] over a scrolling grid of GIFs from KLIPY.
 *                  Tapping the search field ([gifSearching]) brings back the normal keyboard (mini or
 *                  full, with its bar, numbers and gestures) under a header: [☺ | search field | ⌄]
 *                  over a sideways strip of the GIFs. The IME types into the query while it's open;
 *                  enter searches and goes back to the grid.
 *
 * Coordinates: the view starts with a transparent strip of [overlayH] px where popups (the zoom,
 * key previews) can draw above the keyboard, then the GIF search header ([gifHeaderH], 0 unless
 * searching). Touch events are shifted by both, so all layout and hit-testing below works in
 * "keyboard" coordinates where y = 0 is the top of the bar (the header has negative y).
 *
 * The look: one calm surface, flat keys, pill highlights, line icons, popups that fade and scale in.
 *
 * The view only holds presentation state (panels, scrolling, zoom, popups). Everything that
 * changes text goes through [Listener], which [ContinuumIME] implements.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    /** What the user did, in editing terms. */
    interface Listener {
        /** A touch on the letters, to be decoded into a word (see [Tap] for the coordinates). */
        fun onLetterTap(tap: Tap)
        /** Whether the llama may visit now (not in a password field…), see [Llama]. */
        fun llamaMayVisit(): Boolean
        /** Literal text to type: a digit, a symbol, or a long-press character. */
        fun onText(text: String)
        /** An emoji picked from the emoji panel. */
        fun onEmoji(emoji: String)
        fun onBackspace()
        /** Swipe left. */
        fun onDeleteWord()
        /** Space key or swipe right. */
        fun onSpace()
        /** Enter key or diagonal swipe up. */
        fun onEnter()
        fun onShift()
        /** Suggestion [slot] (an index into the list given to [setSuggestions]) was tapped. */
        fun onSuggestion(slot: Int)
        /** The user confirmed removing suggestion [slot] (long-press → Remove). */
        fun onForgetSuggestion(slot: Int)
        /** Switch between mini and full mode (swipe up in mini, down from the bar in full). */
        fun onModeToggle()
        /** Full mode's autocorrect switch in the bar. */
        fun onAutocorrectToggle()
        /** Long press on the space bar, or shift's menu: show the system keyboard picker. */
        fun onPickInputMethod()
        /** Shift held, then released on the menu's settings icon: open Continuum's settings. */
        fun onOpenSettings()
        /** Show GIFs for [query] (empty: trending) through [setGifs]. */
        fun onGifSearch(query: String)
        /** The GIF grid was scrolled near its end: append the next page, if any. */
        fun onGifMore()
        /** GIF [index] (in the order given to [setGifs]) was tapped. */
        fun onGifPicked(index: Int)
        /** The GIF search field was tapped: typing now edits the query (shown through [gifQuery]). */
        fun onGifSearchOpen()
        /** The search keyboard closed ([closeGifSearch]): typing goes to the app again. */
        fun onGifSearchClose()
        /** The clipboard panel opened: send the copied texts through [setClips]. */
        fun onClipboardOpen()
        /** Clip [index] (in the order given to [setClips]) was tapped: paste it. */
        fun onClipPicked(index: Int)
        /** Clip [index] was held: delete it. */
        fun onClipRemove(index: Int)
        /** The clipboard panel's bin: delete every clip. */
        fun onClipClear()
    }

    enum class Shift { OFF, ON, LOCK }

    /** What fills the keyboard below the bar. SYMBOLS / SYMBOLS2 are the two number pages. */
    enum class Panel {
        LETTERS, SYMBOLS, SYMBOLS2, EMOJI, GIF, CLIPBOARD;

        /** Emoji, GIF and clipboard panels are full-keyboard sized in either mode and replace the bar. */
        val big get() = this == EMOJI || this == GIF || this == CLIPBOARD
    }

    /** One GIF in the panel: [image] (usually animated) arrives once its preview has downloaded. */
    class GifTile(val aspect: Float) {
        var image: Drawable? = null
    }

    // ---- state set by the IME (mostly from settings) --------------------------------------

    var mode = Mode.MINI
        set(value) {
            if (field != value) markPanelChange()
            field = value
            glows.clear()
            if (panel == Panel.GIF) stopGifs()
            // A mode switch while searching GIFs keeps the search open, in the new mode.
            panel = Panel.LETTERS
            requestLayout()
            relayout()
        }
    var panel = Panel.LETTERS
        private set
    var theme = KeyboardTheme.NORD_DARK
        set(value) { field = value; invalidate() }
    /** Height multipliers from the settings sliders, one per mode. */
    var miniScale = 1f
        set(value) { field = value; requestLayout(); relayout() }
    var fullScale = 1f
        set(value) { field = value; requestLayout(); relayout() }
    private val scale get() = if (mode == Mode.MINI && !panel.big) miniScale else fullScale
    /** Suggestion bar height multiplier (settings slider), on top of the mode's scale. */
    var barScale = 1f
        set(value) { field = value; requestLayout(); relayout() }
    /**
     * Space between the keys and whatever system bar is under them (settings slider). From Android 15
     * the IME window is edge-to-edge, so the bars' own height is added separately ([systemBottom]).
     */
    var bottomSpaceDp = Prefs.BOTTOM_SPACE_DEFAULT.toFloat()
        set(value) { field = value; requestLayout() }
    /**
     * Height in px of the system bars still showing under the keyboard: the gesture handle or
     * navigation buttons, and Android 16's keyboard buttons unless "Hide system buttons" hid them.
     * The keys sit above it, so nothing overlaps them.
     */
    private var systemBottom = 0
    var layout = KeyLayout.QWERTZ
        set(value) { field = value; relayout() }
    /** Whether the emoji panel has the clipboard and GIF tabs (setting "Show GIFs"). */
    var showGifs = Prefs.SHOW_GIFS_DEFAULT
        set(value) {
            if (field == value) return
            field = value
            if (!value && panel == Panel.GIF) {
                closeGifSearch()
                showPanel(Panel.EMOJI)
            }
            relayout()
        }
    /** Swipe thresholds (settings sliders), see [isSwipe]. */
    var swipeMinDp = Prefs.SWIPE_MIN_DEFAULT.toFloat()
    var swipeLongDp = Prefs.SWIPE_LONG_DEFAULT.toFloat()
    var swipeSpeed = Prefs.SWIPE_SPEED_DEFAULT / 100f
    var swipeStraightness = Prefs.SWIPE_STRAIGHTNESS_DEFAULT / 100f
    /** How long a hold takes to open the zoom or a key's popup (settings slider). */
    var holdMs = Prefs.HOLD_MS_DEFAULT.toLong()
    var shift = Shift.OFF
        set(value) { field = value; invalidate() }
    /** State of full mode's bar switch. */
    var autocorrectOn = true
        set(value) { field = value; invalidate() }
    /** The field takes an email address: full mode's "," key becomes "@". */
    var emailField = false
        set(value) { if (field != value) { field = value; relayout() } }
    /** Setting "Llama visits": now and then a llama walks across the keys (see [Llama]). */
    var llamaVisits = Prefs.LLAMA_DEFAULT
        set(value) {
            field = value
            if (value) scheduleLlama() else endLlama(cancel = true)
        }
    /** The editor's action (EditorInfo.IME_ACTION_*), drawn on the enter key; NONE = plain enter arrow. */
    var enterAction = EditorInfo.IME_ACTION_NONE
        set(value) { field = value; invalidate() }

    /**
     * Suggestions, left to right; as many as fit (see [layoutSuggestions]) show at once and the rest
     * are reached by dragging the bar sideways. [highlightSlot] is the default pick, shown on a pill.
     */
    var suggestions: List<String> = listOf("", "", "")
        private set
    var highlightSlot = -1
        private set

    /** Replaces the bar's words (empty strings are blank slots) and scrolls it back to the start. */
    fun setSuggestions(slots: List<String>, highlight: Int) {
        suggestions = slots
        highlightSlot = highlight
        layoutSuggestions()
        barScroll = 0f
        scroller.forceFinished(true)
        confirmForget = -1
        invalidate()
    }

    /** Suggestion index awaiting "Remove from suggestions?" confirmation in the bar, or -1. */
    private var confirmForget = -1
    private val removeRect = RectF()
    private val cancelRect = RectF()

    /** Emoji panel content after "frequently used", tab by tab (see [Emoji]). */
    var emojiGroups: List<EmojiGroup> = emptyList()
        set(value) { field = value; if (panel == Panel.EMOJI) relayout() }
    /** The user's most used emoji. Read when the panel opens, so the grid doesn't reshuffle while picking. */
    var frequentEmoji: List<String> = Emoji.DEFAULT_FREQUENT
    private var frequentShown = frequentEmoji

    /**
     * Switches what's shown below the bar (letters, numbers or emoji). While searching GIFs, letters
     * and numbers keep the search open; the emoji panel closes it.
     */
    fun showPanel(p: Panel) {
        if (panel == p) return
        if (p.big) closeGifSearch()
        // The emoji and GIF panels are full-keyboard sized even in mini mode.
        val resize = p.big != panel.big
        markPanelChange()
        if (panel == Panel.GIF) stopGifs()
        panel = p
        if (!gifSearching) gridScroller.forceFinished(true)
        if (p == Panel.EMOJI) {
            frequentShown = frequentEmoji
            emojiScroll = 0f
        }
        if (resize) requestLayout()
        relayout()
        if (p == Panel.GIF && gifTiles.isEmpty()) listener.onGifSearch(gifQuery)
        if (p == Panel.CLIPBOARD) {
            clipScroll = 0f
            listener.onClipboardOpen()
        }
    }

    // ---- geometry -------------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    /** dp → px, scaled by the current mode's height slider. Used for everything that grows with the keyboard. */
    private fun dp(v: Float) = v * density * scale
    /** dp → px, unscaled. Used for fixed details like paddings, corner radii and line widths. */
    private fun dpu(v: Float) = v * density

    private val barH get() = dp(34f) * barScale
    private val stripH get() = dp(26f)
    private val miniRowH get() = dp(56f)
    private val fullRowH get() = dp(46f)
    private val pad get() = dpu(6f)

    /** Zoom popup size; it rises above the keyboard into [overlayH]. */
    private val zoomCellW get() = dp(30f)
    private val zoomSymH get() = dp(40f)
    private val zoomLetH get() = dp(62f)

    /**
     * Transparent space above the keyboard that popups can draw into. The IME reports only the
     * area below it as the keyboard (see ContinuumIME.onComputeInsets), so the app stays visible
     * and touchable there.
     */
    val overlayH: Float get() = max(0f, zoomSymH + zoomLetH - barH) + dpu(8f)

    /** Height of the keyboard itself (bar + body), without the overlay, the GIF search header or the space below it. */
    val keyboardHeight: Float get() = barH + bodyHeight()

    /** The GIF search header above the bar: [☺ | search field | ⌄] over a strip of GIFs. */
    private val gifStripH get() = dpu(GIF_STRIP_DP)
    val gifHeaderH: Float get() = if (gifSearching) barH + gifStripH else 0f

    private fun bodyHeight(): Float = when {
        mode == Mode.MINI && !panel.big -> stripH + miniRowH
        else -> fullRowH * 4
    }

    /** What a key does when tapped (see [keyTap]) and how it's drawn (see [drawKey]). */
    private enum class Kind {
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
        CLIP_TAB, // emoji panel → clipboard panel
        CLIP_CLEAR, // the clipboard panel's bin: delete every clip
    }

    /** How a TEXT key is drawn. */
    private enum class Style { PLAIN, STRIP, BIG, FILLED }

    /**
     * One touchable key. [hint] is shown small in the corner and typed on long-press; several
     * characters (",!?") open a popup to pick one. [value] is the group index for [Kind.EMOJI_TAB].
     * [face] is where the key is drawn; it's smaller than [rect] for keys whose touch area is
     * stretched to the keyboard's edge (full mode's first and last home-row letters).
     */
    private class Key(
        val kind: Kind, val rect: RectF, val label: String = "", val hint: String? = null,
        val value: Int = 0, val style: Style = Style.PLAIN, val face: RectF = rect,
    )

    // ---- layout (recomputed by relayout) ----------------------------------------------------

    /** Keys in the bar (y < barH) and below it; hit-tested separately. */
    private val barKeys = ArrayList<Key>()
    /** The GIF search header's keys (y < 0, see [gifHeaderH]). */
    private val headerKeys = ArrayList<Key>()
    private val suggestionArea = RectF()
    /** How far the suggestion strip is scrolled sideways, in px. */
    private var barScroll = 0f
    private val scroller = OverScroller(context)
    /**
     * Left edge of each suggestion slot along the strip (before scrolling), plus the end of the last
     * one; see [layoutSuggestions]. Slots are as wide as their word, so short words fit more per bar.
     */
    private var slotEdges = FloatArray(1)
    private val maxBarScroll get() = max(0f, slotEdges.last() - suggestionArea.width())

    /**
     * Sizes each slot to its word (with [MIN_SLOT_FRACTION] of the bar as the least), then widens
     * the slots that fit in the bar so they fill it exactly; the rest continue past the right edge.
     */
    private fun layoutSuggestions() {
        val area = suggestionArea.width()
        val n = max(suggestions.indexOfLast { it.isNotEmpty() } + 1, MIN_SLOTS)
        if (area <= 0f) {
            slotEdges = FloatArray(n + 1)
            return
        }
        text.typeface = regular
        text.textSize = suggestionTextSize
        val minW = area * MIN_SLOT_FRACTION
        val widths = FloatArray(n) { i ->
            val s = suggestions.getOrNull(i).orEmpty()
            if (s.isEmpty()) minW else (text.measureText(s) + suggestionPad * 2).coerceIn(minW, area)
        }
        var fit = 0
        var used = 0f
        while (fit < n && used + widths[fit] <= area + 0.5f) used += widths[fit++]
        if (fit == 0) fit = 1.also { used = widths[0] }
        val extra = (area - used) / fit
        if (extra > 0f) for (i in 0 until fit) widths[i] += extra
        slotEdges = FloatArray(n + 1).also { e -> for (i in 0 until n) e[i + 1] = e[i] + widths[i] }
    }

    private val suggestionTextSize get() = barH * 0.46f
    private val suggestionPad get() = barH * 0.45f

    /** The suggestion slot under view x, or -1. */
    private fun suggestionAt(x: Float): Int {
        val sx = x - suggestionArea.left + barScroll
        for (i in 0 until slotEdges.size - 1) if (sx >= slotEdges[i] && sx < slotEdges[i + 1]) return i
        return -1
    }
    private val keys = ArrayList<Key>()
    /** Mini mode's symbol strip and letter row. Both are divided into 26 equal "slots". */
    private val stripRect = RectF()
    private val letterRect = RectF()
    /** Emoji panel: "frequently used" + [emojiGroups], each starting a new grid row ([emojiSectionRow]). */
    private var emojiSections: List<EmojiGroup> = emptyList()
    private var emojiSectionRow = IntArray(0)
    private var emojiRows = 0
    private val emojiRect = RectF()
    private var emojiCellW = 1f
    private var emojiRowH = 1f
    private var emojiScroll = 0f
    /** Flings the emoji or the GIF grid, whichever is showing (see [gridScroll]). */
    private val gridScroller = OverScroller(context)
    private val maxEmojiScroll get() = max(0f, emojiRows * emojiRowH + dpu(56f) - emojiRect.height())

    // GIF panel. The IME fills [gifTiles] through setGifs; the view only shows and scrolls them.
    private val gifTiles = ArrayList<GifTile>()
    /** Shown in place of the grid when there are no tiles (loading, nothing found, no key, error). */
    private var gifMessage: String? = null
    /** The search text, as the IME's query editor holds it (kept when going back to the grid). */
    var gifQuery = ""
        set(value) { field = value; invalidate() }
    /** The keyboard is showing under the search header (see [openGifSearch]). */
    var gifSearching = false
        private set
    /** GIFs are on screen: the GIF panel, or the search header's strip. */
    private val showsGifs get() = panel == Panel.GIF || gifSearching
    private var gifScroll = 0f
    private val gifRect = RectF()
    private var gifCellW = 1f
    private var gifCellH = 1f
    /** Asked the IME for the next page and waiting for it (cleared by setGifs). */
    private var gifMoreRequested = false
    private val gifGap get() = dpu(4f)
    private val gifRows get() = (gifTiles.size + GIF_COLUMNS - 1) / GIF_COLUMNS
    /** While searching, the GIFs sit in one row that scrolls sideways; [gifScroll] is then horizontal. */
    private val stripCellH get() = gifStripH - 2 * gifGap
    private val stripCellW get() = stripCellH * 1.3f
    private val maxGifScroll get() =
        if (gifSearching) max(0f, 2 * pad + gifTiles.size * (stripCellW + gifGap) - gifGap - gifRect.width())
        else max(0f, gifRows * (gifCellH + gifGap) + dpu(56f) - gifRect.height())

    // Clipboard panel. The IME fills [clips] through setClips; the view only shows and scrolls them.
    private var clips: List<String> = emptyList()
    private var clipScroll = 0f
    private val clipRect = RectF()
    private var clipCellW = 1f
    private val clipCellH get() = dpu(78f)
    private val clipRows get() = (clips.size + CLIP_COLUMNS - 1) / CLIP_COLUMNS
    private val maxClipScroll get() = max(0f, clipRows * (clipCellH + gifGap) + dpu(56f) - clipRect.height())
    /** Laid-out card text, per clip; rebuilt when the clips or the card width change. */
    private val clipLayouts = HashMap<String, StaticLayout>()
    private val clipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** The copied texts to show, newest first. */
    fun setClips(texts: List<String>) {
        clips = texts
        clipLayouts.clear()
        clipScroll = clipScroll.coerceIn(0f, maxClipScroll)
        invalidate()
    }

    /** The scroll position of whichever grid is showing (emoji, GIF or clipboard). */
    private var gridScroll: Float
        get() = when {
            showsGifs -> gifScroll
            panel == Panel.CLIPBOARD -> clipScroll
            else -> emojiScroll
        }
        set(value) {
            when {
                showsGifs -> gifScroll = value
                panel == Panel.CLIPBOARD -> clipScroll = value
                else -> emojiScroll = value
            }
            if (showsGifs) maybeLoadMoreGifs()
        }
    private val maxGridScroll get() = when {
        showsGifs -> maxGifScroll
        panel == Panel.CLIPBOARD -> maxClipScroll
        else -> maxEmojiScroll
    }

    /** New GIF results ([append] adds a page to the current ones). [message] shows when there are none. */
    fun setGifs(tiles: List<GifTile>, message: String?, append: Boolean) {
        if (!append) {
            stopGifs()
            gifTiles.clear()
            gifScroll = 0f
            gridScroller.forceFinished(true)
        }
        gifTiles += tiles
        gifMessage = message
        gifMoreRequested = false
        invalidate()
    }

    /** A tile's preview has loaded; animated previews redraw the view through [invalidateDrawable]. */
    fun setGifImage(tile: GifTile, image: Drawable) {
        if (tile !in gifTiles) return
        tile.image = image
        image.callback = this
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || showsGifs && gifTiles.any { it.image === who }

    /**
     * Each GIF frame redraws the whole view. While the search keyboard is up that competes with
     * typing, so frames are then drawn at most every [GIF_TYPING_FRAME_MS].
     */
    override fun invalidateDrawable(drawable: Drawable) {
        if (!verifyDrawable(drawable)) return super.invalidateDrawable(drawable)
        if (!gifSearching) return invalidate()
        if (gifFramePending) return
        gifFramePending = true
        val wait = GIF_TYPING_FRAME_MS - (SystemClock.uptimeMillis() - lastDrawAt)
        if (wait <= 0) postOnAnimation(gifFrame) else postDelayed(gifFrame, wait)
    }
    private var gifFramePending = false
    private val gifFrame = Runnable { gifFramePending = false; invalidate() }
    /** When [onDraw] last ran, for [invalidateDrawable]. */
    private var lastDrawAt = 0L

    /**
     * Drops the GIF images to give memory back, unless the GIF panel is showing. Opening the panel
     * again searches anew ([showPanel]).
     */
    fun releaseGifs() {
        if (panel == Panel.GIF) return
        stopGifs()
        for (t in gifTiles) t.image?.callback = null
        gifTiles.clear()
        gifScroll = 0f
        gifMessage = null
        gifMoreRequested = false
    }

    /** Pauses every GIF (they animate only while visible, see [drawGifGrid]). */
    private fun stopGifs() {
        for (t in gifTiles) (t.image as? Animatable)?.takeIf { it.isRunning }?.stop()
    }

    private fun maybeLoadMoreGifs() {
        val ahead = if (gifSearching) stripCellW * 2 else gifCellH * 1.5f
        if (gifMoreRequested || gifTiles.isEmpty() || gifScroll < maxGifScroll - ahead) return
        gifMoreRequested = true
        listener.onGifMore()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom
        } else {
            0
        }
        if (bottom != systemBottom) {
            systemBottom = bottom
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = overlayH + gifHeaderH + keyboardHeight + dpu(bottomSpaceDp) + systemBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    /** Rebuilds every key rectangle for the current mode, panel, size and settings. */
    private fun relayout() {
        barKeys.clear()
        headerKeys.clear()
        keys.clear()
        stripRect.setEmpty()
        letterRect.setEmpty()
        val w = width.toFloat()
        if (w <= 0f) return
        val top = barH
        // Buttons of the "Remove from suggestions?" question that covers the bar.
        removeRect.set(w * 0.58f, 0f, w * 0.80f, top)
        cancelRect.set(w * 0.80f, 0f, w, top)
        if (panel == Panel.EMOJI) {
            layoutEmoji(w, top)
            invalidate()
            return
        }
        if (panel == Panel.GIF) {
            layoutGif(w, top)
            invalidate()
            return
        }
        if (panel == Panel.CLIPBOARD) {
            layoutClipboard(w, top)
            invalidate()
            return
        }
        if (gifSearching) layoutGifHeader(w)
        if (mode == Mode.MINI) {
            barKeys += Key(Kind.SHIFT, RectF(0f, 0f, w * 0.12f, top))
            addSuggestionKeys(w * 0.12f, w * 0.88f)
            barKeys += Key(Kind.BACKSPACE, RectF(w * 0.88f, 0f, w, top))
        } else {
            barKeys += Key(Kind.AUTOCORRECT, RectF(0f, 0f, w * 0.12f, top))
            // No ✔ (the back gesture closes the keyboard): the suggestions get the room.
            addSuggestionKeys(w * 0.12f, w)
        }
        // Mini rows share the slot geometry so strip characters sit above their letters.
        stripRect.set(pad, top, w - pad, top + stripH)
        letterRect.set(pad, stripRect.bottom, w - pad, stripRect.bottom + miniRowH)
        when {
            mode == Mode.MINI && panel == Panel.LETTERS -> Unit // the row is drawn and hit-tested by slot, not as keys
            mode == Mode.MINI -> layoutMiniNumbers(w)
            panel == Panel.LETTERS -> layoutFull(w, top)
            else -> layoutFullNumbers(w, top, if (panel == Panel.SYMBOLS) Layouts.FULL_NUMBERS_1 else Layouts.FULL_NUMBERS_2)
        }
        invalidate()
    }

    private fun addSuggestionKeys(l: Float, r: Float) {
        suggestionArea.set(l, 0f, r, barH)
        barKeys += Key(Kind.SUGGESTION, RectF(suggestionArea))
        layoutSuggestions()
    }

    /** Mini number mode (swipe down to toggle): symbol strip over a big digit row, same footprint as letters. */
    private fun layoutMiniNumbers(w: Float) {
        for (i in 0 until 26) {
            keys += Key(
                Kind.TEXT, RectF(slotLeft(i), stripRect.top, slotLeft(i + 1), stripRect.bottom),
                Layouts.MINI_NUMBER_STRIP[i], style = Style.STRIP,
            )
        }
        val row = Layouts.MINI_NUMBER_ROW
        val cw = letterRect.width() / row.size
        row.forEachIndexed { i, s ->
            val l = letterRect.left + i * cw
            keys += Key(Kind.TEXT, RectF(l, letterRect.top, l + cw, letterRect.bottom), s, style = Style.BIG)
        }
    }

    private fun letterKey(c: Char, r: RectF, face: RectF = r) = Key(Kind.LETTER, r, c.toString(), layout.fullHints[c], face = face)

    /**
     * Full mode letters: 10 key widths per row; the home row is offset by half a key, like a real
     * keyboard. Its outer keys also take the half key left over at each edge, but are drawn one key
     * wide, so their letters line up with the rest of the row.
     */
    private fun layoutFull(w: Float, top: Float) {
        val kw = w / 10f
        val rh = fullRowH
        layout.top.forEachIndexed { i, c -> keys += letterKey(c, RectF(i * kw, top, (i + 1) * kw, top + rh)) }
        layout.mid.forEachIndexed { i, c ->
            val face = RectF((i + 0.5f) * kw, top + rh, (i + 1.5f) * kw, top + 2 * rh)
            val l = if (i == 0) 0f else face.left
            val r = if (i == layout.mid.lastIndex) w else face.right
            keys += letterKey(c, RectF(l, face.top, r, face.bottom), face)
        }
        val y2 = top + 2 * rh
        keys += Key(Kind.SHIFT, RectF(0f, y2, 1.5f * kw, y2 + rh))
        layout.bot.forEachIndexed { i, c ->
            keys += letterKey(c, RectF((i + 1.5f) * kw, y2, (i + 2.5f) * kw, y2 + rh))
        }
        keys += Key(Kind.BACKSPACE, RectF(8.5f * kw, y2, w, y2 + rh))
        bottomRow(w, top + 3 * rh, Kind.SYMBOLS, "123")
    }

    /** Full mode number page from one of the [Layouts] grids; digits get filled keys like a phone keypad. */
    private fun layoutFullNumbers(w: Float, top: Float, rows: List<List<String>>) {
        val kw = w / 10f
        val rh = fullRowH
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, s ->
                val rect = RectF(i * kw, top + r * rh, (i + 1) * kw, top + (r + 1) * rh)
                keys += when {
                    s == Layouts.MORE -> Key(Kind.MORE, rect, if (panel == Panel.SYMBOLS) "}\\<" else "123", style = Style.FILLED)
                    s == Layouts.BACKSPACE -> Key(Kind.BACKSPACE, rect)
                    s.length == 1 && s[0].isDigit() -> Key(Kind.TEXT, rect, s, style = Style.FILLED)
                    else -> Key(Kind.TEXT, rect, s)
                }
            }
        }
        bottomRow(w, top + 3 * rh, Kind.ABC, "")
    }

    /** Full mode's bottom row, shared by the letter and number pages. */
    private fun bottomRow(w: Float, y: Float, first: Kind, firstLabel: String) {
        val kw = w / 10f
        val y1 = y + fullRowH
        keys += Key(first, RectF(0f, y, 1.5f * kw, y1), firstLabel)
        keys += Key(Kind.TEXT, RectF(1.5f * kw, y, 2.5f * kw, y1), if (emailField) "@" else ",", Layouts.EMOJI_SLOT)
        keys += Key(Kind.SPACE, RectF(2.5f * kw, y, 7.5f * kw, y1))
        keys += Key(Kind.TEXT, RectF(7.5f * kw, y, 8.5f * kw, y1), ".", PERIOD_ALTS)
        keys += Key(Kind.ENTER, RectF(8.5f * kw, y, w, y1))
    }

    /**
     * Emoji panel: one tab per section plus ⌫ in the bar, and the grid geometry. Sections are laid
     * out one after another, each starting on a new row ([emojiSectionRow] = its first row).
     */
    private fun layoutEmoji(w: Float, top: Float) {
        emojiSections = listOf(EmojiGroup(Emoji.FREQUENT, frequentShown)) + emojiGroups
        val extra = if (showGifs) 3 else 1 // clipboard, GIF and ⌫ tabs, or just ⌫
        val tabW = w / (emojiSections.size + extra)
        emojiSections.indices.forEach { i ->
            barKeys += Key(Kind.EMOJI_TAB, RectF(i * tabW, 0f, (i + 1) * tabW, top), value = i)
        }
        if (showGifs) {
            barKeys += Key(Kind.CLIP_TAB, RectF(w - 3 * tabW, 0f, w - 2 * tabW, top))
            barKeys += Key(Kind.GIF_TAB, RectF(w - 2 * tabW, 0f, w - tabW, top))
        }
        barKeys += Key(Kind.BACKSPACE, RectF(w - tabW, 0f, w, top))
        emojiRect.set(0f, top, w, top + bodyHeight())
        emojiCellW = w / EMOJI_COLUMNS
        emojiRowH = emojiCellW * 0.9f
        var rows = 0
        emojiSectionRow = IntArray(emojiSections.size) { i ->
            rows.also { rows += (emojiSections[i].emoji.size + EMOJI_COLUMNS - 1) / EMOJI_COLUMNS }
        }
        emojiRows = rows
        emojiScroll = emojiScroll.coerceIn(0f, maxEmojiScroll)
        keys += closeKey(w, emojiRect.bottom)
    }

    /** GIF panel: [☺ | search field] in the bar, then the GIF grid with a floating ✕. */
    private fun layoutGif(w: Float, top: Float) {
        barKeys += Key(Kind.GIF_BACK, RectF(0f, 0f, w * 0.12f, top))
        barKeys += Key(Kind.GIF_SEARCH, RectF(w * 0.12f, 0f, w, top))
        gifRect.set(0f, top, w, top + bodyHeight())
        gifCellW = (w - 2 * pad - (GIF_COLUMNS - 1) * gifGap) / GIF_COLUMNS
        gifCellH = gifCellW * 0.8f
        gifScroll = gifScroll.coerceIn(0f, maxGifScroll)
        keys += closeKey(w, gifRect.bottom)
    }

    /** The emoji / GIF / clipboard panels' floating ✕, in the bottom-right corner above [bottom]. */
    private fun closeKey(w: Float, bottom: Float): Key {
        val d = 2 * dpu(CLOSE_RADIUS_DP)
        val right = w - dpu(CLOSE_SIDE_DP)
        val low = bottom - dpu(CLOSE_BOTTOM_DP)
        return Key(Kind.CLOSE, RectF(right - d, low - d, right, low))
    }

    /** Clipboard panel: [title | bin] in the bar, then two columns of cards with a floating ✕. */
    private fun layoutClipboard(w: Float, top: Float) {
        barKeys += Key(Kind.CLIP_CLEAR, RectF(w * 0.86f, 0f, w, top))
        clipRect.set(0f, top, w, top + bodyHeight())
        val cellW = (w - 2 * pad - (CLIP_COLUMNS - 1) * gifGap) / CLIP_COLUMNS
        if (cellW != clipCellW) clipLayouts.clear()
        clipCellW = cellW
        clipScroll = clipScroll.coerceIn(0f, maxClipScroll)
        keys += closeKey(w, clipRect.bottom)
    }

    /** Where clip card [i] is on screen (scrolled), into [out]. */
    private fun clipCardRect(i: Int, out: RectF) {
        val l = pad + (i % CLIP_COLUMNS) * (clipCellW + gifGap)
        val t = clipRect.top + (i / CLIP_COLUMNS) * (clipCellH + gifGap) + gifGap - clipScroll
        out.set(l, t, l + clipCellW, t + clipCellH)
    }

    /** Index of the clip card under (x, y), or null. */
    private fun clipAt(x: Float, y: Float): Int? {
        if (!clipRect.contains(x, y)) return null
        val r = RectF()
        return clips.indices.firstOrNull { clipCardRect(it, r); r.contains(x, y) }
    }

    /** The search header above the keyboard's bar: [☺ | search field | ⌄], then the strip of GIFs. */
    private fun layoutGifHeader(w: Float) {
        val top = -gifHeaderH
        val bottom = top + barH
        headerKeys += Key(Kind.GIF_BACK, RectF(0f, top, w * 0.12f, bottom))
        headerKeys += Key(Kind.GIF_SEARCH, RectF(w * 0.12f, top, w * 0.88f, bottom))
        headerKeys += Key(Kind.GIF_CANCEL, RectF(w * 0.88f, top, w, bottom))
        gifRect.set(0f, -gifStripH, w, 0f)
        gifScroll = gifScroll.coerceIn(0f, maxGifScroll)
    }

    /**
     * Where GIF tile [i] is on screen (scrolled), into [out]: a cell of the grid, or of the search
     * header's single row.
     */
    private fun gifTileRect(i: Int, out: RectF) {
        if (gifSearching) {
            val l = gifRect.left + pad + i * (stripCellW + gifGap) - gifScroll
            out.set(l, gifRect.top + gifGap, l + stripCellW, gifRect.top + gifGap + stripCellH)
        } else {
            val l = pad + (i % GIF_COLUMNS) * (gifCellW + gifGap)
            val t = gifRect.top + (i / GIF_COLUMNS) * (gifCellH + gifGap) + gifGap - gifScroll
            out.set(l, t, l + gifCellW, t + gifCellH)
        }
    }

    /** Index of the GIF tile under (x, y), or null. */
    private fun gifAt(x: Float, y: Float): Int? {
        if (!gifRect.contains(x, y)) return null
        val r = RectF()
        return gifTiles.indices.firstOrNull { gifTileRect(it, r); r.contains(x, y) }
    }

    /** Grid section (tab) at the top of the visible grid; the last one once scrolled to the end. */
    private fun activeEmojiSection(): Int {
        if (emojiScroll >= maxEmojiScroll - 1f && emojiScroll > 0f) return emojiSections.lastIndex
        val row = floor((emojiScroll + 1f) / emojiRowH).toInt()
        return emojiSectionRow.indexOfLast { it <= row }.coerceAtLeast(0)
    }

    /** The emoji under (x, y), with y in view coordinates. */
    private fun emojiAt(x: Float, y: Float): String? {
        if (!emojiRect.contains(x, y)) return null
        val row = floor((y - emojiRect.top + emojiScroll) / emojiRowH).toInt()
        val sec = emojiSectionRow.indexOfLast { it <= row }.takeIf { it >= 0 } ?: return null
        val col = (x / emojiCellW).toInt().coerceIn(0, EMOJI_COLUMNS - 1)
        return emojiSections[sec].emoji.getOrNull((row - emojiSectionRow[sec]) * EMOJI_COLUMNS + col)
    }

    // Mini mode slots: slot i holds the i-th letter of KeyLayout.miniOrder and the i-th strip symbol.
    private val slotW get() = letterRect.width() / 26f
    private fun slotLeft(i: Int) = letterRect.left + i * slotW
    private fun slotAt(x: Float) = (x - letterRect.left) / slotW - 0.5f
    private fun nearestSlot(x: Float) = slotAt(x).roundToInt().coerceIn(0, 25)
    private fun slotCenter(i: Int) = letterRect.left + (i + 0.5f) * slotW

    // ---- touch ----------------------------------------------------------------------------

    /** Where a finger landed: on a [Key], mini mode's strip or letter row, or the emoji / GIF grid. */
    private enum class Area { KEY, STRIP, LETTERS, GRID }

    /** One finger on the keyboard, from down to up. */
    private inner class Pointer(val id: Int, val downX: Float, val downY: Float, val area: Area, val key: Key?) {
        var x = downX
        var y = downY
        /** When the finger landed (event time) and how far it has travelled along its path, in px. */
        val downTime = touchTime
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
        /** Dragging the suggestion strip sideways or the emoji grid up and down; vx / vy feed the fling. */
        var scrolling = false
        var scrollStart = 0f
        var lastX = downX
        var lastY = downY
        var lastT = SystemClock.uptimeMillis()
        var vx = 0f
        var vy = 0f
        val longPress = Runnable { onLongPress(this) }
        /** Backspace auto-repeat while held. */
        var repeat: Runnable? = null

        /** A plain tap still in progress, which another finger landing may finish early (see [handleTouch]). */
        val flushable get() = !zooming && !moved && !longPressed &&
            (area != Area.KEY || key?.kind == Kind.LETTER || key?.kind == Kind.TEXT)
    }

    private val pointers = LinkedHashMap<Int, Pointer>()

    /**
     * The soft light column under a finger on mini mode's row: it fades in on touch, follows the
     * finger and fades out after the finger lifts ([releasedAt]), see [drawGlows].
     */
    private inner class Glow(val pointer: Pointer) {
        var x = pointer.x
        var releasedAt = 0L
    }
    private val glows = ArrayList<Glow>()
    private val timer = Handler(Looper.getMainLooper())
    private val slop get() = dpu(14f)
    /** Event time of the touch event being handled. */
    private var touchTime = 0L

    /**
     * Swipe or tap? A swipe is a fairly straight stroke that is either quick and at least
     * [swipeMinDp] long, or long at any speed ([swipeLongDp]). A tap that lands and rolls or
     * drags a little is slow and short, and a wobbly path is a sloppy tap, not a swipe.
     */
    private fun isSwipe(p: Pointer): Boolean {
        val distPx = hypot(p.x - p.downX, p.y - p.downY)
        val dist = distPx / density
        if (dist < swipeMinDp) return false
        if (p.pathLen > 0f && distPx / p.pathLen < swipeStraightness) return false
        val ms = max(1L, touchTime - p.downTime)
        return dist / ms >= swipeSpeed || dist >= swipeLongDp
    }

    /**
     * Mini zoom: five slots from [start] magnified; [row] 0 = symbols, 1 = letters. In number mode
     * ([numbers]) row 1 is the digit row instead, magnified around [digit].
     */
    private class Zoom(var start: Int, var slot: Int, var row: Int, val numbers: Boolean, var digit: Int = 0) {
        val openedAt = SystemClock.uptimeMillis()
    }

    private var zoom: Zoom? = null

    /** Shifts touches into keyboard coordinates (y = 0 at the top of the bar), see the class comment. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = MotionEvent.obtain(event)
        e.offsetLocation(0f, -overlayH - gifHeaderH)
        handleTouch(e)
        e.recycle()
        return true
    }

    private fun handleTouch(e: MotionEvent) {
        touchTime = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // The llama walks on (touches go to the keys); a visit only starts in a pause in typing.
                lastTouchAt = e.eventTime
                scheduleLlama()
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
                }
                pointers.clear()
                zoom = null
                invalidate()
            }
        }
    }

    /** The key under (x, y), or the nearest one so a touch just between keys still counts. */
    private fun hitKey(list: List<Key>, x: Float, y: Float): Key? =
        list.firstOrNull { it.rect.contains(x, y) }
            ?: list.minByOrNull { hypot(it.rect.centerX() - x, it.rect.centerY() - y) }

    /** A finger landed: work out what it's on and start the long-press / backspace-repeat timers. */
    private fun down(id: Int, x: Float, y: Float) {
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
        val p = when {
            // The GIF search header: its keys, then the strip of GIFs.
            y < -gifStripH -> hitKey(headerKeys, x, y)?.let { Pointer(id, x, y, Area.KEY, it) }
            y < 0f -> Pointer(id, x, y, Area.GRID, null)
            y < barH -> hitKey(barKeys, x, y)?.let { Pointer(id, x, y, Area.KEY, it) }
            letters && y < stripRect.bottom -> Pointer(id, x, y, Area.STRIP, null)
            // Below the row (the gap above the screen edge) still counts as the row.
            letters -> Pointer(id, x, y, Area.LETTERS, null)
            // Emoji / GIF grid: the floating ✕ is a key, the rest scrolls or picks.
            panel.big -> keys.firstOrNull { it.rect.contains(x, y) }?.let { Pointer(id, x, y, Area.KEY, it) }
                ?: Pointer(id, x, y, Area.GRID, null)
            else -> hitKey(keys, x, y)?.let { Pointer(id, x, y, Area.KEY, it) }
        } ?: return
        pointers[id] = p
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
        } else if (p.area != Area.GRID || panel == Panel.CLIPBOARD) {
            // Clip cards can be held (to delete them); emoji and GIFs can't.
            timer.postDelayed(p.longPress, holdMs)
        }
        invalidate()
    }

    /** A finger moved: scroll, update the zoom or popup selection, or note that it's no longer a tap. */
    private fun move(p: Pointer, x: Float, y: Float) {
        p.pathLen += hypot(x - p.x, y - p.y)
        p.x = x
        p.y = y
        val k = p.key
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
        } else if (p.longPressed && k?.hint != null && k.hint.length > 1) {
            val (left, cellW) = altPopup(k)
            val a = ((x - left) / cellW).toInt().coerceIn(0, k.hint.length - 1)
            p.alt = a
        } else if (!p.moved && hypot(x - p.downX, y - p.downY) > slop) {
            p.moved = true
            if (!p.longPressed) timer.removeCallbacks(p.longPress)
        }
        invalidate()
    }

    /** The hold timer ([holdMs]) fired while the finger is still down and hasn't moved. */
    private fun onLongPress(p: Pointer) {
        if (pointers[p.id] !== p || p.moved) return
        when (p.area) {
            Area.STRIP, Area.LETTERS -> startZoom(p)
            Area.GRID -> if (panel == Panel.CLIPBOARD) {
                clipAt(p.downX, p.downY)?.let {
                    p.consumed = true
                    listener.onClipRemove(it)
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
                } else if (zoomsInNumbers(p.key)) {
                    startZoom(p)
                }
                else -> {}
            }
        }
        invalidate()
    }

    /** A finger lifted: finish a scroll or zoom, or decide between a swipe gesture and a tap. */
    private fun release(p: Pointer) {
        pointers.remove(p.id)
        timer.removeCallbacks(p.longPress)
        p.repeat?.let(timer::removeCallbacks)
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
    }

    /**
     * Swipe shortcuts.
     * Horizontal (not from the bar): left deletes a word, right types a space.
     * Straight up: mini → full mode. Straight down: text ↔ numbers, except a swipe down that
     * starts on the suggestion bar in full mode, which goes back to mini mode.
     * Diagonal up (left or right): enter.
     */
    private fun gesture(dx: Float, dy: Float, fromBar: Boolean) {
        val ax = abs(dx)
        val ay = abs(dy)
        when {
            ax >= ay * 1.2f -> if (!fromBar) {
                if (dx < 0) listener.onDeleteWord() else listener.onSpace()
            }
            dy > 0 -> if (mode == Mode.FULL && fromBar) listener.onModeToggle() else toggleNumbers()
            ax < ay * 0.6f -> if (mode == Mode.MINI) listener.onModeToggle()
            else -> listener.onEnter()
        }
    }

    private fun toggleNumbers() = showPanel(if (panel == Panel.LETTERS) Panel.SYMBOLS else Panel.LETTERS)

    /** A finger lifted without swiping. */
    private fun tap(p: Pointer) {
        when (p.area) {
            // A quick tap on the strip is a letter tap too: symbols are only typed via the hold popup.
            Area.LETTERS, Area.STRIP -> listener.onLetterTap(Tap(slotAt(p.downX).coerceIn(-0.5f, 25.5f), 0f))
            Area.GRID -> if (!p.moved) {
                when {
                    showsGifs -> gifAt(p.downX, p.downY)?.let(listener::onGifPicked)
                    panel == Panel.CLIPBOARD -> clipAt(p.downX, p.downY)?.let(listener::onClipPicked)
                    else -> emojiAt(p.downX, p.downY)?.let(listener::onEmoji)
                }
            }
            Area.KEY -> keyTap(p.key!!, p)
        }
    }

    /** Types [s], or opens the emoji panel for the smiley and the clipboard panel for its icon. */
    private fun text(s: String) {
        when (s) {
            Layouts.EMOJI_SLOT -> showPanel(Panel.EMOJI)
            Layouts.CLIPBOARD_SLOT -> showPanel(Panel.CLIPBOARD)
            else -> listener.onText(s)
        }
    }

    private fun backspace() = listener.onBackspace()

    /** A tap (or a released long press, see [Pointer.longPressed]) on key [k]. */
    private fun keyTap(k: Key, p: Pointer) {
        val hint = k.hint
        when (k.kind) {
            Kind.LETTER -> when {
                p.longPressed && hint != null -> listener.onText(hint)
                else -> listener.onLetterTap(Tap(p.downX / (width / 10f), (p.downY - barH) / fullRowH))
            }
            Kind.TEXT -> text(
                when {
                    !p.longPressed || hint == null -> k.label
                    hint == Layouts.EMOJI_SLOT -> hint
                    else -> hint[p.alt.coerceIn(0, hint.length - 1)].toString()
                },
            )
            Kind.SHIFT -> when {
                !p.longPressed -> listener.onShift()
                else -> when (SHIFT_MENU.getOrNull(shiftMenuChoice(k, p))) {
                    MENU_SETTINGS -> listener.onOpenSettings()
                    MENU_KEYBOARDS -> listener.onPickInputMethod()
                }
            }
            Kind.BACKSPACE -> {} // handled on touch down, with auto-repeat
            Kind.SPACE -> listener.onSpace()
            Kind.ENTER -> listener.onEnter()
            Kind.SYMBOLS -> showPanel(Panel.SYMBOLS)
            Kind.MORE -> showPanel(if (panel == Panel.SYMBOLS) Panel.SYMBOLS2 else Panel.SYMBOLS)
            Kind.ABC -> showPanel(Panel.LETTERS)
            Kind.AUTOCORRECT -> listener.onAutocorrectToggle()
            Kind.SUGGESTION -> {
                val i = suggestionAt(p.downX)
                if (suggestions.getOrNull(i).orEmpty().isNotEmpty()) listener.onSuggestion(i)
            }
            Kind.EMOJI_TAB -> {
                gridScroller.forceFinished(true)
                emojiScroll = (emojiSectionRow[k.value] * emojiRowH).coerceIn(0f, maxEmojiScroll)
            }
            Kind.CLOSE -> showPanel(Panel.LETTERS)
            Kind.GIF_TAB -> showPanel(Panel.GIF)
            Kind.GIF_BACK -> showPanel(Panel.EMOJI)
            Kind.GIF_SEARCH -> openGifSearch()
            Kind.GIF_CANCEL -> closeGifSearch()
            Kind.CLIP_TAB -> showPanel(Panel.CLIPBOARD)
            Kind.CLIP_CLEAR -> if (clips.isNotEmpty()) listener.onClipClear()
        }
    }

    /**
     * The search field was tapped: the keyboard (letters, in the current mode) comes back under the
     * search header, and the GIFs move into the header's strip.
     */
    private fun openGifSearch() {
        if (gifSearching) return
        markPanelChange()
        gifSearching = true
        panel = Panel.LETTERS
        gifScroll = 0f
        gridScroller.forceFinished(true)
        requestLayout()
        relayout()
        listener.onGifSearchOpen()
    }

    /** Leaves the search keyboard for the GIF grid (enter, ⌄, ☺), or does nothing when not searching. */
    fun closeGifSearch() {
        if (!gifSearching) return
        markPanelChange()
        gifSearching = false
        panel = Panel.GIF
        gifScroll = 0f
        gridScroller.forceFinished(true)
        zoom = null
        requestLayout()
        relayout()
        listener.onGifSearchClose()
    }

    /** Left edge and cell width of the long-press popup for a multi-character hint. */
    private fun altPopup(k: Key): Pair<Float, Float> {
        val n = k.hint!!.length
        val cellW = max(k.rect.width(), dpu(40f))
        val left = (k.rect.centerX() - n * cellW / 2).coerceIn(0f, width - n * cellW)
        return left to cellW
    }

    /** Mini number mode: holding the symbol strip or the digit row opens the zoom, as with letters. */
    private fun zoomsInNumbers(k: Key?) =
        mode == Mode.MINI && panel != Panel.LETTERS && !panel.big &&
            k != null && (k.style == Style.STRIP || k.style == Style.BIG) && !isIconSlot(k.label)

    /** Mini number mode: which digit-row key (0..12) is under x. */
    private fun digitAt(x: Float) =
        ((x - letterRect.left) / letterRect.width() * Layouts.MINI_NUMBER_ROW.size).toInt()
            .coerceIn(0, Layouts.MINI_NUMBER_ROW.lastIndex)

    /** Opens the zoom around the finger, on the row it is on (strip = 0, letters or digits = 1). */
    private fun startZoom(p: Pointer) {
        val slot = nearestSlot(p.downX)
        val numbers = mode == Mode.MINI && panel != Panel.LETTERS
        zoom = Zoom((slot - 2).coerceIn(0, 21), slot, if (p.downY < letterRect.top) 0 else 1, numbers, digitAt(p.downX))
        p.zooming = true
    }

    /** The finger picks the slot under it; the magnified window scrolls when it reaches an edge. */
    private fun updateZoom(p: Pointer) {
        val z = zoom ?: return
        val slot = nearestSlot(p.x)
        if (slot < z.start) z.start = slot
        if (slot > z.start + 4) z.start = slot - 4
        val row = if (p.y < letterRect.top) 0 else 1
        val digit = digitAt(p.x)
        if (slot != z.slot || row != z.row || z.numbers && row == 1 && digit != z.digit) {
            z.slot = slot
            z.row = row
            z.digit = digit
        }
    }

    /** The finger lifted during the zoom: type the selected symbol, or an exact letter tap. */
    private fun commitZoom() {
        val z = zoom ?: return
        zoom = null
        if (z.numbers) {
            text(if (z.row == 0) Layouts.MINI_NUMBER_STRIP[z.slot] else Layouts.MINI_NUMBER_ROW[z.digit])
        } else if (z.row == 0) {
            text(Layouts.MINI_STRIP[z.slot])
        } else {
            val letter = layout.miniOrder[z.slot]
            listener.onLetterTap(Tap(z.slot.toFloat(), 0f, exact = letter - 'a'))
        }
    }

    // ---- the llama ----------------------------------------------------------------------------

    /** 2 dp per sprite pixel, in whole screen pixels so it stays crisp. */
    private val llama = Llama(max(1f, (2f * density).roundToInt().toFloat()))
    private val llamaCheck = Runnable { maybeStartLlama() }
    private var lastTouchAt = 0L

    /** Checks for a visit after a pause in typing of [LLAMA_IDLE_MS] (or after [delay]). */
    private fun scheduleLlama(delay: Long = LLAMA_IDLE_MS) {
        timer.removeCallbacks(llamaCheck)
        if (llamaVisits && isAttachedToWindow && windowVisibility == VISIBLE) timer.postDelayed(llamaCheck, delay)
    }

    /** Ends a visit at once (the keyboard changed under it); [cancel] also drops the next check. */
    private fun endLlama(cancel: Boolean = false) {
        if (cancel) timer.removeCallbacks(llamaCheck)
        if (llama.visiting) {
            llama.end()
            invalidate()
        }
    }

    /**
     * Starts a visit if one is due ([nextLlamaAt]) and the letters are showing in a pause in typing
     * (no finger down, no zoom, no GIF search, and the IME agrees).
     */
    private fun maybeStartLlama() {
        if (!llamaVisits || !animating || llama.visiting) return
        val now = SystemClock.uptimeMillis()
        if (now < nextLlamaAt) return scheduleLlama(max(LLAMA_IDLE_MS, nextLlamaAt - now))
        val idle = pointers.isEmpty() && zoom == null && !gifSearching && panel == Panel.LETTERS &&
            now - lastTouchAt >= LLAMA_IDLE_MS && listener.llamaMayVisit()
        if (!idle) return scheduleLlama()
        // Mini mode: along the letter row. Full mode: a random one of the four rows.
        val feet = if (mode == Mode.MINI) {
            letterRect.bottom - dp(4f)
        } else {
            barH + fullRowH * (1 + kotlin.random.Random.nextInt(4)) - dp(3f)
        }
        llama.start(width.toFloat(), feet, now)
        nextLlamaAt = now + randomBetween(LLAMA_EVERY_MS)
        invalidate()
    }

    private fun randomBetween(range: LongRange) = range.first + kotlin.random.Random.nextLong(range.last - range.first)

    private fun drawLlama(c: Canvas) {
        if (!llama.visiting) return
        val next = llama.draw(c, width.toFloat(), SystemClock.uptimeMillis())
        // Redraw only when it next moves a pixel or changes frame, not every display frame.
        if (next >= 0) postInvalidateDelayed(next) else scheduleLlama()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Keyboard sessions are short, so a due visit comes a few seconds after it opens.
        if (visibility == VISIBLE) scheduleLlama(randomBetween(LLAMA_OPEN_MS)) else endLlama(cancel = true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        endLlama(cancel = true)
        for (p in pointers.values) {
            timer.removeCallbacks(p.longPress)
            p.repeat?.let(timer::removeCallbacks)
        }
        pointers.clear()
        zoom = null
    }

    // ---- drawing --------------------------------------------------------------------------
    // Paints, paths and rects are reused across frames; every draw function sets what it needs.

    private val fonts = KeyFonts(context)
    private val regular = fonts.regular
    private val medium = fonts.medium
    private val bold = fonts.bold
    /** Emoji are drawn with the system font, which has them. */
    private val emojiFace: Typeface = Typeface.DEFAULT

    /** The bar and the keys share one surface. */
    private val barFill get() = theme.background
    /** Soft surfaces (the suggestion pill, space bar, active tab): a little lighter than the background. */
    private val softFill get() = if (theme.isLight) ColorUtils.blendARGB(theme.background, Color.WHITE, 0.75f) else theme.keyFill
    private val softPressedFill get() = ColorUtils.blendARGB(softFill, theme.text, 0.16f)
    private val hintColor get() = ColorUtils.blendARGB(theme.dim, theme.background, 0.3f)
    private val stripColor get() = ColorUtils.blendARGB(theme.dim, theme.background, 0.35f)
    /** The hairlines between suggestions: just visible on the bar in every theme. */
    private val dividerColor get() = ColorUtils.blendARGB(theme.background, theme.text, if (theme.isLight) 0.2f else 0.16f)
    private val icons = KeyIcons(density, fonts)

    // ---- motion (off when the system's animations are off, or offscreen as in tests)

    /** When the shown panel or mode last changed; the new content fades in from then. */
    private var panelChangedAt = 0L

    private fun markPanelChange() {
        panelChangedAt = SystemClock.uptimeMillis()
        endLlama()
    }

    private val animating get() = isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    /** 0..1 progress of an animation that started at [since]; keeps redrawing until it's done. */
    private fun progress(since: Long, ms: Long): Float {
        if (!animating) return 1f
        val t = ((SystemClock.uptimeMillis() - since) / ms.toFloat()).coerceIn(0f, 1f)
        if (t < 1f) postInvalidateOnAnimation()
        return 1f - (1f - t) * (1f - t) // ease out
    }

    /** Draws a popup that fades and scales in from (px, py), starting at [since]. */
    private inline fun popIn(c: Canvas, since: Long, px: Float, py: Float, draw: () -> Unit) {
        val e = progress(since, POP_MS)
        if (e >= 1f) return draw()
        val save = c.saveLayerAlpha(null, (255 * e).toInt())
        val sc = 0.86f + 0.14f * e
        c.scale(sc, sc, px, py)
        draw()
        c.restoreToCount(save)
    }

    /** Popups float on a soft shadow. */
    private fun shadowOn() {
        fill.setShadowLayer(dpu(8f), 0f, dpu(2f), 0x40000000)
    }

    private fun shadowOff() = fill.clearShadowLayer()

    private val popupRadius get() = dpu(14f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = regular }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tmp = RectF()

    /** Key labels follow shift. */
    private fun upper(s: String) = if (shift != Shift.OFF) s.uppercase() else s

    /** Advances the suggestion strip's and the emoji grid's fling animations. */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            barScroll = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
        if (gridScroller.computeScrollOffset()) {
            gridScroll = (if (gifSearching) gridScroller.currX else gridScroller.currY).toFloat()
            postInvalidateOnAnimation()
        }
    }

    private val iconColor get() = if (theme.accentIcons) theme.accent else theme.text

    override fun onDraw(canvas: Canvas) {
        lastDrawAt = SystemClock.uptimeMillis()
        val header = gifHeaderH
        canvas.translate(0f, overlayH + header)
        fill.color = theme.background
        canvas.drawRect(0f, -header, width.toFloat(), height - overlayH - header, fill)
        // After a switch (letters / numbers / emoji / GIF / mini / full) the new content fades in.
        val fade = progress(panelChangedAt, FADE_MS)
        if (fade >= 1f) return drawContent(canvas)
        val save = canvas.saveLayerAlpha(null, (255 * fade).toInt())
        drawContent(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawContent(canvas: Canvas) {
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
            drawText(canvas, LANGUAGE, slotCenter(25), letterRect.bottom - miniRowH * 0.12f, miniRowH * 0.3f, t.dim, regular)
        }
        drawLlama(canvas)
        drawPreviews(canvas)
        drawZoom(canvas)
    }

    private fun isPressed(k: Key) = pointers.values.any { it.key === k && !it.consumed }

    /** Draws one key: its pressed background, then its label or icon. */
    private fun drawKey(c: Canvas, k: Key, inBar: Boolean) {
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
                drawText(c, LANGUAGE, cx, cy, h * 0.38f, t.dim, regular)
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
            Kind.CLIP_TAB -> icons.clipboard(c, cx, cy, min(r.width(), r.height()) * 0.2f, t.dim)
            Kind.CLIP_CLEAR -> icons.trash(c, cx, cy, min(r.width(), r.height()) * 0.2f, if (clips.isEmpty()) hintColor else t.dim)
            Kind.AUTOCORRECT -> icons.autocorrect(c, cx, cy, barH * 0.22f, autocorrectOn, if (autocorrectOn) t.accent else t.dim)
            Kind.SUGGESTION -> {} // drawn by drawSuggestions
        }
    }

    /** Label of a [Kind.TEXT] key, sized by its [Style]. */
    private fun drawTextKey(c: Canvas, k: Key) {
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
    private fun drawHint(c: Canvas, r: RectF, hint: String) {
        val x = r.right - r.width() * (if (hint.length > 1) 0.3f else 0.26f)
        val y = r.top + r.height() * 0.28f
        if (hint == Layouts.EMOJI_SLOT) icons.smiley(c, x, y + r.height() * 0.03f, r.height() * 0.1f, hintColor)
        else drawText(c, hint, x, y, r.height() * 0.19f, hintColor, regular)
    }


    /** The scrollable suggestion strip: hairlines between words, a fade where more words continue. */
    private fun drawSuggestions(c: Canvas) {
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
    private fun drawForgetConfirm(c: Canvas) {
        val t = theme
        val w = width.toFloat()
        fill.color = barFill
        c.drawRect(0f, 0f, w, barH, fill)
        val word = suggestions.getOrNull(confirmForget).orEmpty()
        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        var size = barH * 0.38f
        val label = "Remove “$word” from suggestions?"
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
        drawText(c, "Remove", tmp.centerX(), tmp.centerY(), barH * 0.36f, t.onAccent, bold)
        drawText(c, "Cancel", cancelRect.centerX(), cancelRect.centerY(), barH * 0.36f, t.text, regular)
    }

    private fun drawFade(c: Canvas, l: Float, r: Float, toRight: Boolean) {
        val solid = barFill
        val clear = ColorUtils.setAlphaComponent(solid, 0)
        fill.shader = LinearGradient(l, 0f, r, 0f, if (toRight) clear else solid, if (toRight) solid else clear, Shader.TileMode.CLAMP)
        c.drawRect(l, 0f, r, barH, fill)
        fill.shader = null
    }

    /** One suggestion, shrunk only if it is wider than the whole bar; the default pick sits on a pill. */
    private fun drawSuggestion(c: Canvas, s: String, highlighted: Boolean, r: RectF) {
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

    /**
     * Light under touches on mini mode's row ("designed for big fingers"), as a smooth column:
     * brightest over the finger, falling off over about two letters to
     * each side, across the symbol strip and the letters.
     */
    private fun drawGlows(c: Canvas) {
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
    private fun glowShader(half: Float): Shader {
        val color = theme.text
        glowCache?.takeIf { glowColor == color && glowHalf == half }?.let { return it }
        val colors = IntArray(GLOW_STOPS.size) { i -> ColorUtils.setAlphaComponent(color, (255 * GLOW_STOPS[i]).roundToInt()) }
        return LinearGradient(-half, 0f, half, 0f, colors, GLOW_POSITIONS, Shader.TileMode.CLAMP).also {
            glowCache = it
            glowColor = color
            glowHalf = half
        }
    }
    private var glowCache: Shader? = null
    private var glowColor = 0
    private var glowHalf = 0f

    /** Mini mode's symbol strip and staggered letter row (drawn directly, they aren't [Key]s). */
    private fun drawMiniLetters(c: Canvas) {
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
    private fun drawPreviews(c: Canvas) {
        val t = theme
        for (p in pointers.values) {
            val k = p.key ?: continue
            if (k.kind == Kind.SHIFT && p.longPressed && !p.consumed) {
                drawShiftMenu(c, k, p)
                continue
            }
            if (p.consumed || p.zooming || k.rect.top < barH) continue
            if (k.kind != Kind.LETTER && k.kind != Kind.TEXT) continue
            val hint = k.hint
            if (p.longPressed && hint != null && hint.length > 1) {
                // Pick-one popup (e.g. ",!?" on the period key).
                val (left, cellW) = altPopup(k)
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
    private fun drawZoom(c: Canvas) {
        val z = zoom ?: return
        popIn(c, z.openedAt, slotCenter(z.slot), letterRect.top) { drawZoomPopup(c, z) }
    }

    private fun drawZoomPopup(c: Canvas, z: Zoom) {
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
            val sym = (if (z.numbers) Layouts.MINI_NUMBER_STRIP else Layouts.MINI_STRIP)[s]
            val symColor = if (symSel) t.onZoomSelect else t.text
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
    private fun shiftMenuRect(k: Key, out: RectF) {
        val s = max(dpu(48f), k.rect.height() * 1.1f)
        val w = s * SHIFT_MENU.size
        val left = (k.rect.centerX() - s / 2).coerceIn(dpu(4f), width - w - dpu(4f))
        val top = max(-overlayH + dpu(2f), k.rect.top - s - dpu(6f))
        out.set(left, top, left + w, top + s)
    }

    /** The menu cell the finger is over (or above / below), or -1 once it has slid away. */
    private fun shiftMenuChoice(k: Key, p: Pointer): Int {
        val r = RectF()
        shiftMenuRect(k, r)
        val near = RectF(r).apply { inset(-dpu(12f), -dpu(12f)); union(k.rect) }
        if (!near.contains(p.x, p.y)) return -1
        return ((p.x - r.left) / r.height()).toInt().coerceIn(0, SHIFT_MENU.lastIndex)
    }

    private fun drawShiftMenu(c: Canvas, k: Key, p: Pointer) {
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

    /** Strip entries drawn as icons rather than text: ☺ (emoji panel) and the clipboard. */
    private fun isIconSlot(s: String) = s == Layouts.EMOJI_SLOT || s == Layouts.CLIPBOARD_SLOT

    private fun drawSlotIcon(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        if (s == Layouts.CLIPBOARD_SLOT) icons.clipboard(c, cx, cy, size, color) else icons.smiley(c, cx, cy, size, color)
    }

    /**
     * The clipboard panel: its title in the bar, then a card per clip (up to [CLIP_LINES] lines, the
     * rest cut off with "…"), the one under a resting finger highlighted. A hint shows while it's empty.
     */
    private fun drawClipboard(c: Canvas) {
        val t = theme
        text.textAlign = Paint.Align.LEFT
        drawText(c, resources.getString(R.string.clip_title), pad * 2, barH / 2, barH * 0.42f, t.text, medium)
        text.textAlign = Paint.Align.CENTER
        c.withClip(clipRect) {
            if (clips.isEmpty()) {
                drawText(c, resources.getString(R.string.clip_empty), clipRect.centerX(), clipRect.centerY(), dp(14f), t.dim, regular)
                return@withClip
            }
            val pressed = pointers.values.firstOrNull { it.area == Area.GRID && !it.moved && !it.consumed }
                ?.let { clipAt(it.downX, it.downY) }
            val radius = dpu(10f)
            val inset = dpu(10f)
            clipPaint.textSize = dpu(14f)
            clipPaint.typeface = regular
            clipPaint.color = t.text
            for ((i, clip) in clips.withIndex()) {
                clipCardRect(i, tmp)
                if (!RectF.intersects(tmp, clipRect)) continue
                fill.color = if (i == pressed) t.keyPressed else t.keyFill
                c.drawRoundRect(tmp, radius, radius, fill)
                val textW = (tmp.width() - 2 * inset).toInt().coerceAtLeast(1)
                val layout = clipLayouts.getOrPut(clip) {
                    // Only the start can show in three lines; don't lay out a long copy in full.
                    val preview = clip.take(CLIP_PREVIEW_CHARS)
                    StaticLayout.Builder.obtain(preview, 0, preview.length, clipPaint, textW)
                        .setMaxLines(CLIP_LINES)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                }
                c.withSave {
                    c.clipRect(tmp)
                    c.translate(tmp.left + inset, tmp.top + (tmp.height() - layout.height) / 2)
                    layout.draw(c)
                }
            }
        }
    }

    /** Draws [s] centred on (cx, cy), vertically centred on its glyphs. */
    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, face: Typeface) {
        text.typeface = face
        text.textSize = size
        text.color = color
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    /** The visible rows of the emoji grid, with a highlight under a finger that's about to tap. */
    private fun drawEmojiGrid(c: Canvas) = c.withClip(emojiRect) {
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
    private fun drawGifGrid(c: Canvas) = c.withClip(gifRect) {
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
    private fun drawGifSearchField(c: Canvas, r: RectF) {
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

    companion object {
        /** When the next llama visit is due (uptime ms; 0: the first time the keyboard opens). Kept while the app runs. */
        private var nextLlamaAt = 0L
        /** Time from one visit to when the next is due, and from the keyboard opening to a due visit (ms). */
        private val LLAMA_EVERY_MS = 3 * 60_000L..6 * 60_000L
        private val LLAMA_OPEN_MS = 2_000L..6_000L
        /** The pause in typing a visit starts in. */
        private const val LLAMA_IDLE_MS = 1_500L
        /** The strip always has at least this many slots, so a lone word still sits where it would among three. */
        private const val MIN_SLOTS = 3
        /** The narrowest a suggestion slot gets, as a share of the strip (so up to six short words fit). */
        private const val MIN_SLOT_FRACTION = 1f / 6
        private const val EMOJI_COLUMNS = 8
        private const val GIF_COLUMNS = 3
        /** Slowest GIF frame interval while typing a search (see [invalidateDrawable]). */
        private const val GIF_TYPING_FRAME_MS = 80L
        private const val CLIP_COLUMNS = 2
        /** Lines of text shown on a clip card before it's cut off. */
        private const val CLIP_LINES = 3
        private const val CLIP_PREVIEW_CHARS = 300
        /** Shown on the space bar. */
        private const val LANGUAGE = "en"
        /** Long-press choices on the period key. */
        private const val PERIOD_ALTS = ",!?"
        /** Radius of the emoji / GIF panels' floating ✕. */
        private const val CLOSE_RADIUS_DP = 14f
        /** Its gap to the panel's right edge and (smaller, so it sits low) to its bottom edge. */
        private const val CLOSE_SIDE_DP = 12f
        private const val CLOSE_BOTTOM_DP = 4f
        /** Its opacity (0..255): 50%, so the GIFs, emoji and clips under it show through. */
        private const val CLOSE_ALPHA = 128
        /** Height of the GIF strip in the search header. */
        private const val GIF_STRIP_DP = 92f
        /** Shift and backspace are drawn smaller than other icons, to match the lowercase letters. */
        private const val SMALL_ICON = 0.7f
        /** How long a popup takes to fade and scale in (see [popIn]). */
        private const val POP_MS = 110L
        /** Shift's hold menu, left to right. */
        private const val MENU_SETTINGS = 0
        private const val MENU_KEYBOARDS = 1
        private val SHIFT_MENU = intArrayOf(MENU_SETTINGS, MENU_KEYBOARDS)
        /** The finger glow: fade in / out times, peak opacity (0..255) and reach to each side, in letters. */
        private const val GLOW_IN_MS = 90L
        private const val GLOW_OUT_MS = 260L
        private const val GLOW_ALPHA = 46f
        private const val GLOW_HALF_SLOTS = 2.6f
        /** A bell curve sampled across the glow, so it fades smoothly to nothing at its edges. */
        private val GLOW_POSITIONS = FloatArray(13) { it / 12f }
        private val GLOW_STOPS = FloatArray(13) { i ->
            val d = (i / 12f - 0.5f) * 2f
            ((exp(-3f * d * d) - exp(-3f)) / (1f - exp(-3f))).coerceAtLeast(0f)
        }
        /** How long new content takes to fade in after a panel or mode switch. */
        private const val FADE_MS = 160L
        /** Backspace: hold this long before it repeats, then repeat this often. */
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_MS = 55L
    }
}

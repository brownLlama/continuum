package com.brownllama.continuum.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
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
import androidx.core.view.ViewCompat
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.R
import com.brownllama.continuum.prediction.Language
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.theme.KeyboardTheme
import kotlin.math.max
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
 * - Emoji:         full-keyboard sized in either mode: group tabs (+ stickers, GIF, ⌫) in place of the bar,
 *                  over one grid that scrolls through "frequently used" and every group; ✕ closes it.
 * - Clipboard:     same size: bar ["Clipboard" | bin] over cards of recently copied text; tap one
 *                  to paste it, hold one to delete it.
 * - Stickers:      same size: bar [☺ | "Stickers" | +] over a grid of stickers: the user's own,
 *                  then the built-in ones. Tap one to send it, hold one of your own to delete it.
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
 *
 * Screen readers get the keys, suggestions and grid cells as named virtual views
 * ([accessNodes], [KeyboardAccessibility]).
 *
 * This file holds the state and the public surface; the work is in extension functions next to it:
 * KeyboardLayout / KeyboardPanelLayout (where things go), KeyboardTouch / KeyboardKeyActions /
 * KeyboardZoom (touch and what keys do), KeyboardDraw / KeyboardDrawEffects / KeyboardDrawPanels
 * (drawing), KeyboardAccess (screen reader), KeyboardAnimalHint (animals and the language hint),
 * with the small types in KeyboardTypes and the numbers in KeyboardConstants.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, internal val listener: Listener) : View(context) {

    /** What the user did, in editing terms. */
    interface Listener {
        /** A touch on the letters, to be decoded into a word (see [Tap] for the coordinates). */
        fun onLetterTap(tap: Tap)
        /** Whether an animal may visit now (not in a password field…), see [Animal]. */
        fun animalMayVisit(): Boolean
        /** Literal text to type: a digit, a symbol, or a long-press character. */
        fun onText(text: String)
        /** An emoji picked from the emoji panel. */
        fun onEmoji(emoji: String)
        fun onBackspace()
        /**
         * A finger slid along the grass: move the cursor by [steps] characters (negative: left), never
         * past the ends of the text. With text selected, it stretches the selection instead.
         */
        fun onCursorMove(steps: Int)
        /** An item of the grass's hold menu: act on the selection (or the whole text). */
        fun onEditAction(action: EditAction)
        /** Swipe left. */
        fun onDeleteWord()
        /** Space key or swipe right. */
        fun onSpace()
        /** Enter key or swipe up and right. */
        fun onEnter()
        /** Swipe up and left (a wide sector, see [gesture]): switch between English and German. */
        fun onLanguageToggle()
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
        /** The sticker panel opened: send the sticker images through [setStickers]. */
        fun onStickersOpen()
        /** Sticker [index] (in the order given to [setStickers]) was tapped: send it. */
        fun onStickerPicked(index: Int)
        /** One of the user's own stickers was held: delete it. */
        fun onStickerRemove(index: Int)
        /** The sticker panel's +: add a sticker from the user's photos. */
        fun onStickerAdd()
    }

    enum class Shift { OFF, ON, LOCK }

    /** What the grass's hold menu asks of the text field. */
    enum class EditAction { SELECT_WORD, SELECT_ALL, CUT, COPY, PASTE }

    /** What fills the keyboard below the bar. SYMBOLS / SYMBOLS2 are the two number pages. */
    enum class Panel {
        LETTERS, SYMBOLS, SYMBOLS2, EMOJI, GIF, CLIPBOARD, STICKERS;

        /** Emoji, GIF, clipboard and sticker panels are full-keyboard sized in either mode and replace the bar. */
        val big get() = this == EMOJI || this == GIF || this == CLIPBOARD || this == STICKERS
    }

    /** One GIF in the panel: [image] (usually animated) arrives once its preview has downloaded. */
    class GifTile(val aspect: Float) {
        var image: Drawable? = null
    }

    // ---- state set by the IME (mostly from settings) --------------------------------------

    var mode = Mode.MINI
        set(value) {
            if (field != value) markPanelChange(MODE_FADE_MS, dpu(MODE_SLIDE_DP))
            field = value
            glows.clear()
            if (panel == Panel.GIF) stopGifs()
            // A mode switch while searching GIFs keeps the search open, in the new mode.
            panel = Panel.LETTERS
            requestLayout()
            relayout()
        }
    var panel = Panel.LETTERS
        internal set
    var theme = KeyboardTheme.NORD_DARK
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Height multipliers from the settings sliders, one per mode. */
    var miniScale = 1f
        set(value) { if (field != value) { field = value; requestLayout(); relayout() } }
    var fullScale = 1f
        set(value) { if (field != value) { field = value; requestLayout(); relayout() } }
    internal val scale get() = if (mode == Mode.MINI && !panel.big) miniScale else fullScale
    /** Suggestion bar height multiplier (settings slider), on top of the mode's scale. */
    var barScale = 1f
        set(value) { if (field != value) { field = value; requestLayout(); relayout() } }
    /**
     * Space between the keys and whatever system bar is under them (settings slider). From Android 15
     * the IME window is edge-to-edge, so the bars' own height is added separately ([systemBottom]).
     */
    var bottomSpaceDp = Prefs.BOTTOM_SPACE_DEFAULT.toFloat()
        set(value) { if (field != value) { field = value; requestLayout() } }
    /**
     * Height of the grass, the touchable top of the space under the keys (settings slider). The strip
     * under the keys is at least this tall; whatever is below the grass, and under Android's bars, is soil.
     */
    var grassDp = Prefs.GRASS_HEIGHT_DEFAULT.toFloat()
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    /**
     * Height in px of the system bars still showing under the keyboard: the gesture handle or
     * navigation buttons, and Android 16's keyboard buttons unless "Hide system buttons" hid them.
     * The keys sit above it, so nothing overlaps them.
     */
    internal var systemBottom = 0
    var layout = KeyLayout.QWERTZ
        set(value) { if (field != value) { field = value; relayout() } }
    /** Whether the emoji panel has the GIF tab (setting "Show GIFs"). */
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
    /** Settings "Vibrate on keys": 0 off, else the strength (1..100) of a tick on each key press. */
    var vibration: Int
        get() = keyVibration.strength
        set(value) { keyVibration.strength = value }
    internal val keyVibration = KeyVibration(context)
    var shift = Shift.OFF
        set(value) { field = value; invalidate(); accessibility.changed() }
    /** State of full mode's bar switch. */
    var autocorrectOn = true
        set(value) { if (field != value) { field = value; invalidate(); accessibility.changed() } }
    /** The field takes an email address: full mode's "," key becomes "@". */
    var emailField = false
        set(value) { if (field != value) { field = value; relayout() } }
    /** Settings "Animal visits" and "Animals": which may walk across the keys now and then (none: off). */
    internal var animals: List<Species> = Species.ALL
        set(value) {
            field = value
            if (value.isNotEmpty()) scheduleAnimal() else endAnimal(cancel = true)
        }
    /** Setting "How often". */
    internal var animalFrequency = AnimalFrequency.ALWAYS
        set(value) {
            if (field == value) return
            field = value
            nextAnimalAt = 0L
            scheduleAnimal()
        }

    /**
     * The keyboard opened ([ContinuumIME.onWindowShown], and the window turning visible): a due
     * visit comes in a moment, and with "Every time" one is always due.
     */
    fun keyboardShown() {
        if (animalFrequency.gapMs == null) nextAnimalAt = 0L
        scheduleAnimal(randomBetween(animalFrequency.openMs))
    }

    fun keyboardHidden() {
        endAnimal(cancel = true)
    }
    /** The editor's action (EditorInfo.IME_ACTION_*), drawn on the enter key; NONE = plain enter arrow. */
    var enterAction = EditorInfo.IME_ACTION_NONE
        set(value) { field = value; invalidate(); accessibility.changed() }

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
        accessibility.changed()
    }

    /** Suggestion index awaiting "Remove from suggestions?" confirmation in the bar, or -1. */
    internal var confirmForget = -1
    internal val removeRect = RectF()
    internal val cancelRect = RectF()

    /** Emoji panel content after "frequently used", tab by tab (see [Emoji]). */
    var emojiGroups: List<EmojiGroup> = emptyList()
        set(value) { field = value; if (panel == Panel.EMOJI) relayout() }
    /** The user's most used emoji. Read when the panel opens, so the grid doesn't reshuffle while picking. */
    var frequentEmoji: List<String> = Emoji.DEFAULT_FREQUENT
    internal var frequentShown = frequentEmoji

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
        // Sticker images are only kept while their panel shows; it asks for them again when it opens.
        if (panel == Panel.STICKERS) stickers = emptyList()
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
        if (p == Panel.STICKERS) {
            stickerScroll = 0f
            listener.onStickersOpen()
        }
    }

    // ---- geometry -------------------------------------------------------------------------

    internal val density = resources.displayMetrics.density
    /** dp → px, scaled by the current mode's height slider. Used for everything that grows with the keyboard. */
    internal fun dp(v: Float) = v * density * scale
    /** dp → px, unscaled. Used for fixed details like paddings, corner radii and line widths. */
    internal fun dpu(v: Float) = v * density

    internal val barH get() = dp(34f) * barScale
    internal val stripH get() = dp(26f)
    internal val miniRowH get() = dp(56f)
    internal val fullRowH get() = dp(46f)
    internal val pad get() = dpu(6f)

    /** Zoom popup size; it rises above the keyboard into [overlayH]. */
    internal val zoomCellW get() = dp(30f)
    internal val zoomSymH get() = dp(40f)
    internal val zoomLetH get() = dp(62f)

    /**
     * Transparent space above the keyboard that popups can draw into. The IME reports only the
     * area below it as the keyboard (see ContinuumIME.onComputeInsets), so the app stays visible
     * and touchable there.
     */
    val overlayH: Float get() = max(0f, zoomSymH + zoomLetH - barH) + dpu(8f)

    /** Height of the keyboard itself (bar + body), without the overlay, the GIF search header or the space below it. */
    val keyboardHeight: Float get() = barH + bodyHeight()

    /** The GIF search header above the bar: [☺ | search field | ⌄] over a strip of GIFs. */
    internal val gifStripH get() = dpu(GIF_STRIP_DP)
    val gifHeaderH: Float get() = if (gifSearching) barH + gifStripH else 0f

    internal fun bodyHeight(): Float = when {
        mode == Mode.MINI && !panel.big -> stripH + miniRowH
        else -> fullRowH * 4
    }

    // ---- layout (recomputed by relayout) ----------------------------------------------------

    /** Keys in the bar (y < barH) and below it; hit-tested separately. */
    internal val barKeys = ArrayList<Key>()
    /** The GIF search header's keys (y < 0, see [gifHeaderH]). */
    internal val headerKeys = ArrayList<Key>()
    internal val suggestionArea = RectF()
    /** How far the suggestion strip is scrolled sideways, in px. */
    internal var barScroll = 0f
    internal val scroller = OverScroller(context)
    /**
     * Left edge of each suggestion slot along the strip (before scrolling), plus the end of the last
     * one; see [layoutSuggestions]. Slots are as wide as their word, so short words fit more per bar.
     */
    internal var slotEdges = FloatArray(1)
    internal val maxBarScroll get() = max(0f, slotEdges.last() - suggestionArea.width())

    internal val suggestionTextSize get() = barH * 0.46f
    internal val suggestionPad get() = barH * 0.45f

    internal val keys = ArrayList<Key>()
    /** Mini mode's symbol strip and letter row. Both are divided into 26 equal "slots". */
    internal val stripRect = RectF()
    internal val letterRect = RectF()
    /** Emoji panel: "frequently used" + [emojiGroups], each starting a new grid row ([emojiSectionRow]). */
    internal var emojiSections: List<EmojiGroup> = emptyList()
    internal var emojiSectionRow = IntArray(0)
    internal var emojiRows = 0
    internal val emojiRect = RectF()
    internal var emojiCellW = 1f
    internal var emojiRowH = 1f
    internal var emojiScroll = 0f
    /** Flings the emoji or the GIF grid, whichever is showing (see [gridScroll]). */
    internal val gridScroller = OverScroller(context)
    internal val maxEmojiScroll get() = max(0f, emojiRows * emojiRowH + dpu(56f) - emojiRect.height())

    // GIF panel. The IME fills [gifTiles] through setGifs; the view only shows and scrolls them.
    internal val gifTiles = ArrayList<GifTile>()
    /** Shown in place of the grid when there are no tiles (loading, nothing found, no key, error). */
    internal var gifMessage: String? = null
    /** The search text, as the IME's query editor holds it (kept when going back to the grid). */
    var gifQuery = ""
        set(value) { field = value; invalidate() }
    /** The keyboard is showing under the search header (see [openGifSearch]). */
    var gifSearching = false
        internal set
    /** GIFs are on screen: the GIF panel, or the search header's strip. */
    internal val showsGifs get() = panel == Panel.GIF || gifSearching
    internal var gifScroll = 0f
    internal val gifRect = RectF()
    internal var gifCellW = 1f
    internal var gifCellH = 1f
    /** Asked the IME for the next page and waiting for it (cleared by setGifs). */
    private var gifMoreRequested = false
    internal val gifGap get() = dpu(4f)
    private val gifRows get() = (gifTiles.size + GIF_COLUMNS - 1) / GIF_COLUMNS
    /** While searching, the GIFs sit in one row that scrolls sideways; [gifScroll] is then horizontal. */
    internal val stripCellH get() = gifStripH - 2 * gifGap
    internal val stripCellW get() = stripCellH * 1.3f
    internal val maxGifScroll get() =
        if (gifSearching) max(0f, 2 * pad + gifTiles.size * (stripCellW + gifGap) - gifGap - gifRect.width())
        else max(0f, gifRows * (gifCellH + gifGap) + dpu(56f) - gifRect.height())

    // Clipboard panel. The IME fills [clips] through setClips; the view only shows and scrolls them.
    internal var clips: List<String> = emptyList()
    internal var clipScroll = 0f
    internal val clipRect = RectF()
    internal var clipCellW = 1f
    internal val clipCellH get() = dpu(78f)
    private val clipRows get() = (clips.size + CLIP_COLUMNS - 1) / CLIP_COLUMNS
    internal val maxClipScroll get() = max(0f, clipRows * (clipCellH + gifGap) + dpu(56f) - clipRect.height())
    /** Laid-out card text, per clip; rebuilt when the clips or the card width change. */
    internal val clipLayouts = HashMap<String, StaticLayout>()
    internal val clipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Shown in place of the cards when there are none; null: the usual "nothing copied yet" hint. */
    internal var clipMessage: String? = null

    /**
     * The copied texts to show, newest first. With none, [emptyMessage] says why (the phone is
     * locked, the history is switched off) in place of the usual hint.
     */
    fun setClips(texts: List<String>, emptyMessage: String? = null) {
        clips = texts
        clipMessage = emptyMessage
        clipLayouts.clear()
        clipScroll = clipScroll.coerceIn(0f, maxClipScroll)
        invalidate()
        accessibility.changed()
    }

    // Sticker panel. The IME fills [stickers] through setStickers; the view only shows and scrolls them.
    internal var stickers: List<Bitmap?> = emptyList()
    /** Per sticker: whether it's one of the user's own (those can be held to delete them). */
    internal var stickerRemovable: List<Boolean> = emptyList()
    internal var stickerScroll = 0f
    internal val stickerRect = RectF()
    internal var stickerCell = 1f
    private val stickerRows get() = (stickers.size + STICKER_COLUMNS - 1) / STICKER_COLUMNS
    internal val maxStickerScroll get() =
        max(0f, stickerRows * (stickerCell + gifGap) + dpu(56f) - stickerRect.height())
    internal val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The sticker panel's images (null: couldn't be read) and which of them the user added. */
    fun setStickers(images: List<Bitmap?>, removable: List<Boolean>) {
        if (panel != Panel.STICKERS) return
        stickers = images
        stickerRemovable = removable
        stickerScroll = stickerScroll.coerceIn(0f, maxStickerScroll)
        invalidate()
        accessibility.changed()
    }

    /** The scroll position of whichever grid is showing (emoji, GIF, clipboard or stickers). */
    internal var gridScroll: Float
        get() = when {
            showsGifs -> gifScroll
            panel == Panel.CLIPBOARD -> clipScroll
            panel == Panel.STICKERS -> stickerScroll
            else -> emojiScroll
        }
        set(value) {
            when {
                showsGifs -> gifScroll = value
                panel == Panel.CLIPBOARD -> clipScroll = value
                panel == Panel.STICKERS -> stickerScroll = value
                else -> emojiScroll = value
            }
            if (showsGifs) maybeLoadMoreGifs()
        }
    internal val maxGridScroll get() = when {
        showsGifs -> maxGifScroll
        panel == Panel.CLIPBOARD -> maxClipScroll
        panel == Panel.STICKERS -> maxStickerScroll
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
        accessibility.changed()
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
     * again searches anew ([showPanel]). With [force] (the keyboard is hidden, so nothing shows)
     * they go in any case, and a GIF panel or search left open goes back to the letters.
     */
    fun releaseGifs(force: Boolean = false) {
        if (force) {
            closeGifSearch()
            if (panel == Panel.GIF) showPanel(Panel.LETTERS)
        }
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
            // The bars, and the strip at the bottom where Android reads its home and app-switch swipes
            // (which can be taller than the bar itself): nothing of ours goes there.
            insets.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.mandatorySystemGestures(),
            ).bottom
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
        val height = overlayH + gifHeaderH + keyboardHeight + dpu(max(bottomSpaceDp, grassDp)) + systemBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateGestureExclusion()
    }

    // Mini mode slots: slot i holds the i-th letter of KeyLayout.miniOrder and the i-th strip symbol.
    internal val slotW get() = letterRect.width() / 26f

    internal val pointers = LinkedHashMap<Int, Pointer>()

    internal val glows = ArrayList<Glow>()
    internal val timer = Handler(Looper.getMainLooper())
    internal val slop get() = dpu(14f)
    /** Event time of the touch event being handled. */
    internal var touchTime = 0L

    /** Offers the German form of the zoom's letter once the finger rests on it a while longer. */
    internal val zoomGerman = Runnable {
        val z = zoom ?: return@Runnable
        if (z.numbers || z.row != 1) return@Runnable
        val g = GERMAN[layout.miniOrder[z.slot]] ?: return@Runnable
        z.german = germanCase(g)
        z.germanSlot = z.slot
        invalidate()
    }

    /** [g] in the current case: Ä Ö Ü with shift; ß has no everyday capital, so it stays ß. */
    internal fun germanCase(g: String) = if (g == "ß") g else upper(g)

    internal var zoom: Zoom? = null

    /** Shifts touches into keyboard coordinates (y = 0 at the top of the bar), see the class comment. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val e = MotionEvent.obtain(event)
        e.offsetLocation(0f, -overlayH - gifHeaderH)
        handleTouch(e)
        e.recycle()
        return true
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

    // ---- accessibility -----------------------------------------------------------------------

    internal val accessibility = KeyboardAccessibility(this) { accessNodes() }

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    /** Touch exploration (TalkBack) moves a finger over the keys as hover events. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Everything showing that a screen reader can name and activate, top to bottom: the GIF search
     * header, the bar (each suggestion on its own), the grid cells in view, mini mode's symbols and
     * letters, then the keys. Activating a letter types exactly that letter.
     */
    private fun accessNodes(): List<AccessNode> = buildAccessNodes()

    // ---- visiting animals --------------------------------------------------------------------

    /** 2 dp per sprite pixel, in whole screen pixels so it stays crisp. */
    internal val animal = Animal(max(1f, (2f * density).roundToInt().toFloat()))
    /** The strip under the keyboard, in the same pixels as the animals that walk on it. */
    internal val grass = Grass(max(1f, (2f * density).roundToInt().toFloat()))
    internal val animalCheck = Runnable { maybeStartAnimal() }
    internal var lastTouchAt = 0L

    // ---- the language hint ------------------------------------------------------------------

    /** The language typed in now ("en", "de"), shown quietly at the bottom right of the mini keys and on the space bar. */
    internal var languageCode: String = Language.EN.code
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    internal var langHintAt = 0L
    internal var langFrom: Language? = null
    internal var langTo: Language? = null

    /** True while the hint after a language change is showing. */
    internal val languageHintShowing: Boolean
        get() = langTo != null && SystemClock.uptimeMillis() - langHintAt < LANG_HINT_MS

    /**
     * A change of language: a small pill over the suggestion bar slides its highlight from [from] to
     * [to] and names the new language, then fades out; the language label next to the keys lights up
     * meanwhile. With the system's animations off it just shows for the same time.
     */
    fun showLanguageHint(from: Language, to: Language) {
        langFrom = from
        langTo = to
        langHintAt = SystemClock.uptimeMillis()
        languageCode = to.code
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) keyboardShown() else keyboardHidden()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        endAnimal(cancel = true)
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
    internal val regular = fonts.regular
    internal val medium = fonts.medium
    internal val bold = fonts.bold
    /** Emoji are drawn with the system font, which has them. */
    internal val emojiFace: Typeface = Typeface.DEFAULT

    /** The bar and the keys share one surface. */
    internal val barFill get() = theme.background
    /** Soft surfaces (the suggestion pill, space bar, active tab): a little lighter than the background. */
    internal val softFill get() = if (theme.isLight) ColorUtils.blendARGB(theme.background, Color.WHITE, 0.75f) else theme.keyFill
    internal val softPressedFill get() = ColorUtils.blendARGB(softFill, theme.text, 0.16f)
    internal val hintColor get() = ColorUtils.blendARGB(theme.dim, theme.background, 0.3f)
    internal val stripColor get() = ColorUtils.blendARGB(theme.dim, theme.background, 0.35f)
    /** The hairlines between suggestions: just visible on the bar in every theme. */
    internal val dividerColor get() = ColorUtils.blendARGB(theme.background, theme.text, if (theme.isLight) 0.2f else 0.16f)
    internal val icons = KeyIcons(density, fonts)

    // ---- motion (off when the system's animations are off, or offscreen as in tests)

    /** When the shown panel or mode last changed; the new content fades in from then. */
    internal var panelChangedAt = 0L
    /** How long that fade lasts, and how far (px) the new content rises into place meanwhile. */
    internal var fadeMs = FADE_MS
    internal var fadeSlide = 0f

    internal val animating get() = isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    internal val popupRadius get() = dpu(14f)
    internal val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = regular }
    internal val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    internal val path = Path()
    internal val tmp = RectF()
    /** Scratch rects for hit tests (grid cells, the shift menu), so touches and frames allocate nothing. */
    internal val hitRect = RectF()
    internal val nearRect = RectF()

    /** Key labels follow shift. */
    internal fun upper(s: String) = if (shift != Shift.OFF) s.uppercase() else s

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

    internal val iconColor get() = if (theme.accentIcons) theme.accent else theme.text

    override fun onDraw(canvas: Canvas) {
        lastDrawAt = SystemClock.uptimeMillis()
        val header = gifHeaderH
        canvas.translate(0f, overlayH + header)
        fill.color = theme.background
        canvas.drawRect(0f, -header, width.toFloat(), height - overlayH - header, fill)
        drawGrass(canvas)
        // After a switch (letters / numbers / emoji / GIF) the new content fades in; after a switch
        // between mini and full mode it also rises a little into place, easing out.
        val fade = progress(panelChangedAt, fadeMs)
        if (fade >= 1f) return drawContent(canvas)
        val eased = 1f - (1f - fade) * (1f - fade)
        val save = canvas.saveLayerAlpha(null, (255 * eased).toInt())
        canvas.translate(0f, (1f - eased) * fadeSlide)
        drawContent(canvas)
        canvas.restoreToCount(save)
    }

    /** The bar's two edge fades (left, right), built once per position and colour rather than every frame. */
    internal val fades = arrayOfNulls<Shader>(2)
    internal val fadeL = FloatArray(2)
    internal val fadeR = FloatArray(2)
    internal val fadeColor = IntArray(2)

    internal var glowCache: Shader? = null
    internal var glowColor = 0
    internal var glowHalf = 0f
}

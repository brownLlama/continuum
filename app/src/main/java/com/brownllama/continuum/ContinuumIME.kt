package com.brownllama.continuum

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.brownllama.continuum.clipboard.ClipboardHistory
import com.brownllama.continuum.gif.Gif
import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.gif.KlipyClient
import com.brownllama.continuum.keyboard.AnimalFrequency
import com.brownllama.continuum.keyboard.Emoji
import com.brownllama.continuum.keyboard.EmojiGroup
import com.brownllama.continuum.keyboard.EmojiHistory
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.keyboard.Species
import com.brownllama.continuum.prediction.DecodeResult
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TextContext
import com.brownllama.continuum.prediction.TouchModel
import com.brownllama.continuum.prediction.UserModel
import com.brownllama.continuum.sticker.AddStickerActivity
import com.brownllama.continuum.sticker.Sticker
import com.brownllama.continuum.sticker.StickerStore
import com.brownllama.continuum.stats.TypingStats
import com.brownllama.continuum.theme.AppThemes
import com.brownllama.continuum.theme.KeyboardTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The keyboard service. Android starts it when a text field gets focus; it owns all editing state
 * and talks to the app's text field through an [InputConnection].
 *
 * How typing works:
 * - Each letter touch from [KeyboardView] arrives as a [Tap] and is added to [taps]. The whole word
 *   is re-decoded after every tap ([redecode]) and shown in the field as *composing* (underlined)
 *   text, with alternatives in the suggestion bar ([slots]).
 * - Space, punctuation or a suggestion commits the word ([commitWord]) and teaches it to the
 *   [UserModel] ([learn]). Backspace right after that reopens the word ([LastCommit]).
 * - With nothing being typed, the bar shows next-word predictions ([showPredictions]).
 * - The word before the cursor ([prevWord]) is read from the field's text whenever a word starts
 *   or the bar is filled ([refreshContext]), so it is right after the cursor moved or text was
 *   deleted too.
 * - Taps that read best as two words with the space missed ("thankyou") are offered, and where it
 *   is clear typed, as those two words; they are learned one after the other ([learn]).
 * - What becomes of each typed word (kept as typed, picked from the bar, or fixed) is counted for
 *   the settings page "Typing statistics" ([TypingStats]); counts only, never in private fields.
 * - Where the user's taps land for each letter is learned from the words they keep ([TouchModel],
 *   [flushTouch]): a word counts once the next one starts, so one taken back with backspace never
 *   does. Setting "Learn how you tap"; never in private fields.
 * - Password and number fields are typed letter by letter in full mode ([predict] off). Email and
 *   URL fields predict as usual but don't auto-capitalise. With a screen reader exploring by
 *   touch, every field opens in full mode.
 * - Fields that ask not to be learned from (incognito tabs, IME_FLAG_NO_PERSONALIZED_LEARNING)
 *   still get suggestions, but nothing typed there is learned or unlearned ([learning] off).
 *   The same goes for fields that ask for no suggestions (TYPE_TEXT_FLAG_NO_SUGGESTIONS: user
 *   names, codes), where full mode also types exactly what was tapped ([exactField]).
 * - Copied text is kept for the clipboard panel ([captureClip]), except what is copied while a
 *   password, number or incognito field has focus ([privateField]), and never shown while the
 *   phone is locked.
 * - A word typed right after "word'" or "word-" is learned together with it ("zazzy's", see [Glue]).
 * - "##" then a letter or digit with a text shortcut saved for it types that text ([expandShortcut]).
 * - GIFs come from KLIPY through [GifFeed] and are inserted as rich content ([sendGif]). While the
 *   GIF search is open, the same typing (mini or full, suggestions and all) edits the query
 *   instead of the app's field ([editor], [onGifSearchOpen]).
 *
 * The dictionary loads on a background thread when the service starts; until then taps are shown
 * as the nearest letters and the bar stays empty.
 */
open class ContinuumIME : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    // ---- services and data ------------------------------------------------------------------

    private lateinit var prefs: SharedPreferences
    private lateinit var user: UserModel
    private lateinit var touch: TouchModel
    /** Setting "Learn how you tap": the decoder reads taps by [touch], and it keeps learning. */
    private var touchOn = Prefs.TOUCH_ADAPT_DEFAULT
    /**
     * The last committed word's taps, for [touch] once it's clear the word was kept ([flushTouch]),
     * and the taps that were deleted while typing it, each with the letter typed in its place.
     */
    private class PendingTouch(
        val layout: KeyLayout, val mode: Mode, val taps: List<Tap>, val key: String, val fixed: List<Pair<Tap, Char>>,
    )
    private var pendingTouch: PendingTouch? = null
    /**
     * Taps of the word being typed that backspace deleted, by their place in the word (the first
     * one deleted at each place). The letter the word ends up with there is what it was meant
     * for ([commitWord]). Forgotten when the word goes away without being committed.
     */
    private val deletedTaps = HashMap<Int, Tap>()

    private lateinit var stats: TypingStats
    /** The last committed word, for [stats] once it's clear it was kept ([flushTouch]) or taken back. */
    private class PendingWord(val mode: Mode, val outcome: TypingStats.Outcome, val split: Boolean)
    private var pendingWord: PendingWord? = null
    /**
     * The next committed word is a fix: a word was taken back or deleted, and this one is typed
     * (or picked) in its place.
     */
    private var fixing = false
    /** The bar shows a deleted word and its alternatives ([showDeletedWord]). */
    private var deletedShown = false
    private lateinit var appThemes: AppThemes
    private var currentPackage: String? = null
    @Volatile private var decoder: Decoder? = null
    private var dict: Dictionary? = null
    private lateinit var emojiHistory: EmojiHistory
    private lateinit var clipboard: ClipboardHistory
    private lateinit var stickerStore: StickerStore
    /** The stickers the panel shows, in its order (see [loadStickers]). */
    private var stickers: List<Sticker> = emptyList()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(fresh = true) }
    private var emojiGroups: List<EmojiGroup> = emptyList()
    /** Every emoji in [emojiGroups], i.e. the ones the font can draw. */
    private var drawableEmoji: Set<String> = emptySet()
    private lateinit var gifs: GifFeed
    private var view: KeyboardView? = null
    private val main = Handler(Looper.getMainLooper())

    // ---- settings and the current field --------------------------------------------------------

    /** The mode last switched to by gesture; [mode] is what's shown now (forced to full in password / number fields). */
    private var prefMode = Mode.MINI
    private var layout = KeyLayout.QWERTZ
    private var mode = Mode.MINI
    /** Decode words and show suggestions. Off in passwords and numbers. */
    private var predict = true
    /** Learn from what's typed. Off where the field asks for no personalised learning or no suggestions. */
    private var learning = true
    /** The field asks for no suggestions (a user name, a code): full mode types what was tapped, uncorrected. */
    private var exactField = false
    /**
     * The field that has focus is a password, number or incognito field. What is copied meanwhile
     * stays out of the clipboard history for good (see [captureClip]). Set as soon as the field
     * gets focus, keyboard showing or not, and cleared when it loses it.
     */
    private var privateField = false
    /** Setting "Clipboard history": off keeps nothing and shows nothing. */
    private var clipboardOn = Prefs.CLIPBOARD_DEFAULT
    /** Full mode's autocorrect switch; mini mode always uses the decoder's best word. */
    private var autocorrect = true
    private var autoCaps = true
    private var capsAllowed = true
    /** The field takes an email address (full mode then has "@" in place of ","). */
    private var emailField = false

    // ---- the word being typed -------------------------------------------------------------

    private val taps = ArrayList<Tap>()
    /** The mode the current word was started in (the decoder needs to know how to read its taps). */
    private var tapMode = Mode.MINI
    private var result: DecodeResult? = null
    /** What the suggestion bar shows, left to right; [defaultSlot] is what space commits. */
    private var slots = listOf("", "", "")
    /** Usually [DEFAULT_SLOT]; 0 while a reopened word leads the bar. */
    private var defaultSlot = DEFAULT_SLOT
    private var shift = Shift.OFF
    /** The current word starts a sentence (so a capital first letter is not part of its spelling). */
    private var wordSentenceStart = false
    private var lastShiftTap = 0L
    /**
     * Key of the word before the cursor, for the decoder and next-word predictions; null at the
     * start of a sentence. Read from the text ([refreshContext]) and, as words are committed,
     * kept in step by [learn].
     */
    private var prevWord: String? = null
    /** When we last set composing text; selection updates arriving sooner may be stale. */
    private var lastEditTime = 0L
    /**
     * Whether the field lets the keyboard read its text (see [refreshContext]): null until it has
     * answered with some.
     */
    private var textReadable: Boolean? = null
    /** The bar shows next-word predictions (not a word's alternatives, punctuation or a deleted word). */
    private var predictionsShown = false

    /**
     * The last word committed by space/suggestion, so backspace can reopen it for correction. [text]
     * is what was typed into the field, [learned] what was taught (with its [glue] prefix, if any).
     */
    private class LastCommit(
        val text: String, val spaced: Boolean, val taps: List<Tap>, val mode: Mode, val prevBefore: String?,
        val learned: String, val glue: Glue?, val sentenceStart: Boolean,
    )
    private var lastCommit: LastCommit? = null

    /**
     * A word that ended in an apostrophe or hyphen right before the cursor ("zazzy'"). The next word
     * typed is learned together with it ("zazzy's", key "zazzys"), replacing what was learned for
     * [prefix] on its own (the key [learnedKey]: "zazzy", or "m" for "mü"; null if nothing was);
     * [absorbed] records that this was undone already.
     */
    private class Glue(val prefix: String, val prevBefore: String?, val sentenceStart: Boolean, val learnedKey: String?) {
        var absorbed = false
    }
    /** The shortcut just expanded: [typed] ("##e") became [text]; a backspace right after undoes it. */
    private class Expansion(val typed: String, val text: String)
    private var lastExpansion: Expansion? = null

    /** The composing text the decoder last showed (what the word looks like in the field). */
    private var composingText = ""
    /**
     * While a word is being backspaced away: the word as it was when deleting started, then its
     * alternatives. Shown in the bar once the word is gone ([showDeletedWord]); typing ends it.
     */
    private var deletedSlots: List<String>? = null

    /**
     * The current word follows a number directly ("16" + "th"): the ordinal ending that number takes
     * ("st", "nd", "rd" or "th"), offered first and not autocorrected; null otherwise.
     */
    private var afterNumber: String? = null

    /** Set when a joiner is typed right after a word; [wordGlue] once the next word starts. */
    private var pendingGlue: Glue? = null
    private var wordGlue: Glue? = null
    /** True when the character before the cursor is a space we inserted automatically. */
    private var autoSpace = false
    /** The bar shows [PUNCTUATION_BAR] after a double-space period (see [showPunctuationBar]). */
    private var punctuationBar = false
    /** The next punctuation pick replaces the double-space "." (only the first one does). */
    private var replacePeriod = false

    // ---- GIF search -----------------------------------------------------------------------

    /** The GIF search text. It lives here so typing works on it as on any field (see [editor]). */
    private val query = SpannableStringBuilder()
    /** Edits [query]; set only while the search is open. */
    private var queryEditor: InputConnection? = null
    /** The app field's settings, put back when the search closes. */
    private class FieldState(
        val predict: Boolean, val capsAllowed: Boolean, val learning: Boolean, val prevWord: String?, val textReadable: Boolean?,
    )
    private var fieldState: FieldState? = null
    /** Searches as you type, once typing pauses for [LIVE_SEARCH_MS]. */
    private val liveSearch = Runnable { if (queryEditor != null) gifs.search(query.toString(), live = true) }

    // ---- service lifecycle ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Prefs.migrateAnimals(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        // The three files are read in the background; whoever needs one first waits for the rest of it.
        user = UserModel(File(filesDir, "user_model.tsv"), background = true)
        touch = TouchModel(File(filesDir, "touch_model.tsv"), DiskIo.executor)
        stats = TypingStats.get(this)
        emojiHistory = EmojiHistory(File(filesDir, "emoji_usage.tsv"), DiskIo.executor)
        clipboard = ClipboardHistory(File(filesDir, "clipboard.tsv"), io = DiskIo.executor)
        applyClipboardPrefs()
        stickerStore = StickerStore(this)
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
        appThemes = AppThemes(this)
        gifs = GifFeed(this, prefs)
        val loader = Executors.newSingleThreadExecutor()
        loader.execute {
            val d = Dictionary.load(this)
            main.post {
                dict = d
                decoder = Decoder(d, user)
                applyTouchPrefs()
                if (taps.isNotEmpty()) redecode() else showPredictions()
            }
            val groups = Emoji.groups(this)
            val drawable = groups.flatMapTo(HashSet()) { it.emoji }
            main.post {
                emojiGroups = groups
                drawableEmoji = drawable
                view?.emojiGroups = groups
            }
        }
        // The thread finishes loading, then ends instead of idling for the life of the service.
        loader.shutdown()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        gifs.shutdown()
        stickerStore.shutdown()
        main.removeCallbacksAndMessages(null)
        flushTouch()
        touch.save()
        stats.save()
        user.flush()
        super.onDestroy()
    }

    /** Android is short of memory: drop the GIF previews (unless they're on screen). */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW: still sent on the Android versions that have it.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        val v = view
        if (v != null && v.panel == KeyboardView.Panel.GIF) return
        v?.releaseGifs()
        gifs.release()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        view = v
        gifs.view = v
        v.emojiGroups = emojiGroups
        v.frequentEmoji = emojiHistory.top(FREQUENT_EMOJI)
        v.shift = shift
        v.emailField = emailField
        applyPrefs()
        return v
    }

    /** For tests: whether the dictionary has loaded, what the suggestion bar shows, the GIF search text. */
    internal val isReady: Boolean get() = decoder != null
    internal val suggestionSlots: List<String> get() = slots
    internal val gifQuery: String get() = query.toString()
    internal fun learnedCount(word: String): Int = Dictionary.keyOf(word)?.let { user.count(it) } ?: 0
    /** For tests: how [touch] has learned this user taps, in the current layout and mode. */
    internal fun touchShape(): TouchModel.Shape? = touch.shape(layout, mode)

    /** For tests: the clipboard history as kept, whether or not the panel may show it. */
    internal val clipTexts: List<String> get() = clipboard.texts()

    /** The editor connection; a seam so tests can drive the service against a real text field. */
    internal open fun inputConnection(): InputConnection? = currentInputConnection

    /** Where typing goes: the GIF search query while it's open ([onGifSearchOpen]), else the app. */
    private fun editor(): InputConnection? = queryEditor ?: inputConnection()

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The input view includes transparent space on top for the zoom popup (KeyboardView.overlayH).
     * Report only the keyboard below it, so apps lay out above the real keyboard and touches on
     * the transparent part reach the app.
     */
    private val windowLocation = IntArray(2)

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (!isInputViewShown || v.height == 0) return
        val loc = windowLocation
        v.getLocationInWindow(loc)
        val kbTop = loc[1] + v.overlayH.roundToInt()
        outInsets.contentTopInsets = kbTop
        outInsets.visibleTopInsets = kbTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, kbTop, v.width, loc[1] + v.height)
    }

    /** Settings changed (the settings screen writes them while the keyboard may be open). */
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.CLEAR_LEARNED) {
            user.clear()
            pendingTouch = null
            touch.clear()
        }
        applyPrefs()
    }

    /** Reads every setting and pushes the visual ones to the view. */
    private fun applyPrefs() {
        prefMode = if (prefs.getString(Prefs.MODE, "mini") == "full") Mode.FULL else Mode.MINI
        autocorrect = prefs.getBoolean(Prefs.AUTOCORRECT, Prefs.AUTOCORRECT_DEFAULT)
        autoCaps = prefs.getBoolean(Prefs.AUTOCAPS, Prefs.AUTOCAPS_DEFAULT)
        layout = KeyLayout.named(prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_DEFAULT))
        applyClipboardPrefs()
        applyTouchPrefs()
        val v = view ?: return
        v.layout = layout
        v.showGifs = prefs.getBoolean(Prefs.SHOW_GIFS, Prefs.SHOW_GIFS_DEFAULT)
        v.autocorrectOn = autocorrect
        val theme = KeyboardTheme.PRESETS[prefs.getString(Prefs.THEME, Prefs.THEME_CHAMELEON)]
            ?: appThemes.themeFor(currentPackage)
        v.theme = theme
        styleNavigationBar(theme)
        showSystemButtons(!prefs.getBoolean(Prefs.HIDE_SYSTEM_BUTTONS, true))
        v.miniScale = prefs.getInt(Prefs.MINI_HEIGHT, Prefs.MINI_HEIGHT_DEFAULT) / 100f
        v.fullScale = prefs.getInt(Prefs.FULL_HEIGHT, Prefs.FULL_HEIGHT_DEFAULT) / 100f
        v.barScale = prefs.getInt(Prefs.BAR_HEIGHT, Prefs.BAR_HEIGHT_DEFAULT) / 100f
        v.bottomSpaceDp = prefs.getInt(Prefs.BOTTOM_SPACE, Prefs.BOTTOM_SPACE_DEFAULT).toFloat()
        v.swipeMinDp = prefs.getInt(Prefs.SWIPE_MIN, Prefs.SWIPE_MIN_DEFAULT).toFloat()
        v.swipeLongDp = prefs.getInt(Prefs.SWIPE_LONG, Prefs.SWIPE_LONG_DEFAULT).toFloat()
        v.swipeSpeed = prefs.getInt(Prefs.SWIPE_SPEED, Prefs.SWIPE_SPEED_DEFAULT) / 100f
        v.swipeStraightness = prefs.getInt(Prefs.SWIPE_STRAIGHTNESS, Prefs.SWIPE_STRAIGHTNESS_DEFAULT) / 100f
        v.holdMs = prefs.getInt(Prefs.HOLD_MS, Prefs.HOLD_MS_DEFAULT).toLong()
        v.vibration = if (!prefs.getBoolean(Prefs.VIBRATE, Prefs.VIBRATE_DEFAULT)) 0 else
            prefs.getInt(Prefs.VIBRATE_STRENGTH, Prefs.VIBRATE_STRENGTH_DEFAULT)
        v.animalFrequency = AnimalFrequency.named(prefs.getString(Prefs.ANIMAL_FREQUENCY, null))
        v.animals = if (!prefs.getBoolean(Prefs.ANIMALS, Prefs.ANIMALS_DEFAULT)) emptyList() else {
            Species.picked(prefs.getStringSet(Prefs.ANIMAL_KINDS, null))
        }
    }

    /** "Learn how you tap": off, taps are read against the key centres and nothing more is learned. */
    private fun applyTouchPrefs() {
        touchOn = prefs.getBoolean(Prefs.TOUCH_ADAPT, Prefs.TOUCH_ADAPT_DEFAULT)
        if (!touchOn) pendingTouch = null
        decoder?.touch = if (touchOn) touch else null
    }

    /**
     * The last committed word was kept (another word started, or the field was left): its taps
     * now teach [touch] where this user's fingers land.
     */
    private fun flushTouch() {
        pendingWord?.let {
            stats.word(it.mode, it.outcome)
            if (it.split) stats.split(it.mode)
        }
        pendingWord = null
        val p = pendingTouch ?: return
        pendingTouch = null
        if (!touchOn) return
        var learned = 0
        if (touch.learn(p.layout, p.mode, p.taps, p.key)) learned += p.taps.count { it.exact < 0 }
        for ((tap, letter) in p.fixed) if (touch.learnTap(p.layout, p.mode, tap, letter)) learned++
        if (learned > 0) stats.tapsLearned(p.mode, learned)
    }

    /** The clipboard settings: with the history off, what was kept goes too. */
    private fun applyClipboardPrefs() {
        clipboardOn = prefs.getBoolean(Prefs.CLIPBOARD, Prefs.CLIPBOARD_DEFAULT)
        clipboard.keepMs = (prefs.getString(Prefs.CLIPBOARD_KEEP, null)?.toLongOrNull() ?: Prefs.CLIPBOARD_KEEP_DEFAULT)
            .coerceIn(1L, Prefs.CLIPBOARD_KEEP_DEFAULT) * HOUR_MS
        if (!clipboardOn) clipboard.clear()
    }

    // ---- editor lifecycle -----------------------------------------------------------------

    /** A password field (text or numeric). */
    private fun isPassword(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** A number, phone number or date field. */
    private fun isNumeric(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
    }

    /** A field whose content is private: a password, a number (codes, card numbers) or an incognito field. */
    private fun isPrivate(info: EditorInfo): Boolean =
        isPassword(info) || isNumeric(info) || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /** A field got focus (the keyboard may not be showing yet): note whether it is private. */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        privateField = attribute != null && isPrivate(attribute)
    }

    /** The keyboard is shown for a (new) text field: reset the word and adapt to the field's type. */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.closeGifSearch()
        privateField = isPrivate(info)
        captureClip() // something may have been copied while the keyboard wasn't running
        currentPackage = info.packageName
        applyPrefs()
        taps.clear()
        deletedTaps.clear()
        fixing = false
        result = null
        lastCommit = null
        lastExpansion = null
        autoSpace = false
        prevWord = null
        textReadable = null

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = isPassword(info)
        val address = cls == InputType.TYPE_CLASS_TEXT && variation in ADDRESS_VARIATIONS
        emailField = cls == InputType.TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS
        val numeric = isNumeric(info)

        predict = !(password || numeric)
        capsAllowed = predict && !address
        exactField = predict && cls == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        learning = predict && !exactField && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        // Exact typing (passwords, numbers) needs real keys, so those fields always use full mode.
        // So does a screen reader's touch exploration: mini mode works by rough taps and swipes,
        // which it can't offer, while every key of the full keyboard can be found and activated.
        mode = if (predict && !touchExploring()) prefMode else Mode.FULL
        pendingGlue = null
        wordGlue = null
        deletedSlots = null

        view?.let { v ->
            v.mode = mode
            if (numeric) v.showPanel(KeyboardView.Panel.SYMBOLS)
            v.enterAction = enterAction(info)
            v.emailField = emailField
        }
        // Caps lock stays on in the next field (it's still shown on shift); anything else starts over.
        if (shift != Shift.LOCK) setShift(Shift.OFF)
        updateAutoShift()
        showPredictions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.closeGifSearch()
        // The app may already have finished the composing text itself (it does on the back gesture);
        // committing the word again would then insert it a second time, so keep what's in the field.
        finishComposing(keepShown = true)
        user.save()
        flushTouch()
        touch.save()
        stats.save()
    }

    /** Whether a screen reader (TalkBack) is exploring the screen by touch. */
    private fun touchExploring(): Boolean =
        getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** The field lost focus (or its app went away). */
    override fun onFinishInput() {
        super.onFinishInput()
        taps.clear()
        deletedTaps.clear()
        result = null
        privateField = false
    }

    /** The cursor moved. If the user tapped elsewhere in the text, drop the word being typed. */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // While searching GIFs the word being typed is in the query, not the app's field.
        if (queryEditor != null) return
        if (taps.isEmpty()) {
            // The cursor may have been put somewhere else: once it rests, the bar's predictions follow it.
            if (predictionsShown && (newSelStart != oldSelStart || newSelEnd != oldSelEnd)) {
                main.removeCallbacks(followCursor)
                main.postDelayed(followCursor, FOLLOW_CURSOR_MS)
            }
            return
        }
        // Updates for our own edits can arrive late; only trust "composing vanished" once things settle.
        val composingGone = candidatesStart == -1 && SystemClock.uptimeMillis() - lastEditTime > STALE_UPDATE_MS
        val cursorOutside = candidatesStart != -1 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        if (composingGone || cursorOutside) {
            taps.clear()
            deletedTaps.clear()
            result = null
            editor()?.finishComposingText()
            lastCommit = null
            autoSpace = false
            updateAutoShift()
            showPredictions()
        }
    }

    // ---- KeyboardView.Listener ------------------------------------------------------------

    override fun onLetterTap(tap: Tap) {
        val upper = shift != Shift.OFF
        val t = tap.copy(upper = upper)
        lastExpansion = null
        if (taps.isEmpty() && expandShortcut('a' + nearestLetter(t))) return
        if (!predict) {
            val code = nearestLetter(t)
            val ch = ('a' + code).let { if (upper) it.uppercaseChar() else it }
            editor()?.commitText(ch.toString(), 1)
            autoSpace = false
            if (shift == Shift.ON) setShift(Shift.OFF)
            return
        }
        deletedSlots = null
        if (taps.isEmpty()) {
            lastCommit = null
            tapMode = mode
            wordSentenceStart = atSentenceStart()
            afterNumber = ordinalAfterCursor()
            wordGlue = pendingGlue?.takeIf {
                editor()?.getTextBeforeCursor(it.prefix.length, 0)?.toString() == it.prefix
            }
            pendingGlue = null
            refreshContext()
        }
        taps += t
        if (shift == Shift.ON) setShift(Shift.OFF)
        redecode()
    }

    /** Not in password or number fields, not while searching GIFs. */
    override fun animalMayVisit() = predict && queryEditor == null

    override fun onText(text: String) {
        val ic = editor() ?: return
        lastExpansion = null
        if (taps.isEmpty() && text.length == 1 && expandShortcut(text[0])) return
        val german = text.length == 1 && text[0] in GERMAN_LETTERS
        if (german) {
            // A German letter inside a word ("Gr" + "ü"): the letters so far stay exactly as shown,
            // and the rest of the word is typed as is after it (see Glue), not corrected apart.
            val before = pendingGlue?.takeIf { taps.isEmpty() && ic.getTextBeforeCursor(it.prefix.length, 0)?.toString() == it.prefix }
            if (taps.isNotEmpty()) commitWord(spaced = false, choice = null, keepShown = true)
            val last = lastCommit?.takeIf { !it.spaced }
            pendingGlue = when {
                last != null -> glueAfter(last, text)
                before != null -> Glue(before.prefix + text, before.prevBefore, before.sentenceStart, before.learnedKey)
                else -> Glue(text, prevWord, atSentenceStart(), null)
            }
            ic.commitText(text, 1)
            autoSpace = false
            lastCommit = null
            if (shift == Shift.ON) setShift(Shift.OFF)
            updateAutoShift()
            showPredictions()
            return
        }
        if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
        // "zazzy" + "'": the next word joins this one (see Glue).
        val joined = lastCommit?.takeIf { !it.spaced && text.length == 1 && text[0] in JOINERS }
        pendingGlue = joined?.let { glueAfter(it, text) }
        if (queryEditor == null && text.length == 1 && text[0] in '0'..'9') {
            // Digits go as key presses, like other keyboards do: one-time-code boxes often move to
            // the next box on the key event and ignore text committed without one.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (text[0] - '0'))
            autoSpace = false
        } else if (text.length == 1 && text[0] in ATTACHING_PUNCTUATION && autoSpace) {
            // "word |" + "." → "word. |"
            ic.beginBatchEdit()
            if (ic.getTextBeforeCursor(1, 0) == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$text ", 1)
            ic.endBatchEdit()
            autoSpace = true
        } else {
            ic.commitText(text, 1)
            autoSpace = false
        }
        lastCommit = null
        if (text.length == 1 && text[0] in SENTENCE_END) prevWord = null
        updateAutoShift()
        showPredictions()
    }

    /** Deletes the last tap, reopens the word just committed, or deletes a character. */
    override fun onBackspace() {
        val ic = editor() ?: return
        if (taps.isNotEmpty()) {
            rememberDeletedWord()
            val removed = taps.removeAt(taps.lastIndex)
            deletedTaps.putIfAbsent(taps.size, removed)
            if (taps.isEmpty()) {
                // The whole word went: what is typed next may be another word altogether (but
                // most likely the same one, typed again: a fix).
                deletedTaps.clear()
                fixing = true
                ic.commitText("", 1)
                result = null
                updateAutoShift()
                showDeletedWord()
            } else {
                redecode()
            }
            return
        }
        if (undoExpansion(ic)) return
        if (reopenLastCommit(ic)) return
        lastCommit = null
        autoSpace = false
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else deleteBefore(ic)
        updateAutoShift()
        showPredictions()
    }

    /**
     * Reopens the word just committed (if it's still right before the cursor) so another suggestion
     * can be picked, and takes back what committing it taught the user model.
     */
    private fun reopenLastCommit(ic: InputConnection): Boolean {
        val last = lastCommit ?: return false
        val expected = last.text + if (last.spaced) " " else ""
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        // Taken back: its taps don't say where the user's fingers land for those letters, and the
        // word typed or picked in its place is a fix.
        pendingTouch = null
        deletedTaps.clear()
        pendingWord?.let { if (it.split) stats.splitUndone(it.mode) }
        pendingWord = null
        fixing = true
        if (learning) unlearn(pairPrev(last.prevBefore, last.sentenceStart), last.learned)
        prevWord = last.prevBefore
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        afterNumber = ordinalAfterCursor()
        taps.clear()
        taps.addAll(last.taps)
        tapMode = last.mode
        wordGlue = last.glue
        lastCommit = null
        autoSpace = false
        redecode(forceText = last.text)
        ic.endBatchEdit()
        return true
    }

    /**
     * "##" right before the cursor and a shortcut saved for [key] (a letter or digit, either case):
     * replaces the "##" and the key with the shortcut's text. Not in password and number fields.
     */
    private fun expandShortcut(key: Char): Boolean {
        if (!predict || key !in Prefs.SHORTCUT_KEYS) return false
        val text = prefs.getString(Prefs.shortcut(key), null)
        if (text.isNullOrEmpty()) return false
        val ic = editor() ?: return false
        val trigger = Prefs.SHORTCUT_TRIGGER
        if (ic.getTextBeforeCursor(trigger.length, 0)?.toString() != trigger) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(trigger.length, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        lastExpansion = Expansion(trigger + key, text)
        lastCommit = null
        pendingGlue = null
        deletedSlots = null
        autoSpace = false
        if (shift == Shift.ON) setShift(Shift.OFF)
        updateAutoShift()
        showPredictions()
        return true
    }

    /** Backspace right after [expandShortcut]: puts back the "##e" that was typed. */
    private fun undoExpansion(ic: InputConnection): Boolean {
        val last = lastExpansion ?: return false
        lastExpansion = null
        if (ic.getTextBeforeCursor(last.text.length, 0)?.toString() != last.text) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(last.text.length, 0)
        ic.commitText(last.typed, 1)
        ic.endBatchEdit()
        updateAutoShift()
        showPredictions()
        return true
    }

    /** Swipe left: drops the word being typed, or deletes the word before the cursor. */
    override fun onDeleteWord() {
        val ic = editor() ?: return
        // A word just committed is reopened first, so its alternatives can be offered once it's gone.
        if (taps.isEmpty()) reopenLastCommit(ic)
        if (taps.isNotEmpty()) {
            rememberDeletedWord()
            taps.clear()
            deletedTaps.clear()
            fixing = true
            ic.commitText("", 1)
            result = null
            lastCommit = null
            autoSpace = false
            updateAutoShift()
            showDeletedWord()
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        // Punctuation after a word ("word." or "word?!") goes on its own; the next swipe takes the word.
        var punct = end
        while (punct > start && !before[punct - 1].isLetterOrDigit()) punct--
        if (punct in (start + 1) until end) start = punct
        val n = before.length - start
        val word = before.substring(start, end)
        if (n > 0) ic.deleteSurroundingText(n, 0) else deleteBefore(ic)
        lastCommit = null
        autoSpace = false
        updateAutoShift()
        deletedSlots = if (predict && n > 0) alternativesFor(word) else null
        if (n > 0) fixing = true
        showDeletedWord()
    }

    /** Commits the word being typed, or types a space; a second space after a word makes a full stop. */
    override fun onSpace() {
        val ic = editor() ?: return
        lastExpansion = null
        pendingGlue = null
        if (taps.isNotEmpty()) {
            commitWord(spaced = true, choice = null)
            return
        }
        // A space right after "word " makes it "word. ", however long ago the first one was
        // (not in passwords, emails or numbers).
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val doubled = predict && before.length == 2 && before[1] == ' ' &&
            !before[0].isWhitespace() && before[0] !in ATTACHING_PUNCTUATION
        if (doubled) {
            // Double space → ". "
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            prevWord = null
        } else {
            ic.commitText(" ", 1)
        }
        autoSpace = false
        lastCommit = null
        updateAutoShift()
        showPredictions()
        if (doubled) showPunctuationBar(replacePeriod = true)
    }

    /**
     * After a double-space period the bar offers other punctuation until a letter is typed. The
     * first pick replaces the "."; later picks add to it ("?" then "!" → "?! ").
     */
    private fun showPunctuationBar(replacePeriod: Boolean) {
        punctuationBar = true
        deletedShown = false
        predictionsShown = false
        this.replacePeriod = replacePeriod
        slots = PUNCTUATION_BAR
        view?.setSuggestions(slots, if (replacePeriod) PUNCTUATION_BAR.indexOf(".") else -1)
    }

    /** Deletes the character before the cursor (a key event for apps, which some fields need). */
    private fun deleteBefore(ic: InputConnection) {
        if (queryEditor != null) ic.deleteSurroundingText(1, 0) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Runs the field's action (search, send, go…) if it has one, otherwise types a newline. */
    override fun onEnter() {
        val ic = editor() ?: return
        lastExpansion = null
        if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
        if (queryEditor != null) {
            // Searching GIFs: show the results in the full grid.
            view?.closeGifSearch()
            onGifSearchClose()
            gifs.search(query.toString())
            return
        }
        val action = currentInputEditorInfo?.let(::enterAction) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoSpace = false
        lastCommit = null
        prevWord = null
        updateAutoShift()
        showPredictions()
    }

    /** Off → on (one capital) → double tap: caps lock → off. */
    override fun onShift() {
        val now = SystemClock.uptimeMillis()
        setShift(
            when (shift) {
                Shift.OFF -> Shift.ON
                Shift.ON -> if (now - lastShiftTap < DOUBLE_TAP_MS) Shift.LOCK else Shift.OFF
                Shift.LOCK -> Shift.OFF
            },
        )
        lastShiftTap = now
        if (taps.isEmpty() && !punctuationBar) showPredictions()
    }

    override fun onSuggestion(slot: Int) {
        val word = slots.getOrNull(slot).orEmpty()
        if (word.isEmpty()) return
        val ic = editor() ?: return
        lastExpansion = null
        val replaced = if (replacePeriod) ". " else " "
        if (punctuationBar && taps.isEmpty() && ic.getTextBeforeCursor(2, 0)?.toString()?.endsWith(replaced) == true) {
            // Swap the double-space "." (or add to the punctuation picked before): "word. " → "word? ".
            ic.beginBatchEdit()
            ic.deleteSurroundingText(replaced.length, 0)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
            autoSpace = true
            updateAutoShift()
            showPunctuationBar(replacePeriod = false)
            return
        }
        if (taps.isNotEmpty()) {
            // Words and emoji alike replace the word being typed.
            commitWord(spaced = true, choice = word)
        } else if (Dictionary.keyOf(word) == null) {
            // Punctuation predictions attach to the previous word like typed punctuation.
            onText(word)
        } else {
            // A next-word prediction, or a deleted word's alternative put back in its place.
            if (learning) {
                if (deletedShown) stats.word(mode, TypingStats.Outcome.FIXED) else stats.prediction(mode)
            }
            if (deletedShown) fixing = false
            val sentenceStart = atSentenceStart()
            ic.commitText("$word ", 1)
            learn(word, sentenceStart, picked = true)
            lastCommit = null
            autoSpace = true
            updateAutoShift()
            showPredictions()
        }
    }

    override fun onModeToggle() {
        // Password and number fields stay in full mode, also while searching GIFs from one.
        if (!predict || fieldState?.predict == false) {
            Toast.makeText(this, R.string.mode_locked, Toast.LENGTH_SHORT).show()
            return
        }
        finishComposing()
        val next = if (mode == Mode.MINI) Mode.FULL else Mode.MINI
        prefs.edit { putString(Prefs.MODE, if (next == Mode.FULL) "full" else "mini") }
        prefMode = next
        mode = next
        view?.mode = next
        showPredictions()
    }

    /** Full mode's bar switch. Mini mode always decodes, so it only affects full mode. */
    override fun onAutocorrectToggle() {
        autocorrect = !autocorrect
        prefs.edit { putBoolean(Prefs.AUTOCORRECT, autocorrect) }
        view?.autocorrectOn = autocorrect
        Toast.makeText(this, if (autocorrect) R.string.autocorrect_on else R.string.autocorrect_off, Toast.LENGTH_SHORT).show()
        if (taps.isNotEmpty()) redecode()
    }

    /** Removes a word from suggestions for good (see [UserModel.forget]). */
    override fun onForgetSuggestion(slot: Int) {
        val word = slots.getOrNull(slot).orEmpty()
        if (word.isEmpty()) return
        user.forget(Dictionary.keyOf(word), word)
        if (taps.isNotEmpty()) redecode() else showPredictions()
    }

    /** Types the emoji and counts it for the panel's "frequently used" section. */
    override fun onEmoji(emoji: String) {
        onText(emoji)
        if (learning) emojiHistory.use(emoji)
        view?.frequentEmoji = emojiHistory.top(FREQUENT_EMOJI)
    }

    // ---- clipboard ------------------------------------------------------------------------

    /**
     * Saves the system clipboard's text to the history. Skipped when the copying app marked it
     * sensitive (passwords, one-time codes; Android 13+ password managers do) and while a
     * password, number or incognito field has focus ([privateField]). Only plain text is kept: a
     * clip that is a link to another app's content is never opened.
     *
     * A clip stays on the system clipboard long after it was copied, and this is called again
     * later (a new field, the panel opening). So a clip copied in a private field ([fresh]: the
     * clipboard just changed) is set aside for good ([ClipboardHistory.skip]), not just for now.
     */
    private fun captureClip(fresh: Boolean = false) {
        if (!clipboardOn) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
        if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return
        val time = clip.description?.timestamp ?: 0L
        val hash = text.hashCode()
        if (privateField) {
            if (fresh) clipboard.skip(time, hash)
            return
        }
        // Copied anew outside a private field, the same text is wanted; seen again, it is still the private clip.
        if (!fresh && clipboard.isSkipped(time, hash)) return
        clipboard.add(text)
    }

    /** Why the clipboard panel has nothing to show (a string resource), or 0 if it may show the history. */
    private fun clipboardBlocked(): Int = when {
        !clipboardOn -> R.string.clip_off
        // Copied text is as private as what's on the phone: none of it while the phone is locked.
        runCatching { getSystemService(KeyguardManager::class.java)?.isDeviceLocked }.getOrNull() == true -> R.string.clip_locked
        else -> 0
    }

    private fun showClips() {
        val blocked = clipboardBlocked()
        if (blocked != 0) view?.setClips(emptyList(), getString(blocked)) else view?.setClips(clipboard.texts())
    }

    override fun onClipboardOpen() {
        captureClip()
        showClips()
    }

    override fun onClipPicked(index: Int) {
        if (clipboardBlocked() != 0) return showClips()
        val text = clipboard.texts().getOrNull(index) ?: return
        val ic = editor() ?: return
        if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
        ic.commitText(text, 1)
        autoSpace = false
        lastCommit = null
        updateAutoShift()
        showPredictions()
    }

    override fun onClipRemove(index: Int) {
        if (clipboardBlocked() == 0) clipboard.remove(index)
        showClips()
    }

    override fun onClipClear() {
        if (clipboardBlocked() == 0) clipboard.clear()
        showClips()
    }

    // ---- stickers ---------------------------------------------------------------------------

    override fun onStickersOpen() = loadStickers()

    /** Lists the stickers and hands the panel their thumbnails once they're decoded. */
    private fun loadStickers() {
        val list = stickerStore.list()
        stickers = list
        val px = (resources.displayMetrics.widthPixels / STICKER_THUMBS_ACROSS).coerceIn(64, StickerStore.SIZE)
        stickerStore.thumbnails(list, px) { images ->
            if (stickers === list) view?.setStickers(images, list.map { !it.builtIn })
        }
    }

    override fun onStickerPicked(index: Int) {
        stickers.getOrNull(index)?.let(::sendSticker)
    }

    override fun onStickerRemove(index: Int) {
        val s = stickers.getOrNull(index) ?: return
        stickerStore.remove(s) {
            Toast.makeText(this, R.string.sticker_removed, Toast.LENGTH_SHORT).show()
            if (view?.panel == KeyboardView.Panel.STICKERS) loadStickers()
        }
    }

    /** Opens the photo picker (through [AddStickerActivity]); the new sticker shows when the keyboard comes back. */
    override fun onStickerAdd() {
        finishComposing()
        val intent = Intent(this, AddStickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        runCatching { startActivity(intent) }
    }

    /**
     * Sends a sticker the way messaging apps expect images from a keyboard: a content URI (our
     * FileProvider) through [InputConnection.commitContent], as WebP where the app takes it (WhatsApp
     * then shows it as a sticker) or else PNG. Apps that take no images get Android's share sheet,
     * so the sticker can still be sent from there.
     */
    private fun sendSticker(s: Sticker) {
        finishComposing()
        val info = currentInputEditorInfo
        val types = info?.contentMimeTypes.orEmpty()
        fun accepts(mime: String) = types.any { ClipDescription.compareMimeTypes(mime, it) }
        val webp = accepts("image/webp")
        val inline = webp || accepts("image/png")
        stickerStore.forSending(s, webp = webp || !inline) { file ->
            if (file == null) {
                Toast.makeText(this, R.string.sticker_send_failed, Toast.LENGTH_SHORT).show()
                return@forSending
            }
            val mime = if (file.name.endsWith(".webp")) "image/webp" else "image/png"
            val uri = FileProvider.getUriForFile(this, packageName + GifFeed.AUTHORITY_SUFFIX, file)
            val ic = inputConnection()
            val sent = inline && ic != null && currentInputEditorInfo === info &&
                ic.commitContent(
                    InputContentInfo(uri, ClipDescription(s.name, arrayOf(mime)), null),
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
                )
            if (!sent) shareSticker(uri, mime)
        }
    }

    /** The app can't take the sticker inline: offer Android's share sheet for it instead. */
    private fun shareSticker(uri: android.net.Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        val chooser = Intent.createChooser(send, getString(R.string.sticker_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val ok = runCatching { startActivity(chooser) }.isSuccess
        Toast.makeText(this, if (ok) R.string.sticker_shared else R.string.sticker_send_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onPickInputMethod() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    /** Shift's ⚙ popup: finish the word and open the settings app. */
    override fun onOpenSettings() {
        finishComposing()
        requestHideSelf(0)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onGifSearch(query: String) = gifs.search(query)

    override fun onGifMore() = gifs.more()

    override fun onGifPicked(index: Int) {
        gifs.gif(index)?.let(::sendGif)
    }

    /**
     * The GIF search field was tapped: finish the app's word, then point typing at [query] with
     * suggestions on, no auto-capitals and nothing learned. [onGifSearchClose] undoes it.
     */
    override fun onGifSearchOpen() {
        if (queryEditor != null) return
        finishComposing()
        lastCommit = null
        fieldState = FieldState(predict, capsAllowed, learning, prevWord, textReadable)
        predict = true
        capsAllowed = false
        learning = false
        // The query is a text of its own: its words follow each other, not the app's last word.
        prevWord = null
        textReadable = null
        resetWordState()
        if (query.getSpans(0, query.length, TextWatcher::class.java).isEmpty()) {
            query.setSpan(queryWatcher, 0, query.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        Selection.setSelection(query, query.length)
        queryEditor = object : BaseInputConnection(view!!, true) {
            override fun getEditable(): Editable = query
        }
        if (shift != Shift.LOCK) setShift(Shift.OFF)
        view?.emailField = false
        showPredictions()
    }

    /** The search keyboard closed: the word in progress stays in the query, typing goes to the app again. */
    override fun onGifSearchClose() {
        if (queryEditor == null) return
        finishComposing()
        queryEditor?.finishComposingText()
        main.removeCallbacks(liveSearch)
        queryEditor = null
        fieldState?.let {
            predict = it.predict
            capsAllowed = it.capsAllowed
            learning = it.learning
            prevWord = it.prevWord
            textReadable = it.textReadable
        }
        fieldState = null
        view?.emailField = emailField
        resetWordState()
        updateAutoShift()
        showPredictions()
    }

    /** Shows the query in the search field and schedules a live search. */
    private val queryWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            view?.gifQuery = query.toString()
            main.removeCallbacks(liveSearch)
            if (queryEditor != null) main.postDelayed(liveSearch, LIVE_SEARCH_MS)
        }
    }

    /** Forgets the word being typed and what it was attached to (switching between the app and the query). */
    private fun resetWordState() {
        taps.clear()
        deletedTaps.clear()
        result = null
        lastCommit = null
        pendingGlue = null
        wordGlue = null
        deletedSlots = null
        autoSpace = false
    }

    /**
     * Inserts a GIF into the app the way messaging apps expect: as a content URI (our FileProvider)
     * through [InputConnection.commitContent], which most messengers turn into a GIF message ready to
     * send (some send it at once). Fields that don't accept GIFs get the GIF's link as text instead.
     */
    internal fun sendGif(gif: Gif) {
        // While searching, the word in progress belongs to the query; the app has none.
        if (queryEditor == null) finishComposing()
        val info = currentInputEditorInfo
        val acceptsGif = info?.contentMimeTypes?.any { ClipDescription.compareMimeTypes("image/gif", it) } == true
        // KlipyClient only lets https links through; checked again because this text goes into the app.
        if (!KlipyClient.isSafeUrl(gif.sendUrl)) return
        if (!acceptsGif) {
            val ic = inputConnection() ?: return
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            ic.commitText((if (before.isNotEmpty() && !before[0].isWhitespace()) " " else "") + gif.sendUrl, 1)
            autoSpace = false
            Toast.makeText(this, R.string.gif_link_pasted, Toast.LENGTH_SHORT).show()
            return
        }
        gifs.fetchForSending(gif) { file ->
            val ic = inputConnection()
            if (file == null || ic == null || currentInputEditorInfo !== info) {
                if (file == null) Toast.makeText(this, R.string.gif_send_failed, Toast.LENGTH_SHORT).show()
                return@fetchForSending
            }
            val uri = FileProvider.getUriForFile(this, packageName + GifFeed.AUTHORITY_SUFFIX, file)
            val content = InputContentInfo(uri, ClipDescription(gif.title, arrayOf("image/gif")), gif.sendUrl.toUri())
            if (!ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
                ic.commitText(gif.sendUrl, 1)
            }
        }
    }

    // ---- composing ------------------------------------------------------------------------

    /**
     * Decodes [taps] and updates the composing text and the suggestion bar. [forceText] keeps a
     * reopened word as it was committed instead of the decoder's best guess; it then leads the bar
     * (and stays what space commits), with the alternatives after it.
     */
    private fun redecode(forceText: String? = null) {
        val ic = editor() ?: return
        if (taps.isEmpty()) return
        val d = decoder
        // The end of a joined word ("zazzy'" + "s") or of a number ("16" + "th") is not two words.
        val r = d?.decode(
            taps, tapMode, layout, prevWord, splits = wordGlue == null && afterNumber == null,
            start = prevWord == null && wordSentenceStart,
        )
        result = r
        predictionsShown = false
        deletedShown = false
        val literal = r?.literal ?: buildString { for (t in taps) append('a' + nearestLetter(t)) }
        // The typed letters spelled the way the word is when that differs only in capitals ("iphone"
        // → "iPhone", "i" → "I", a learned "brownLlama"): no letter changes, so it is still exact.
        val spelled = r?.words?.firstOrNull {
            it.key == literal && it.display.length == literal.length && it.display.equals(literal, ignoreCase = true)
        }?.display ?: literal
        val literalText = applyCase(spelled)
        val words = r?.words.orEmpty().map { applyCase(it.display) }
        // Typo corrections take their place among the runners-up by score.
        val more = (r?.words.orEmpty().drop(1) + r?.completions.orEmpty() + r?.corrections.orEmpty())
            .sortedByDescending { it.score }
            .map { applyCase(it.display) }

        // Right after a number, its ordinal ending is what these letters most likely are ("16" + "th").
        val ordinal = afterNumber?.let { applyCase(it) }
        val best = if (ordinal != null && taps.size == ordinal.length) ordinal else words.firstOrNull() ?: literalText
        // Letters picked exactly with the zoom spell a word the dictionary may not know: when every
        // letter was picked that way, the spelling itself is the default (and gets learned).
        val allExact = taps.all { it.exact >= 0 }
        val anyExact = taps.any { it.exact >= 0 }
        val composing = forceText ?: when (tapMode) {
            Mode.MINI -> if (allExact) literalText else best
            Mode.FULL -> literalText
        }
        ic.setComposingText(composing, 1)
        composingText = composing
        lastEditTime = SystemClock.uptimeMillis()

        // A number spelling of the best word ("someone" → "some1") is offered next to it.
        val speak = TEXT_SPEAK[best.lowercase()]?.let { v -> if (best.first().isUpperCase()) v.replaceFirstChar { it.uppercaseChar() } else v }
        val others = (listOfNotNull(best, ordinal, speak) + more).distinct().filter { it != composing }
        // The bar shows as many words as fit and scrolls sideways to the rest.
        var list = when {
            // Reopened by backspace: the word as it was, then what else the taps could be.
            forceText != null -> {
                val typed = if (tapMode == Mode.FULL || anyExact) listOf(literalText) else emptyList()
                (listOf(forceText) + typed + others).distinct()
            }
            // Mini: best guess in the middle, runners-up either side, more to the right.
            // Partly exact spellings are offered next to the best guess.
            tapMode == Mode.MINI -> {
                val spelled = if (anyExact && literalText != composing) listOf(literalText) else emptyList()
                val ranked = (listOf(composing) + spelled + others).distinct()
                centerOut(ranked.take(VISIBLE_SLOTS), VISIBLE_SLOTS) + ranked.drop(VISIBLE_SLOTS)
            }
            // Full: what you typed on the left, the autocorrection in the middle.
            else -> {
                val pick = autocorrectPick(r, literalText, words)
                val rest = (listOf(pick) + others).distinct().filter { it != literalText && it != pick }
                if (pick == literalText) listOf(rest.getOrElse(0) { "" }, literalText) + rest.drop(1)
                else listOf(literalText, pick) + rest
            }
        }
        defaultSlot = if (forceText != null) 0 else DEFAULT_SLOT
        // Emoji for the best word (CLDR keywords, if the font can draw it) goes right after it.
        val emojiAfter = if (forceText != null) list.indexOf(best) else DEFAULT_SLOT
        r?.words?.firstOrNull()?.emoji?.takeIf { emojiAfter >= 0 && it in drawableEmoji }?.let { e ->
            list = list.take(emojiAfter + 1) + e + list.drop(emojiAfter + 1)
        }
        slots = pad(list.take(MAX_SUGGESTIONS))
        view?.setSuggestions(slots, defaultSlot)
    }

    /** At least [VISIBLE_SLOTS] entries, so the bar's fixed slots always line up. */
    private fun pad(list: List<String>) = list + List(maxOf(0, VISIBLE_SLOTS - list.size)) { "" }

    /** Place ranked words so the best is in the middle slot, then alternating left / right. */
    private fun centerOut(ranked: List<String>, n: Int): List<String> {
        val out = MutableList(n) { "" }
        val c = n / 2
        for (rank in 0 until minOf(n, ranked.size)) {
            val off = (rank + 1) / 2
            out[if (rank % 2 == 1) c - off else c + off] = ranked[rank]
        }
        return out
    }

    /** Full mode: the word space will commit. Keeps real words unless the alternative is far more likely. */
    private fun autocorrectPick(r: DecodeResult?, literalText: String, words: List<String>): String {
        // The end of a joined word ("zazzy'" + "s") is typed as is, and so is a field that asks for no
        // suggestions (but not the GIF search opened from one).
        if (exactField && queryEditor == null) return literalText
        if (!autocorrect || r == null || wordGlue != null || afterNumber != null || taps.any { it.exact >= 0 }) return literalText
        val best = r.words.firstOrNull() ?: return literalText
        if (best.key == r.literal) {
            // The same letters with an apostrophe, hyphen or space ("dont" → "don't", "zazzys" →
            // "zazzy's", "thankyou" → "thank you") or German letters ("munchen" → "münchen"),
            // unless what was typed is a word as it stands ("its"; not a known misspelling like
            // "ofthe", which the word list also holds).
            val typedIsWord = r.literalIsWord &&
                r.words.any { it.key == r.literal && it.display.equals(r.literal, ignoreCase = true) }
            val respelled = best.display.any { !it.isLetter() || it in GERMAN_LETTERS }
            return if (!typedIsWord && respelled) words.first() else literalText
        }
        if (r.literalIsWord && best.score - r.literalScore < KEEP_REAL_WORD_MARGIN) return literalText
        if (taps.size == 1) return literalText
        return words.first()
    }

    /**
     * Commits [choice], or the default suggestion, in place of the composing text, and learns it.
     * [keepShown] instead leaves the composing text in the field as it is, without retyping it.
     */
    private fun commitWord(spaced: Boolean, choice: String?, keepShown: Boolean = false) {
        val ic = editor() ?: return
        val text = if (keepShown && composingText.isNotEmpty()) composingText
            else choice ?: slots.getOrNull(defaultSlot)?.takeIf { it.isNotEmpty() }
                ?: applyCase(result?.literal ?: "")
        if (keepShown) ic.finishComposingText() else ic.commitText(text + if (spaced) " " else "", 1)
        // A joined word replaces what was learned for its first part: "zazzy" + "'s" → "zazzy's".
        val g = wordGlue?.takeIf { Dictionary.keyOf(text) != null }
        if (g != null && !g.absorbed) {
            if (learning) g.learnedKey?.let { user.unlearn(pairPrev(g.prevBefore, g.sentenceStart), it) }
            g.absorbed = true
        }
        if (g != null) prevWord = g.prevBefore
        val learned = if (g != null) g.prefix + text else text
        val prevBefore = prevWord
        // An ordinal ending ("th" after "16") isn't a word of its own to learn.
        if (afterNumber == null) learn(learned, g?.sentenceStart ?: wordSentenceStart, picked = choice != null)
        // Where the taps landed counts once this word is kept (see flushTouch).
        flushTouch()
        val typedKey = Dictionary.keyOf(text)
        // What became of the word counts once the next one starts (see flushTouch).
        if (learning) {
            val outcome = when {
                fixing || deletedTaps.isNotEmpty() -> TypingStats.Outcome.FIXED
                choice != null -> TypingStats.Outcome.PICKED
                else -> TypingStats.Outcome.KEPT
            }
            pendingWord = PendingWord(tapMode, outcome, ' ' in text)
        }
        fixing = false
        if (learning && touchOn && typedKey != null) {
            // A tap deleted while typing was meant for the letter the word has there now (if the
            // word's letters still line up with its taps).
            val fixed = if (typedKey.length != taps.size) emptyList() else
                deletedTaps.mapNotNull { (at, tap) -> typedKey.getOrNull(at)?.let { tap to it } }
            pendingTouch = PendingTouch(layout, tapMode, ArrayList(taps), typedKey, fixed)
        }
        deletedTaps.clear()
        lastCommit = LastCommit(text, spaced, ArrayList(taps), tapMode, prevBefore, learned, g, g?.sentenceStart ?: wordSentenceStart)
        wordGlue = null
        taps.clear()
        result = null
        autoSpace = spaced
        updateAutoShift()
        showPredictions()
    }

    /** Commits a word in progress as it stands (when the keyboard closes or switches mode). */
    private fun finishComposing(keepShown: Boolean = false) {
        if (taps.isEmpty()) return
        commitWord(spaced = false, choice = null, keepShown = keepShown)
        lastCommit = null
    }

    /**
     * Records a committed word, and how the user spells it. Only capitals that say something about
     * the word change that spelling: a capital starting a sentence doesn't ("Zorp is…" stays "zorp"),
     * nor do all caps (caps lock, "NEVER"). Only a capital inside a word is shown without shift
     * ("brownLlama"; the first letter's case is the dictionary's, see Decoder.shownForm).
     */
    private fun learn(word: String, sentenceStart: Boolean, picked: Boolean = false) {
        // Two words typed as one ("thank you" for "thankyou") are learned one after the other.
        if (' ' in word) {
            word.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, w -> learn(w, sentenceStart && i == 0) }
            return
        }
        // An emoji (or anything without letters) is not learned, and the word before it no longer counts.
        // Nor is a word with digits ("some1"): its key would drop those and match other words. A
        // German word is learned under its base letters ("münchen" as "munchen", see keyOf).
        val key = Dictionary.keyOf(word)?.takeIf { word.none { it.isDigit() } } ?: run { prevWord = null; return }
        // Fields that ask for no learning (incognito) only track the previous word, for predictions.
        if (!learning) {
            prevWord = key
            return
        }
        user.unblock(word)
        // null keeps the spelling learned before (or the dictionary's).
        val form = when {
            word.none { it.isUpperCase() } -> word
            // All caps is caps lock (or emphasis): capitals only ever come from shift.
            word.count { it.isLetter() } > 1 && word.none { it.isLowerCase() } -> null
            sentenceStart && word.drop(1).none { it.isUpperCase() } -> null
            else -> word
        }
        user.learn(pairPrev(prevWord, sentenceStart), key, form)
        // Picking a new word from the bar confirms it, as typing it a second time would.
        if (picked) user.confirm(key)
        prevWord = key
    }

    /**
     * The word a word is learned after: [prev], or at the start of a sentence, the sentence start
     * ([UserModel.START]), so the words the user begins with are learned as well.
     */
    private fun pairPrev(prev: String?, sentenceStart: Boolean) = prev ?: if (sentenceStart) UserModel.START else null

    /** Takes back what [learn] taught for [text] typed after [prev] (each word of it, if it is two). */
    private fun unlearn(prev: String?, text: String) {
        var before = prev
        for (word in text.split(' ')) {
            val key = Dictionary.keyOf(word) ?: continue
            user.unlearn(before, key)
            before = key
        }
    }

    /**
     * The glue for a joiner or German letter typed right after the commit [last]: the next word
     * joins its (last) word, and replaces what was learned for that.
     */
    private fun glueAfter(last: LastCommit, text: String): Glue {
        val words = last.learned.split(' ')
        if (words.size < 2) return Glue(last.learned + text, last.prevBefore, wordSentenceStart, Dictionary.keyOf(last.learned))
        return Glue(words.last() + text, Dictionary.keyOf(words[words.size - 2]), false, Dictionary.keyOf(words.last()))
    }

    /**
     * Sets [prevWord] from the text before the cursor ([TextContext]). Fields that don't let the
     * keyboard read their text (some terminals and games answer with nothing) keep the word that
     * was typed last instead: an empty answer only counts once the field has shown it can answer.
     */
    private fun refreshContext() {
        if (textReadable == false) return
        val before = editor()?.getTextBeforeCursor(TextContext.WINDOW, 0) ?: return
        if (before.isNotEmpty()) textReadable = true else if (textReadable == null) return
        prevWord = TextContext.previousWord(before)
    }

    /** The cursor was moved while nothing was being typed: fill the bar for where it is now. */
    private val followCursor = Runnable {
        if (taps.isEmpty() && queryEditor == null && predictionsShown) showPredictions()
    }

    /** Snapshots the word and its alternatives before the first deletion of a run (see [deletedSlots]). */
    private fun rememberDeletedWord() {
        if (deletedSlots != null || composingText.isEmpty()) return
        deletedSlots = (listOf(composingText) + slots.filter { it.isNotEmpty() }).distinct()
    }

    /** The word was deleted: offer it again, then its alternatives (nothing is the default). */
    private fun showDeletedWord() {
        val list = deletedSlots
        deletedSlots = null
        if (list == null || !predict) return showPredictions()
        punctuationBar = false
        predictionsShown = false
        deletedShown = true
        slots = pad(list.take(MAX_SUGGESTIONS))
        view?.setSuggestions(slots, -1)
    }

    /** Next-word predictions for the empty bar (does nothing while a word is being typed). */
    private fun showPredictions() {
        punctuationBar = false
        deletedShown = false
        if (taps.isNotEmpty()) return
        predictionsShown = false
        if (predict) refreshContext()
        val v = view ?: return
        val d = decoder
        if (!predict || d == null) {
            slots = listOf("", "", "")
            v.setSuggestions(slots, -1)
            return
        }
        predictionsShown = true
        // Lowercase unless shift is on, like typed words (see applyCase).
        val words = d.predict(prevWord, sentenceStart = shift != Shift.OFF, limit = MAX_SUGGESTIONS).map {
            val plain = plainCase(it)
            when (shift) {
                Shift.OFF -> plain
                Shift.ON -> plain.replaceFirstChar { c -> c.uppercaseChar() }
                Shift.LOCK -> plain.uppercase(Locale.ROOT)
            }
        }
        // Left to right by likelihood ("the | I | in").
        slots = pad(words)
        v.setSuggestions(slots, -1)
    }

    /**
     * Applies the shift state of the taps to a suggestion. Capitals come only from shift: the word
     * is lowercase but for capitals inside it ([plainCase]: "london", "iPhone"), then all caps if
     * every letter was shifted, else each letter typed with shift is a capital (when the word's
     * letters line up with the taps), else just the first letter if that was shifted.
     */
    private fun applyCase(word: String): String {
        if (word.isEmpty() || taps.isEmpty()) return word
        val plain = plainCase(word)
        if (taps.size > 1 && taps.all { it.upper }) return plain.uppercase(Locale.ROOT)
        if (plain.count { it.isLetter() } == taps.size) {
            var t = 0
            return String(CharArray(plain.length) { i ->
                val ch = plain[i]
                if (ch.isLetter() && taps[t++].upper) ch.uppercaseChar() else ch
            })
        }
        return if (taps[0].upper) plain.replaceFirstChar { it.uppercaseChar() } else plain
    }

    /**
     * How a word is spelled when shift wasn't pressed: lowercase ("hey", "london", even if it was
     * learned or listed with a capital), except a word with a capital inside it ("iPhone",
     * "McDonald", one the user typed that way), and "I" and its contractions ("I'm", "I'll", "I'd",
     * "I've"), which always have a capital I. Two words ("am I") are each treated so.
     */
    private fun plainCase(word: String): String = word.split(' ')
        .joinToString(" ") { w -> if (w.drop(1).any { it.isUpperCase() }) w else w.lowercase(Locale.ROOT) }
        .replace(LONE_I, "I")

    /** The letter (0..25) closest to a tap, ignoring word likelihood. */
    private fun nearestLetter(t: Tap): Int {
        if (t.exact >= 0) return t.exact
        var best = 0
        var bestD = Float.MAX_VALUE
        for (c in 0 until 26) {
            val d = when (mode) {
                Mode.MINI -> (t.x - layout.miniSlot[c]).let { it * it }
                Mode.FULL -> (t.x - layout.fullX[c]).let { it * it } + (t.y - layout.fullY[c]).let { it * it }
            }
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    // ---- window ---------------------------------------------------------------------------

    /** Re-applies the system-buttons setting, which only takes effect on a visible window. */
    override fun onWindowShown() {
        super.onWindowShown()
        showSystemButtons(!prefs.getBoolean(Prefs.HIDE_SYSTEM_BUTTONS, true))
        view?.keyboardShown()
        // Back from adding a sticker: show the new one.
        if (view?.panel == KeyboardView.Panel.STICKERS) loadStickers()
    }

    /** The keyboard went away: stop the animals, and give back the memory of the GIFs it was showing. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        view?.keyboardHidden()
        view?.releaseGifs(force = true)
        gifs.release()
    }

    /**
     * The system draws its IME switcher and hide-keyboard buttons in a bar under the keys, which
     * reports itself as the caption bar. Since Android 16 an IME may hide it; switching keyboards
     * is then a long press on the space bar.
     */
    private fun showSystemButtons(show: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val c = window?.window?.insetsController ?: return
        if (show) c.show(WindowInsets.Type.captionBar()) else c.hide(WindowInsets.Type.captionBar())
    }

    /** Matches the navigation bar area under the keyboard (and its button colours) to the theme. */
    private fun styleNavigationBar(theme: KeyboardTheme) {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = theme.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.insetsController?.setSystemBarsAppearance(
                if (theme.isLight) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    // ---- shift ----------------------------------------------------------------------------

    private fun setShift(s: Shift) {
        shift = s
        view?.shift = s
    }

    /** Capitalise at the start of a field and after sentence-ending punctuation. */
    private fun updateAutoShift() {
        if (shift == Shift.LOCK) return
        if (!autoCaps || !capsAllowed || taps.isNotEmpty()) {
            if (taps.isEmpty() && shift == Shift.ON && !autoCaps) setShift(Shift.OFF)
            return
        }
        val ic = editor() ?: return
        setShift(if (atSentenceStart(ic)) Shift.ON else Shift.OFF)
    }

    /** The ordinal ending for the number right before the cursor ("1" → "st", "12" → "th"), or null. */
    private fun ordinalAfterCursor(): String? {
        val before = editor()?.getTextBeforeCursor(8, 0)?.toString() ?: return null
        val digits = before.takeLastWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val n = digits.takeLast(2).toInt()
        return when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }

    /**
     * Swipe-deleting a finished word: its alternatives, as the decoder reads the word's own letters
     * (the taps behind it are gone), so a slip like "your" for "some" can be picked again. The word
     * itself comes first.
     */
    private fun alternativesFor(word: String): List<String>? {
        val d = decoder ?: return null
        val letters = word.lowercase().filter { it in 'a'..'z' }
        if (letters.isEmpty() || letters.length > 20 || word.any { !it.isLetter() && it !in JOINERS }) return null
        val synthetic = letters.map { c ->
            val i = c - 'a'
            if (mode == Mode.MINI) Tap(layout.miniSlot[i].toFloat(), 0f) else Tap(layout.fullX[i], layout.fullY[i])
        }
        val r = d.decode(synthetic, mode, layout, null, adapt = false)
        val cap = word.first().isUpperCase()
        val alts = (r.words + r.corrections + r.completions).sortedByDescending { it.score }
            .map { c -> plainCase(c.display).let { w -> if (cap) w.replaceFirstChar { it.uppercaseChar() } else w } }
        return (listOf(word) + alts).distinct()
    }

    /** The cursor is at the start of the field, a line, or a sentence ("Done. |"). */
    private fun atSentenceStart(ic: InputConnection? = editor()): Boolean {
        val before = ic?.getTextBeforeCursor(3, 0)?.toString() ?: return false
        val trimmed = before.trimEnd(' ')
        return trimmed.isEmpty() && before.length < 3 ||
            trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END && before.endsWith(" ") ||
            before.endsWith("\n")
    }

    /** The field's enter action (drawn on the enter key and run by [onEnter]), or NONE for a newline. */
    private fun enterAction(info: EditorInfo): Int {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return EditorInfo.IME_ACTION_NONE
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (Android 13); the same key works as a plain extra before it. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val VISIBLE_SLOTS = 3
        /** The middle slot holds the word space will commit. */
        private const val DEFAULT_SLOT = 1
        private const val MAX_SUGGESTIONS = 12
        /** Four rows of the emoji panel's "frequently used" section. */
        private const val FREQUENT_EMOJI = 32
        /** Sticker thumbnails are decoded about a quarter of the screen width across (the panel has 4 columns). */
        private const val STICKER_THUMBS_ACROSS = 4
        /** Two shift taps within this time turn on caps lock. */
        private const val DOUBLE_TAP_MS = 350L
        /** Full-mode autocorrect keeps a real typed word unless a correction scores this much better (log). */
        private const val KEEP_REAL_WORD_MARGIN = 5f
        private const val STALE_UPDATE_MS = 300L
        /** How long the cursor must rest before the bar's predictions are redone for its new place. */
        private const val FOLLOW_CURSOR_MS = 150L
        private const val HOUR_MS = 60L * 60 * 1000
        /** GIF search: how long typing must pause before the results update. */
        private const val LIVE_SEARCH_MS = 600L
        /** Punctuation that sticks to the previous word, taking the space before it. */
        private const val ATTACHING_PUNCTUATION = ".,!?;:"
        private const val SENTENCE_END = ".!?"
        /** "i" as a word of its own (or before an apostrophe: "i'm"), not inside one ("in"). */
        private val LONE_I = Regex("(?<!\\p{L})i(?!\\p{L})")
        /** Characters that join two parts into one word ("zazzy's", "self-aware"). */
        private const val JOINERS = "'’-"
        /** Number spellings offered next to a word ("someone" → "some1"). */
        private val TEXT_SPEAK = mapOf(
            "someone" to "some1", "anyone" to "any1", "everyone" to "every1", "noone" to "no1",
            "today" to "2day", "tonight" to "2nite", "tomorrow" to "2moro", "before" to "b4",
            "forever" to "4ever", "great" to "gr8", "later" to "l8r", "wait" to "w8", "mate" to "m8",
        )
        /** Typed from a longer hold on a, o, u or s; they join the word around them (see onText). */
        private const val GERMAN_LETTERS = "äöüÄÖÜß"
        /** Shown after a double-space period, "." highlighted; the bar scrolls to the rest. */
        private val PUNCTUATION_BAR = listOf("!", ".", "?", ",", ";", ":", "/")

        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        private val EMAIL_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        private val ADDRESS_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}

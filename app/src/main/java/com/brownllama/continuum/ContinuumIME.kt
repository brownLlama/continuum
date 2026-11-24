package com.brownllama.continuum

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.brownllama.continuum.clipboard.ClipboardHistory
import com.brownllama.continuum.gif.Gif
import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.keyboard.Emoji
import com.brownllama.continuum.keyboard.EmojiGroup
import com.brownllama.continuum.keyboard.EmojiHistory
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.DecodeResult
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Language
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.TouchModel
import com.brownllama.continuum.prediction.UserModel
import com.brownllama.continuum.sticker.AddStickerActivity
import com.brownllama.continuum.sticker.Sticker
import com.brownllama.continuum.sticker.StickerStore
import com.brownllama.continuum.stats.TypingStats
import com.brownllama.continuum.theme.AppThemes
import java.io.File
import java.util.EnumMap
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
 *
 * This file holds the state, the lifecycle and the [KeyboardView.Listener] entry points; most of the
 * work is in extension functions next to it: ImeEditing (what keys do to the text), ImeComposing
 * (decoding, committing, learning, the bar), ImeSettings, ImeShift, ImeClipboardStickers and
 * ImeGifSearch, with the small records in ImeTypes and the numbers in ImeConstants.
 */
open class ContinuumIME : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    // ---- services and data ------------------------------------------------------------------

    internal lateinit var prefs: SharedPreferences
    /**
     * The language typed in now (the swipe up and left, or Settings → Typing → Language): its word
     * list ([dict]) and its own learned words ([user]). What is typed in one language is never
     * learned in the other, and each keeps its own words.
     */
    internal var language = Language.EN
    internal val users = EnumMap<Language, UserModel>(Language::class.java)
    internal lateinit var user: UserModel
    internal lateinit var touch: TouchModel
    /** Setting "Learn how you tap": the decoder reads taps by [touch], and it keeps learning. */
    internal var touchOn = Prefs.TOUCH_ADAPT_DEFAULT
    internal var pendingTouch: PendingTouch? = null
    /**
     * Taps of the word being typed that backspace deleted, by their place in the word (the first
     * one deleted at each place). The letter the word ends up with there is what it was meant
     * for ([commitWord]). Forgotten when the word goes away without being committed.
     */
    internal val deletedTaps = HashMap<Int, Tap>()

    internal lateinit var stats: TypingStats
    internal var pendingWord: PendingWord? = null
    /**
     * The next committed word is a fix: a word was taken back or deleted, and this one is typed
     * (or picked) in its place.
     */
    internal var fixing = false
    /** The bar shows a deleted word and its alternatives ([showDeletedWord]). */
    internal var deletedShown = false
    internal lateinit var appThemes: AppThemes
    internal var currentPackage: String? = null
    /** Word-list loads under way (main thread); [ensureDictionary] reads it again only when none is. */
    internal var dictLoads = 0
    /** A text field has the keyboard (between [onStartInputView] and [onFinishInputView]). */
    internal var typingActive = false
    /** Saves what was learned, a moment after the last new word (see [SAVE_DELAY_MS]). */
    internal val saveLearned = Runnable { user.save() }
    @Volatile internal var decoder: Decoder? = null
    internal var dict: Dictionary? = null
    internal lateinit var emojiHistory: EmojiHistory
    internal lateinit var clipboard: ClipboardHistory
    internal lateinit var stickerStore: StickerStore
    /** The stickers the panel shows, in its order (see [loadStickers]). */
    internal var stickers: List<Sticker> = emptyList()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(fresh = true) }
    private var emojiGroups: List<EmojiGroup> = emptyList()
    /** Every emoji in [emojiGroups], i.e. the ones the font can draw. */
    internal var drawableEmoji: Set<String> = emptySet()
    internal lateinit var gifs: GifFeed
    internal var view: KeyboardView? = null
    internal val main = Handler(Looper.getMainLooper())

    // ---- settings and the current field --------------------------------------------------------

    /** The mode last switched to by gesture; [mode] is what's shown now (forced to full in password / number fields). */
    internal var prefMode = Mode.MINI
    internal var layout = KeyLayout.QWERTZ
    internal var mode = Mode.MINI
    /** Decode words and show suggestions. Off in passwords and numbers. */
    internal var predict = true
    /** Learn from what's typed. Off where the field asks for no personalised learning or no suggestions. */
    internal var learning = true
    /** The field asks for no suggestions (a user name, a code): full mode types what was tapped, uncorrected. */
    internal var exactField = false
    /**
     * The field that has focus is a password, number or incognito field. What is copied meanwhile
     * stays out of the clipboard history for good (see [captureClip]). Set as soon as the field
     * gets focus, keyboard showing or not, and cleared when it loses it.
     */
    internal var privateField = false
    /** Setting "Clipboard history": off keeps nothing and shows nothing. */
    internal var clipboardOn = Prefs.CLIPBOARD_DEFAULT
    /** Full mode's autocorrect switch; mini mode always uses the decoder's best word. */
    internal var autocorrect = true
    internal var autoCaps = true
    internal var capsAllowed = true
    /** The field takes an email address (full mode then has "@" in place of ","). */
    internal var emailField = false

    // ---- the word being typed -------------------------------------------------------------

    internal val taps = ArrayList<Tap>()
    /** The mode the current word was started in (the decoder needs to know how to read its taps). */
    internal var tapMode = Mode.MINI
    internal var result: DecodeResult? = null
    /** What the suggestion bar shows, left to right; [defaultSlot] is what space commits. */
    internal var slots = listOf("", "", "")
    /** Usually [DEFAULT_SLOT]; 0 while a reopened word leads the bar. */
    internal var defaultSlot = DEFAULT_SLOT
    internal var shift = Shift.OFF
    /** The current word starts a sentence (so a capital first letter is not part of its spelling). */
    internal var wordSentenceStart = false
    internal var lastShiftTap = 0L
    /**
     * Key of the word before the cursor, for the decoder and next-word predictions; null at the
     * start of a sentence. Read from the text ([refreshContext]) and, as words are committed,
     * kept in step by [learn].
     */
    internal var prevWord: String? = null
    /** When we last set composing text; selection updates arriving sooner may be stale. */
    internal var lastEditTime = 0L
    /**
     * Whether the field lets the keyboard read its text (see [refreshContext]): null until it has
     * answered with some.
     */
    internal var textReadable: Boolean? = null
    /** The bar shows next-word predictions (not a word's alternatives, punctuation or a deleted word). */
    internal var predictionsShown = false

    internal var lastCommit: LastCommit? = null

    internal var lastExpansion: Expansion? = null

    /** The composing text the decoder last showed (what the word looks like in the field). */
    internal var composingText = ""
    /**
     * While a word is being backspaced away: the word as it was when deleting started, then its
     * alternatives. Shown in the bar once the word is gone ([showDeletedWord]); typing ends it.
     */
    internal var deletedSlots: List<String>? = null

    /**
     * The current word follows a number directly ("16" + "th"): the ordinal ending that number takes
     * ("st", "nd", "rd" or "th"), offered first and not autocorrected; null otherwise.
     */
    internal var afterNumber: String? = null

    /** Set when a joiner is typed right after a word; [wordGlue] once the next word starts. */
    internal var pendingGlue: Glue? = null
    internal var wordGlue: Glue? = null
    /** True when the character before the cursor is a space we inserted automatically. */
    internal var autoSpace = false
    /** The bar shows [PUNCTUATION_BAR] after a double-space period (see [showPunctuationBar]). */
    internal var punctuationBar = false
    /** The next punctuation pick replaces the double-space "." (only the first one does). */
    internal var replacePeriod = false

    // ---- GIF search -----------------------------------------------------------------------

    /** The GIF search text. It lives here so typing works on it as on any field (see [editor]). */
    internal val query = SpannableStringBuilder()
    /** Edits [query]; set only while the search is open. */
    internal var queryEditor: InputConnection? = null
    internal var fieldState: FieldState? = null
    /** Searches as you type, once typing pauses for [LIVE_SEARCH_MS]. */
    internal val liveSearch = Runnable { if (queryEditor != null) gifs.search(query.toString(), live = true) }

    // ---- service lifecycle ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Prefs.migrateAnimals(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        // The files are read in the background; whoever needs one first waits for the rest of it.
        language = Language.named(prefs.getString(Prefs.LANGUAGE, null))
        user = userFor(language)
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
        dictLoads++
        loader.execute {
            loadDictionaryNow(language)
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

    /** The swipe up and left: the other language. */
    override fun onLanguageToggle() {
        val next = language.other
        prefs.edit { putString(Prefs.LANGUAGE, next.code) }
        applyLanguage()
        // A screen reader says it (the hint on the keyboard is only drawn).
        if (touchExploring()) Toast.makeText(this, next.label, Toast.LENGTH_SHORT).show()
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
        users.values.forEach { it.flush() }
        super.onDestroy()
    }

    /**
     * Android is short of memory: drop the GIF previews (unless they're on screen). Once the app is
     * cached (no field has the keyboard) the word list goes too; it is read again when a field next
     * opens the keyboard ([ensureDictionary]).
     */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW: still sent on the Android versions that have it.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        if (level >= TRIM_MEMORY_BACKGROUND) releaseDictionary()
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
    internal fun editor(): InputConnection? = queryEditor ?: inputConnection()

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
            Language.entries.forEach { userFor(it).clear() }
            pendingTouch = null
            touch.clear()
        }
        applyPrefs()
    }

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
        typingActive = true
        applyPrefs()
        ensureDictionary()
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
        typingActive = false
        user.save()
        flushTouch()
        touch.save()
        stats.save()
    }

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

    override fun onLetterTap(tap: Tap) = handleLetterTap(tap)

    /** Not in password or number fields, not while searching GIFs. */
    override fun animalMayVisit() = predict && queryEditor == null

    override fun onText(text: String) = handleText(text)

    /** Deletes the last tap, reopens the word just committed, or deletes a character. */
    override fun onBackspace() = handleBackspace()

    /** Swipe left: drops the word being typed, or deletes the word before the cursor. */
    override fun onDeleteWord() = handleDeleteWord()

    /** Commits the word being typed, or types a space; a second space after a word makes a full stop. */
    override fun onSpace() = handleSpace()

    /** Runs the field's action (search, send, go…) if it has one, otherwise types a newline. */
    override fun onEnter() = handleEnter()

    /** Off → on (one capital) → double tap: caps lock → off. */
    override fun onShift() = handleShift()

    override fun onSuggestion(slot: Int) = handleSuggestion(slot)

    override fun onModeToggle() = handleModeToggle()

    /** Full mode's bar switch. Mini mode always decodes, so it only affects full mode. */
    override fun onAutocorrectToggle() = handleAutocorrectToggle()

    /** Removes a word from suggestions for good (see [UserModel.forget]). */
    override fun onForgetSuggestion(slot: Int) = handleForgetSuggestion(slot)

    /** Types the emoji and counts it for the panel's "frequently used" section. */
    override fun onEmoji(emoji: String) = handleEmoji(emoji)

    override fun onClipboardOpen() = handleClipboardOpen()

    override fun onClipPicked(index: Int) = handleClipPicked(index)

    override fun onClipRemove(index: Int) = handleClipRemove(index)

    override fun onClipClear() = handleClipClear()

    // ---- stickers ---------------------------------------------------------------------------

    override fun onStickersOpen() = loadStickers()

    override fun onStickerPicked(index: Int) = handleStickerPicked(index)

    override fun onStickerRemove(index: Int) = handleStickerRemove(index)

    /** Opens the photo picker (through [AddStickerActivity]); the new sticker shows when the keyboard comes back. */
    override fun onStickerAdd() = handleStickerAdd()

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

    override fun onGifPicked(index: Int) = handleGifPicked(index)

    /**
     * The GIF search field was tapped: finish the app's word, then point typing at [query] with
     * suggestions on, no auto-capitals and nothing learned. [onGifSearchClose] undoes it.
     */
    override fun onGifSearchOpen() = handleGifSearchOpen()

    /** The search keyboard closed: the word in progress stays in the query, typing goes to the app again. */
    override fun onGifSearchClose() = handleGifSearchClose()

    /** Shows the query in the search field and schedules a live search. */
    internal val queryWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            view?.gifQuery = query.toString()
            main.removeCallbacks(liveSearch)
            if (queryEditor != null) main.postDelayed(liveSearch, LIVE_SEARCH_MS)
        }
    }

    /**
     * Inserts a GIF into the app the way messaging apps expect: as a content URI (our FileProvider)
     * through [InputConnection.commitContent], which most messengers turn into a GIF message ready to
     * send (some send it at once). Fields that don't accept GIFs get the GIF's link as text instead.
     */
    internal fun sendGif(gif: Gif) = deliverGif(gif)

    /** The cursor was moved while nothing was being typed: fill the bar for where it is now. */
    private val followCursor = Runnable {
        if (taps.isEmpty() && queryEditor == null && predictionsShown) showPredictions()
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
}

package com.brownllama.continuum

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
import com.brownllama.continuum.keyboard.Emoji
import com.brownllama.continuum.keyboard.EmojiGroup
import com.brownllama.continuum.keyboard.EmojiHistory
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.KeyboardView.Shift
import com.brownllama.continuum.prediction.DecodeResult
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.UserModel
import com.brownllama.continuum.theme.AppThemes
import com.brownllama.continuum.theme.KeyboardTheme
import java.io.File
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
 * - Password and number fields are typed letter by letter in full mode ([predict] off). Email and
 *   URL fields predict as usual but don't auto-capitalise.
 * - Fields that ask not to be learned from (incognito tabs, IME_FLAG_NO_PERSONALIZED_LEARNING)
 *   still get suggestions, but nothing typed there is learned or unlearned ([learning] off).
 * - A word typed right after "word'" or "word-" is learned together with it ("zazzy's", see [Glue]).
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
    private lateinit var appThemes: AppThemes
    private var currentPackage: String? = null
    @Volatile private var decoder: Decoder? = null
    private var dict: Dictionary? = null
    private lateinit var emojiHistory: EmojiHistory
    private lateinit var clipboard: ClipboardHistory
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
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
    /** Learn from what's typed. Off where the field asks for no personalised learning. */
    private var learning = true
    /** Full mode's autocorrect switch; mini mode always uses the decoder's best word. */
    private var autocorrect = true
    private var autoCaps = true
    private var capsAllowed = true

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
    /** Shift is on only because of auto-capitalisation (sentence start), not a tap on shift. */
    private var shiftAuto = false
    /** The current word's first letter was capitalised automatically. */
    private var wordAutoCap = false
    private var lastShiftTap = 0L
    /** Key of the previous word, for next-word predictions; null at the start of a sentence. */
    private var prevWord: String? = null
    /** When we last set composing text; selection updates arriving sooner may be stale. */
    private var lastEditTime = 0L

    /**
     * The last word committed by space/suggestion, so backspace can reopen it for correction. [text]
     * is what was typed into the field, [learned] what was taught (with its [glue] prefix, if any).
     */
    private class LastCommit(
        val text: String, val spaced: Boolean, val taps: List<Tap>, val mode: Mode, val prevBefore: String?,
        val learned: String, val glue: Glue?,
    )
    private var lastCommit: LastCommit? = null

    /**
     * A word that ended in an apostrophe or hyphen right before the cursor ("zazzy'"). The next word
     * typed is learned together with it ("zazzy's", key "zazzys"), replacing what was learned for
     * [prefix] on its own; [absorbed] records that this was undone already.
     */
    private class Glue(val prefix: String, val prevBefore: String?, val autoCap: Boolean) {
        var absorbed = false
    }
    /** The composing text the decoder last showed (what the word looks like in the field). */
    private var composingText = ""
    /**
     * While a word is being backspaced away: the word as it was when deleting started, then its
     * alternatives. Shown in the bar once the word is gone ([showDeletedWord]); typing ends it.
     */
    private var deletedSlots: List<String>? = null

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
    private class FieldState(val predict: Boolean, val capsAllowed: Boolean, val learning: Boolean, val prevWord: String?)
    private var fieldState: FieldState? = null
    /** Searches as you type, once typing pauses for [LIVE_SEARCH_MS]. */
    private val liveSearch = Runnable { if (queryEditor != null) gifs.search(query.toString(), live = true) }

    // ---- service lifecycle ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        user = UserModel(File(filesDir, "user_model.tsv"))
        emojiHistory = EmojiHistory(File(filesDir, "emoji_usage.tsv"))
        clipboard = ClipboardHistory(File(filesDir, "clipboard.tsv"))
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
        appThemes = AppThemes(this)
        gifs = GifFeed(this, prefs)
        Executors.newSingleThreadExecutor().execute {
            val d = Dictionary.load(this)
            main.post {
                dict = d
                decoder = Decoder(d, user)
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
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        gifs.shutdown()
        user.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        view = v
        gifs.view = v
        v.emojiGroups = emojiGroups
        v.frequentEmoji = emojiHistory.top(FREQUENT_EMOJI)
        applyPrefs()
        return v
    }

    /** For tests: whether the dictionary has loaded, what the suggestion bar shows, the GIF search text. */
    internal val isReady: Boolean get() = decoder != null
    internal val suggestionSlots: List<String> get() = slots
    internal val gifQuery: String get() = query.toString()
    internal fun learnedCount(word: String): Int = Dictionary.keyOf(word)?.let { user.count(it) } ?: 0

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
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (!isInputViewShown || v.height == 0) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val kbTop = loc[1] + v.overlayH.roundToInt()
        outInsets.contentTopInsets = kbTop
        outInsets.visibleTopInsets = kbTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, kbTop, v.width, loc[1] + v.height)
    }

    /** Settings changed (the settings screen writes them while the keyboard may be open). */
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.CLEAR_LEARNED) user.clear()
        applyPrefs()
    }

    /** Reads every setting and pushes the visual ones to the view. */
    private fun applyPrefs() {
        prefMode = if (prefs.getString(Prefs.MODE, "mini") == "full") Mode.FULL else Mode.MINI
        autocorrect = prefs.getBoolean(Prefs.AUTOCORRECT, Prefs.AUTOCORRECT_DEFAULT)
        autoCaps = prefs.getBoolean(Prefs.AUTOCAPS, Prefs.AUTOCAPS_DEFAULT)
        layout = KeyLayout.named(prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_DEFAULT))
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
    }

    // ---- editor lifecycle -----------------------------------------------------------------

    /** The keyboard is shown for a (new) text field: reset the word and adapt to the field's type. */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.closeGifSearch()
        captureClip() // something may have been copied while the keyboard wasn't running
        currentPackage = info.packageName
        applyPrefs()
        taps.clear()
        result = null
        lastCommit = null
        autoSpace = false
        prevWord = null

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val address = cls == InputType.TYPE_CLASS_TEXT && variation in ADDRESS_VARIATIONS
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME

        predict = !(password || numeric)
        capsAllowed = predict && !address
        learning = predict && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        // Exact typing (passwords, numbers) needs real keys, so those fields always use full mode.
        mode = if (predict) prefMode else Mode.FULL
        pendingGlue = null
        wordGlue = null
        deletedSlots = null

        view?.let { v ->
            v.mode = mode
            if (numeric) v.showPanel(KeyboardView.Panel.SYMBOLS)
            v.enterAction = enterAction(info)
        }
        shift = Shift.OFF
        updateAutoShift()
        showPredictions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.closeGifSearch()
        finishComposing()
        user.save()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        taps.clear()
        result = null
    }

    /** The cursor moved. If the user tapped elsewhere in the text, drop the word being typed. */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // While searching GIFs the word being typed is in the query, not the app's field.
        if (taps.isEmpty() || queryEditor != null) return
        // Updates for our own edits can arrive late; only trust "composing vanished" once things settle.
        val composingGone = candidatesStart == -1 && SystemClock.uptimeMillis() - lastEditTime > STALE_UPDATE_MS
        val cursorOutside = candidatesStart != -1 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        if (composingGone || cursorOutside) {
            taps.clear()
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
            wordAutoCap = shift == Shift.ON && shiftAuto
            wordGlue = pendingGlue?.takeIf {
                editor()?.getTextBeforeCursor(it.prefix.length, 0)?.toString() == it.prefix
            }
            pendingGlue = null
        }
        taps += t
        if (shift == Shift.ON) setShift(Shift.OFF)
        redecode()
    }

    override fun onText(text: String) {
        val ic = editor() ?: return
        if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
        // "zazzy" + "'": the next word joins this one (see Glue).
        val joined = lastCommit?.takeIf { !it.spaced && text.length == 1 && text[0] in JOINERS }
        pendingGlue = joined?.let { Glue(it.learned + text, it.prevBefore, wordAutoCap) }
        ic.beginBatchEdit()
        if (text.length == 1 && text[0] in ATTACHING_PUNCTUATION && autoSpace) {
            // "word |" + "." → "word. |"
            val before = ic.getTextBeforeCursor(1, 0)
            if (before == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText("$text ", 1)
            autoSpace = true
        } else {
            ic.commitText(text, 1)
            autoSpace = false
        }
        ic.endBatchEdit()
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
            taps.removeAt(taps.lastIndex)
            if (taps.isEmpty()) {
                ic.commitText("", 1)
                result = null
                updateAutoShift()
                showDeletedWord()
            } else {
                redecode()
            }
            return
        }
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
        if (learning) Dictionary.keyOf(last.learned)?.let { user.unlearn(last.prevBefore, it) }
        prevWord = last.prevBefore
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
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

    /** Swipe left: drops the word being typed, or deletes the word before the cursor. */
    override fun onDeleteWord() {
        val ic = editor() ?: return
        // A word just committed is reopened first, so its alternatives can be offered once it's gone.
        if (taps.isEmpty()) reopenLastCommit(ic)
        if (taps.isNotEmpty()) {
            rememberDeletedWord()
            taps.clear()
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
        val n = before.length - start
        if (n > 0) ic.deleteSurroundingText(n, 0) else deleteBefore(ic)
        lastCommit = null
        autoSpace = false
        updateAutoShift()
        showPredictions()
    }

    /** Commits the word being typed, or types a space; a second space after a word makes a full stop. */
    override fun onSpace() {
        val ic = editor() ?: return
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
            // A next-word prediction.
            ic.commitText("$word ", 1)
            learn(word, autoCap = shift == Shift.ON && shiftAuto, picked = true)
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
     * sensitive (passwords, one-time codes; Android 13+ password managers do) and while the field
     * being typed in is a password, number or incognito field ([learning] off). Only plain text is
     * kept: a clip that is a link to another app's content is never opened.
     */
    private fun captureClip() {
        if (!learning) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
        if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return
        clipboard.add(text)
    }

    override fun onClipboardOpen() {
        captureClip()
        view?.setClips(clipboard.texts())
    }

    override fun onClipPicked(index: Int) {
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
        clipboard.remove(index)
        view?.setClips(clipboard.texts())
    }

    override fun onClipClear() {
        clipboard.clear()
        view?.setClips(clipboard.texts())
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
        fieldState = FieldState(predict, capsAllowed, learning, prevWord)
        predict = true
        capsAllowed = false
        learning = false
        resetWordState()
        if (query.getSpans(0, query.length, TextWatcher::class.java).isEmpty()) {
            query.setSpan(queryWatcher, 0, query.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        Selection.setSelection(query, query.length)
        queryEditor = object : BaseInputConnection(view!!, true) {
            override fun getEditable(): Editable = query
        }
        if (shift != Shift.LOCK) setShift(Shift.OFF)
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
        }
        fieldState = null
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
        val r = d?.decode(taps, tapMode, layout, prevWord)
        result = r
        val literal = r?.literal ?: buildString { for (t in taps) append('a' + nearestLetter(t)) }
        val literalText = applyCase(literal, keepDisplay = false)
        val words = r?.words.orEmpty().map { applyCase(it.display) }
        // Typo corrections take their place among the runners-up by score.
        val more = (r?.words.orEmpty().drop(1) + r?.completions.orEmpty() + r?.corrections.orEmpty())
            .sortedByDescending { it.score }
            .map { applyCase(it.display) }

        val best = words.firstOrNull() ?: literalText
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

        val others = (listOf(best) + more).distinct().filter { it != composing }
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
        // The end of a joined word ("zazzy'" + "s") is typed as is.
        if (!autocorrect || r == null || wordGlue != null || taps.any { it.exact >= 0 }) return literalText
        val best = r.words.firstOrNull() ?: return literalText
        if (best.key == r.literal) {
            // The same letters with an apostrophe or hyphen ("dont" → "don't", "zazzys" → "zazzy's"),
            // unless what was typed is a word as it stands ("its").
            val typedIsWord = r.words.any { it.key == r.literal && it.display.equals(r.literal, ignoreCase = true) }
            return if (!typedIsWord && best.display.any { !it.isLetter() }) words.first() else literalText
        }
        if (r.literalIsWord && best.score - r.literalScore < KEEP_REAL_WORD_MARGIN) return literalText
        if (taps.size == 1) return literalText
        return words.first()
    }

    /** Commits [choice], or the default suggestion, in place of the composing text, and learns it. */
    private fun commitWord(spaced: Boolean, choice: String?) {
        val ic = editor() ?: return
        val text = choice ?: slots.getOrNull(defaultSlot)?.takeIf { it.isNotEmpty() }
            ?: applyCase(result?.literal ?: "", keepDisplay = false)
        ic.commitText(text + if (spaced) " " else "", 1)
        // A joined word replaces what was learned for its first part: "zazzy" + "'s" → "zazzy's".
        val g = wordGlue?.takeIf { Dictionary.keyOf(text) != null }
        if (g != null && !g.absorbed) {
            if (learning) Dictionary.keyOf(g.prefix)?.let { user.unlearn(g.prevBefore, it) }
            g.absorbed = true
        }
        if (g != null) prevWord = g.prevBefore
        val learned = if (g != null) g.prefix + text else text
        val prevBefore = prevWord
        learn(learned, autoCap = g?.autoCap ?: wordAutoCap, picked = choice != null)
        lastCommit = LastCommit(text, spaced, ArrayList(taps), tapMode, prevBefore, learned, g)
        wordGlue = null
        taps.clear()
        result = null
        autoSpace = spaced
        updateAutoShift()
        showPredictions()
    }

    /** Commits a word in progress as it stands (when the keyboard closes or switches mode). */
    private fun finishComposing() {
        if (taps.isEmpty()) return
        commitWord(spaced = false, choice = null)
        lastCommit = null
    }

    /**
     * Records a committed word. A capital that only came from auto-capitalisation is not part of the
     * user's spelling ("zorp" at the start of a message), unless the word is a proper noun anyway.
     */
    private fun learn(word: String, autoCap: Boolean, picked: Boolean = false) {
        // An emoji (or anything without letters) is not learned, and the word before it no longer counts.
        val key = Dictionary.keyOf(word) ?: run { prevWord = null; return }
        // Fields that ask for no learning (incognito) only track the previous word, for predictions.
        if (!learning) {
            prevWord = key
            return
        }
        user.unblock(word)
        val dictForm = dict?.takeIf { it.contains(key) }?.displayFor(key)
        val form = when {
            !autoCap || word.isEmpty() || !word[0].isUpperCase() -> word
            word.drop(1).any { it.isUpperCase() } -> word
            dictForm?.firstOrNull()?.isUpperCase() == true -> word
            else -> word.replaceFirstChar { it.lowercaseChar() }
        }
        user.learn(prevWord, key, form)
        // Picking a new word from the bar confirms it, as typing it a second time would.
        if (picked) user.confirm(key)
        prevWord = key
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
        slots = pad(list.take(MAX_SUGGESTIONS))
        view?.setSuggestions(slots, -1)
    }

    /** Next-word predictions for the empty bar (does nothing while a word is being typed). */
    private fun showPredictions() {
        punctuationBar = false
        if (taps.isNotEmpty()) return
        val v = view ?: return
        val d = decoder
        if (!predict || d == null) {
            slots = listOf("", "", "")
            v.setSuggestions(slots, -1)
            return
        }
        val words = d.predict(prevWord, sentenceStart = shift != Shift.OFF, limit = MAX_SUGGESTIONS).map {
            when (shift) {
                Shift.OFF -> it
                Shift.ON -> it.replaceFirstChar { c -> c.uppercaseChar() }
                Shift.LOCK -> it.uppercase()
            }
        }
        // Left to right by likelihood ("the | I | in").
        slots = pad(words)
        v.setSuggestions(slots, -1)
    }

    /**
     * Applies the shift state of the taps to a suggestion: all caps if every letter was shifted,
     * else a capital first letter. With [keepDisplay] off, each letter follows its own tap instead.
     */
    private fun applyCase(word: String, keepDisplay: Boolean = true): String {
        if (word.isEmpty() || taps.isEmpty()) return word
        return when {
            taps.size > 1 && taps.all { it.upper } -> word.uppercase()
            !keepDisplay -> String(CharArray(word.length) { i ->
                if (taps.getOrNull(i)?.upper == true) word[i].uppercaseChar() else word[i]
            })
            taps[0].upper -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

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

    private fun setShift(s: Shift, auto: Boolean = false) {
        shift = s
        shiftAuto = auto && s == Shift.ON
        view?.shift = s
    }

    /** Capitalise at the start of a field and after sentence-ending punctuation. */
    private fun updateAutoShift() {
        if (shift == Shift.LOCK) return
        if (!autoCaps || !capsAllowed || taps.isNotEmpty()) {
            if (taps.isEmpty() && shift == Shift.ON && !autoCaps) setShift(Shift.OFF)
            return
        }
        val before = editor()?.getTextBeforeCursor(3, 0)?.toString() ?: return
        val trimmed = before.trimEnd(' ')
        val start = trimmed.isEmpty() && before.length < 3 ||
            trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END && before.endsWith(" ") ||
            before.endsWith("\n")
        setShift(if (start) Shift.ON else Shift.OFF, auto = true)
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
        /** Two shift taps within this time turn on caps lock. */
        private const val DOUBLE_TAP_MS = 350L
        /** Full-mode autocorrect keeps a real typed word unless a correction scores this much better (log). */
        private const val KEEP_REAL_WORD_MARGIN = 5f
        private const val STALE_UPDATE_MS = 300L
        /** GIF search: how long typing must pause before the results update. */
        private const val LIVE_SEARCH_MS = 600L
        /** Punctuation that sticks to the previous word, taking the space before it. */
        private const val ATTACHING_PUNCTUATION = ".,!?;:"
        private const val SENTENCE_END = ".!?"
        /** Characters that join two parts into one word ("zazzy's", "self-aware"). */
        private const val JOINERS = "'’-"
        /** Shown after a double-space period, "." highlighted; the bar scrolls to the rest. */
        private val PUNCTUATION_BAR = listOf("!", ".", "?", ",", ";", ":", "/")

        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        private val ADDRESS_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}

// Settings, the language and what kind of field has focus.
package com.brownllama.continuum

import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import com.brownllama.continuum.keyboard.AnimalFrequency
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.keyboard.Species
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Language
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.UserModel
import com.brownllama.continuum.theme.KeyboardTheme
import java.io.File
import java.util.concurrent.Executors

/** The learned words of [l], read on first use. */
internal fun ContinuumIME.userFor(l: Language): UserModel = users.getOrPut(l) {
    UserModel(File(filesDir, l.userFile), background = true).also { m ->
        // New words are written a moment later (and when the keyboard closes), not one write each.
        m.onNewWord = {
            main.removeCallbacks(saveLearned)
            main.postDelayed(saveLearned, SAVE_DELAY_MS)
        }
    }
}

/** Reads the word list of [l] (on the loading thread) and, if that is still the language, starts using it. */
internal fun ContinuumIME.loadDictionaryNow(l: Language) {
    val d = Dictionary.load(this, l)
    main.post {
        dictLoads--
        if (l != language) return@post
        dict = d
        decoder = Decoder(d, user)
        applyTouchPrefs()
        if (taps.isNotEmpty()) redecode() else showPredictions()
    }
}

/** Reads the word list of [l] on a thread of its own, which ends when it's done ([loadDictionaryNow]). */
internal fun ContinuumIME.loadDictionaryInBackground(l: Language) {
    dictLoads++
    val loader = Executors.newSingleThreadExecutor()
    loader.execute { loadDictionaryNow(l) }
    loader.shutdown()
}

/**
 * Lets go of the word list and the decoder (a few MB) while no field has the keyboard and the app is
 * cached; [ensureDictionary] brings them back. The learned words stay (they are small).
 */
internal fun ContinuumIME.releaseDictionary() {
    if (typingActive || taps.isNotEmpty() || dictLoads > 0) return
    decoder = null
    dict = null
    result = null
}

/** A field opened the keyboard: read the word list again if [releaseDictionary] let it go. */
internal fun ContinuumIME.ensureDictionary() {
    if (decoder == null && dictLoads == 0) loadDictionaryInBackground(language)
}

/**
 * The language was changed: from here on words are read from, and learned in, the new one. A word
 * in progress is finished in the language it was typed in. Only the language in use is kept in
 * memory; the other loads from its saved copy when it is next switched to.
 */
internal fun ContinuumIME.applyLanguage() {
    val wanted = Language.named(prefs.getString(Prefs.LANGUAGE, null))
    if (wanted == language) return
    if (taps.isNotEmpty()) {
        finishComposing()
        taps.clear()
    }
    flushTouch()
    user.save()
    val old = language
    language = wanted
    user = userFor(wanted)
    prevWord = null
    lastCommit = null
    pendingGlue = null
    wordGlue = null
    afterNumber = null
    deletedTaps.clear()
    result = null
    decoder = null
    dict = null
    loadDictionaryInBackground(wanted)
    showPredictions()
    // A small animated hint (and the language label) shows which language it is now.
    view?.showLanguageHint(old, wanted)
}

/** Reads every setting and pushes the visual ones to the view. */
internal fun ContinuumIME.applyPrefs() {
    applyLanguage()
    prefMode = if (prefs.getString(Prefs.MODE, "mini") == "full") Mode.FULL else Mode.MINI
    autocorrect = prefs.getBoolean(Prefs.AUTOCORRECT, Prefs.AUTOCORRECT_DEFAULT)
    autoCaps = prefs.getBoolean(Prefs.AUTOCAPS, Prefs.AUTOCAPS_DEFAULT)
    layout = KeyLayout.named(prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_DEFAULT))
    applyClipboardPrefs()
    applyTouchPrefs()
    val v = view ?: return
    v.layout = layout
    v.languageCode = language.code
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
    v.grassDp = prefs.getInt(Prefs.GRASS_HEIGHT, Prefs.GRASS_HEIGHT_DEFAULT).toFloat()
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
internal fun ContinuumIME.applyTouchPrefs() {
    touchOn = prefs.getBoolean(Prefs.TOUCH_ADAPT, Prefs.TOUCH_ADAPT_DEFAULT)
    if (!touchOn) pendingTouch = null
    decoder?.touch = if (touchOn) touch else null
}

/**
 * The last committed word was kept (another word started, or the field was left): its taps
 * now teach [touch] where this user's fingers land.
 */
internal fun ContinuumIME.flushTouch() {
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
internal fun ContinuumIME.applyClipboardPrefs() {
    clipboardOn = prefs.getBoolean(Prefs.CLIPBOARD, Prefs.CLIPBOARD_DEFAULT)
    clipboard.keepMs = (prefs.getString(Prefs.CLIPBOARD_KEEP, null)?.toLongOrNull() ?: Prefs.CLIPBOARD_KEEP_DEFAULT)
        .coerceIn(1L, Prefs.CLIPBOARD_KEEP_DEFAULT) * HOUR_MS
    if (!clipboardOn) clipboard.clear()
}

// ---- editor lifecycle -----------------------------------------------------------------

/** A password field (text or numeric). */
internal fun ContinuumIME.isPassword(info: EditorInfo): Boolean {
    val cls = info.inputType and InputType.TYPE_MASK_CLASS
    val variation = info.inputType and InputType.TYPE_MASK_VARIATION
    return cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
        cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
}

/** A number, phone number or date field. */
internal fun ContinuumIME.isNumeric(info: EditorInfo): Boolean {
    val cls = info.inputType and InputType.TYPE_MASK_CLASS
    return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
}

/** A field whose content is private: a password, a number (codes, card numbers) or an incognito field. */
internal fun ContinuumIME.isPrivate(info: EditorInfo): Boolean =
    isPassword(info) || isNumeric(info) || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

/** Whether a screen reader (TalkBack) is exploring the screen by touch. */
internal fun ContinuumIME.touchExploring(): Boolean =
    getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

/** The field's enter action (drawn on the enter key and run by [onEnter]), or NONE for a newline. */
internal fun ContinuumIME.enterAction(info: EditorInfo): Int {
    if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
    if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return EditorInfo.IME_ACTION_NONE
    return info.imeOptions and EditorInfo.IME_MASK_ACTION
}

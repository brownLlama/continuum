package com.brownllama.continuum

/** SharedPreferences keys (the settings pages are res/xml/prefs_*.xml). */
object Prefs {
    /** The last mode switched to by gesture, "mini" (the default) or "full"; not a setting. */
    const val MODE = "mode"
    const val LAYOUT = "layout"
    const val LAYOUT_DEFAULT = "qwertz"
    /** A key of KeyboardTheme.PRESETS, or "chameleon" (the default): each app's own colour. */
    const val THEME = "theme"
    const val THEME_CHAMELEON = "chameleon"
    /*
     * Slider defaults sit in the middle of their slider's range (res/xml/prefs_*.xml), so there's
     * as much room to go up as down. SettingsDefaultsTest checks that the two stay in step.
     */

    /** Keyboard heights in percent of the base size, per mode (settings sliders). */
    const val MINI_HEIGHT = "mini_height"
    const val MINI_HEIGHT_DEFAULT = 70
    const val FULL_HEIGHT = "full_height"
    const val FULL_HEIGHT_DEFAULT = 80
    const val BAR_HEIGHT = "bar_height"
    const val BAR_HEIGHT_DEFAULT = 140

    /** Space between the keys and the bottom of the screen, in dp. */
    const val BOTTOM_SPACE = "bottom_space_dp"
    const val BOTTOM_SPACE_DEFAULT = 15
    /** Hide the system's keyboard-switch / hide-keyboard buttons under the keys (Android 16+). */
    const val HIDE_SYSTEM_BUTTONS = "hide_system_buttons"
    const val AUTOCORRECT = "autocorrect"
    const val AUTOCORRECT_DEFAULT = false
    const val AUTOCAPS = "autocaps"
    const val AUTOCAPS_DEFAULT = false

    /** Swipe detection sliders (see KeyboardView.isSwipe). Speed is in hundredths of dp/ms. */
    const val SWIPE_MIN = "swipe_min_dp"
    const val SWIPE_MIN_DEFAULT = 25
    const val SWIPE_LONG = "swipe_long_dp"
    const val SWIPE_LONG_DEFAULT = 75
    const val SWIPE_SPEED = "swipe_speed"
    const val SWIPE_SPEED_DEFAULT = 25
    const val SWIPE_STRAIGHTNESS = "swipe_straightness"
    const val SWIPE_STRAIGHTNESS_DEFAULT = 60
    /** Hold time before the zoom or a key's popup opens, in ms. */
    const val HOLD_MS = "hold_ms"
    const val HOLD_MS_DEFAULT = 110
    /**
     * Every setting "Reset to defaults" (main menu) clears, so the code's defaults apply again.
     * Not the KLIPY key, the text shortcuts, the learned words or the remembered mode. "haptics" and "previews" were
     * switches once (now always off / always on) and are cleared so old values don't linger.
     */
    val RESETTABLE = listOf(
        THEME, LAYOUT, MINI_HEIGHT, FULL_HEIGHT, BAR_HEIGHT, BOTTOM_SPACE, HIDE_SYSTEM_BUTTONS,
        AUTOCORRECT, AUTOCAPS, HOLD_MS, SWIPE_MIN, SWIPE_LONG, SWIPE_SPEED, SWIPE_STRAIGHTNESS, SHOW_GIFS,
        "haptics", "previews",
    )

    /** Written with a timestamp to ask the IME to forget learned words. */
    const val CLEAR_LEARNED = "clear_learned"

    /** Whether the emoji panel offers the GIF tab (off for people without a KLIPY key). */
    const val SHOW_GIFS = "show_gifs"
    const val SHOW_GIFS_DEFAULT = true
    /** GIF search: the user's free KLIPY app key and the anonymous id KLIPY asks for. */
    const val KLIPY_KEY = "klipy_key"
    const val GIF_CUSTOMER_ID = "gif_customer_id"

    /** Text shortcuts: [SHORTCUT_TRIGGER] then one of [SHORTCUT_KEYS] types the text saved under [shortcut]. */
    const val SHORTCUT_TRIGGER = "##"
    const val SHORTCUT_KEYS = "abcdefghijklmnopqrstuvwxyz0123456789"
    fun shortcut(key: Char) = "shortcut_${key.lowercaseChar()}"
}

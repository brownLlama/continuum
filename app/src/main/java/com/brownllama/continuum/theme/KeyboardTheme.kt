package com.brownllama.continuum.theme

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt

/**
 * Colour sets for the keyboard: the fixed [PRESETS], or one built from an app's colour by [fromColor].
 * [accent] colours the shift-on state and zoom highlights, and icons too when [accentIcons].
 * [homeRow] colours the middle letter row (A S D F …) in both modes. [keyFill] backs the keypad
 * keys and the soft pills (suggestion, space bar, active tab) on dark keyboards.
 */
data class KeyboardTheme(
    val background: Int,
    val text: Int,
    /** Secondary text: the symbol strip, long-press hints, inactive tabs. */
    val dim: Int,
    val accent: Int,
    /** Text on [accent] (the "Remove" button). */
    val onAccent: Int,
    val keyPressed: Int,
    /** Popups: the zoom, key previews, the long-press picker. */
    val zoom: Int,
    /** The selected cell in a popup, and text on it. */
    val zoomSelect: Int,
    val onZoomSelect: Int,
    val keyFill: Int = ColorUtils.blendARGB(background, text, 0.13f),
    val homeRow: Int = soft(accent),
    val accentIcons: Boolean = false,
) {
    val isLight: Boolean get() = ColorUtils.calculateLuminance(background) > 0.5

    companion object {
        private fun c(hex: String) = hex.toColorInt()

        /**
         * The home-row letter colour: [color]'s hue as a soft pastel, light enough to stand out on a
         * dark keyboard without glaring. White or grey colours have no hue to tint and stay as they are.
         */
        fun soft(color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[1] < 0.15f) return color
            return ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.6f), 0.74f))
        }

        /*
         * Nord palette (nordtheme.com): Polar Night nord0–3 #2E3440 #3B4252 #434C5E #4C566A,
         * Snow Storm nord4–6 #D8DEE9 #E5E9F0 #ECEFF4, Frost nord7–10 #8FBCBB #88C0D0 #81A1C1 #5E81AC.
         * The keyboard for apps without a colour of their own.
         */
        val NORD_DARK = KeyboardTheme(
            background = c("#3B4252"), text = c("#ECEFF4"), dim = c("#7B88A1"),
            accent = c("#88C0D0"), onAccent = c("#2E3440"), keyPressed = c("#434C5E"),
            zoom = c("#434C5E"), zoomSelect = c("#D8DEE9"), onZoomSelect = c("#2E3440"),
            keyFill = c("#434C5E"),
        )

        /** Nord's Snow Storm background with Polar Night letters and Frost blue. */
        val NORD_LIGHT = KeyboardTheme(
            background = c("#E5E9F0"), text = c("#2E3440"), dim = c("#616E88"),
            accent = c("#5E81AC"), onAccent = c("#ECEFF4"), keyPressed = c("#D8DEE9"),
            zoom = c("#ECEFF4"), zoomSelect = c("#5E81AC"), onZoomSelect = c("#ECEFF4"),
            keyFill = c("#ECEFF4"), homeRow = c("#5E81AC"),
        )

        /** Plain neutral greys. */
        val DARK = KeyboardTheme(
            background = c("#1E1F22"), text = c("#EDEDED"), dim = c("#8B8D91"),
            accent = c("#8AB4F8"), onAccent = c("#1E1F22"), keyPressed = c("#3A3C40"),
            zoom = c("#323438"), zoomSelect = c("#EDEDED"), onZoomSelect = c("#1E1F22"),
            keyFill = c("#2B2D31"),
        )

        val LIGHT = KeyboardTheme(
            background = c("#EEF0F3"), text = c("#1F2328"), dim = c("#6E7781"),
            accent = c("#2F6FDB"), onAccent = c("#FFFFFF"), keyPressed = c("#D0D4DA"),
            zoom = c("#FFFFFF"), zoomSelect = c("#2F6FDB"), onZoomSelect = c("#FFFFFF"),
            keyFill = c("#FFFFFF"), homeRow = c("#2F6FDB"),
        )

        // The palettes below follow each theme's published colours (draculatheme.com, gruvbox,
        // ethanschoonover.com/solarized, catppuccin.com Mocha, Tokyo Night, Atom One Dark).
        val DRACULA = KeyboardTheme(
            background = c("#282A36"), text = c("#F8F8F2"), dim = c("#8B93BD"),
            accent = c("#BD93F9"), onAccent = c("#282A36"), keyPressed = c("#44475A"),
            zoom = c("#44475A"), zoomSelect = c("#BD93F9"), onZoomSelect = c("#282A36"),
            keyFill = c("#343746"), homeRow = c("#FF79C6"),
        )

        val GRUVBOX = KeyboardTheme(
            background = c("#282828"), text = c("#EBDBB2"), dim = c("#A89984"),
            accent = c("#FABD2F"), onAccent = c("#282828"), keyPressed = c("#504945"),
            zoom = c("#3C3836"), zoomSelect = c("#FABD2F"), onZoomSelect = c("#282828"),
            keyFill = c("#3C3836"), homeRow = c("#B8BB26"),
        )

        val SOLARIZED_DARK = KeyboardTheme(
            background = c("#002B36"), text = c("#EEE8D5"), dim = c("#839496"),
            accent = c("#268BD2"), onAccent = c("#FDF6E3"), keyPressed = c("#0B4554"),
            zoom = c("#073642"), zoomSelect = c("#268BD2"), onZoomSelect = c("#FDF6E3"),
            keyFill = c("#073642"), homeRow = c("#B58900"),
        )

        val SOLARIZED_LIGHT = KeyboardTheme(
            background = c("#FDF6E3"), text = c("#073642"), dim = c("#657B83"),
            accent = c("#268BD2"), onAccent = c("#FDF6E3"), keyPressed = c("#E4DDC8"),
            zoom = c("#EEE8D5"), zoomSelect = c("#268BD2"), onZoomSelect = c("#FDF6E3"),
            keyFill = c("#EEE8D5"), homeRow = c("#CB4B16"),
        )

        val CATPPUCCIN = KeyboardTheme(
            background = c("#1E1E2E"), text = c("#CDD6F4"), dim = c("#7F849C"),
            accent = c("#CBA6F7"), onAccent = c("#1E1E2E"), keyPressed = c("#45475A"),
            zoom = c("#313244"), zoomSelect = c("#CBA6F7"), onZoomSelect = c("#1E1E2E"),
            keyFill = c("#313244"), homeRow = c("#F5C2E7"),
        )

        val TOKYO_NIGHT = KeyboardTheme(
            background = c("#1A1B26"), text = c("#C0CAF5"), dim = c("#737AA2"),
            accent = c("#7AA2F7"), onAccent = c("#1A1B26"), keyPressed = c("#33467C"),
            zoom = c("#24283B"), zoomSelect = c("#7AA2F7"), onZoomSelect = c("#1A1B26"),
            keyFill = c("#24283B"), homeRow = c("#BB9AF7"),
        )

        val ONE_DARK = KeyboardTheme(
            background = c("#282C34"), text = c("#DCDFE4"), dim = c("#7F848E"),
            accent = c("#61AFEF"), onAccent = c("#282C34"), keyPressed = c("#3E4451"),
            zoom = c("#3E4451"), zoomSelect = c("#61AFEF"), onZoomSelect = c("#282C34"),
            keyFill = c("#2F343F"), homeRow = c("#E5C07B"),
        )

        /**
         * The fixed themes of Appearance → Theme, by preference value (the `theme_values` array).
         * "chameleon", the default, isn't here: it takes each app's colour ([AppThemes]).
         */
        val PRESETS: Map<String, KeyboardTheme> by lazy {
            mapOf(
                "dark" to DARK, "light" to LIGHT, "nord" to NORD_DARK, "nord_light" to NORD_LIGHT,
                "dracula" to DRACULA, "gruvbox" to GRUVBOX, "solarized_dark" to SOLARIZED_DARK,
                "solarized_light" to SOLARIZED_LIGHT, "catppuccin" to CATPPUCCIN,
                "tokyo_night" to TOKYO_NIGHT, "one_dark" to ONE_DARK,
            )
        }

        /**
         * A calm, dark palette built around one brand colour, so each app's keyboard is recognisably
         * its own without being loud: the background takes the brand's hue, muted and dark; letters are
         * a soft off-white; the accent and the home row are gentle pastels of the same hue.
         */
        fun fromColor(brand: Int): KeyboardTheme {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(brand, hsl)
            val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
            // Darker brands give darker keyboards, but every keyboard stays between 13% and 24% lightness.
            val bg = ColorUtils.HSLToColor(floatArrayOf(h, minOf(s * 0.42f, MAX_BACKGROUND_SATURATION), 0.13f + 0.11f * l))
            val text = ColorUtils.HSLToColor(floatArrayOf(h, 0.12f, 0.9f))
            val accent = ColorUtils.HSLToColor(floatArrayOf(h, 0.3f + 0.25f * s, 0.72f))
            val deep = ColorUtils.blendARGB(bg, Color.BLACK, 0.4f)
            return KeyboardTheme(
                background = bg, text = text, dim = ColorUtils.blendARGB(text, bg, 0.45f),
                accent = accent, onAccent = deep,
                keyPressed = ColorUtils.blendARGB(bg, Color.WHITE, 0.08f),
                zoom = ColorUtils.blendARGB(bg, Color.WHITE, 0.1f), zoomSelect = accent, onZoomSelect = deep,
                accentIcons = true,
            )
        }

        /** Keeps even the most saturated brand colours from tinting the keyboard too strongly. */
        private const val MAX_BACKGROUND_SATURATION = 0.38f
    }
}

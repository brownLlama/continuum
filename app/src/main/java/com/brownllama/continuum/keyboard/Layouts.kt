package com.brownllama.continuum.keyboard

/**
 * A letter layout (three rows of a physical keyboard) and the geometry derived from it, shared by
 * the view (where to draw) and the decoder (where taps are expected).
 */
class KeyLayout(val id: String, val top: String, val mid: String, val bot: String) {
    /**
     * Mini mode: the keyboard's columns squashed into one row (QWERTZ: Q A Y W S X …), evenly spaced.
     * Position i of this string is the letter's x-coordinate in "slot" units.
     */
    val miniOrder: String = buildString {
        for (i in 0 until maxOf(top.length, mid.length, bot.length)) {
            top.getOrNull(i)?.let(::append)
            mid.getOrNull(i)?.let(::append)
            bot.getOrNull(i)?.let(::append)
        }
    }

    /** Mini slot (0..25) of each letter a..z. */
    val miniSlot = IntArray(26).also { a -> miniOrder.forEachIndexed { i, c -> a[c - 'a'] = i } }

    /** Full mode key centres in key units (x: key widths from the left edge, y: rows from the top). */
    val fullX = FloatArray(26)
    val fullY = FloatArray(26)

    /** Long-press characters for full mode letter keys (also drawn as small hints), by position. */
    val fullHints = HashMap<Char, String>()

    init {
        require(miniOrder.length == 26 && miniOrder.toSet().size == 26) { "layout $id must cover a-z once" }
        top.forEachIndexed { i, c -> fullX[c - 'a'] = i + 0.5f; fullY[c - 'a'] = 0.5f; fullHints[c] = TOP_HINTS[i].toString() }
        mid.forEachIndexed { i, c -> fullX[c - 'a'] = i + 1.0f; fullY[c - 'a'] = 1.5f; fullHints[c] = MID_HINTS[i].toString() }
        bot.forEachIndexed { i, c -> fullX[c - 'a'] = i + 2.0f; fullY[c - 'a'] = 2.5f; fullHints[c] = BOT_HINTS[i].toString() }
    }

    /** 0 = top row, 1 = home row, 2 = bottom row. Drives the vertical stagger in mini mode. */
    fun row(c: Char): Int = when (c) {
        in top -> 0
        in mid -> 1
        else -> 2
    }

    companion object {
        // Long-press characters shown on the full keyboard's keys.
        private const val TOP_HINTS = "1234567890"
        private const val MID_HINTS = "@#€_&*()-"
        private const val BOT_HINTS = "<>;:\"'/"

        val QWERTZ = KeyLayout("qwertz", "qwertzuiop", "asdfghjkl", "yxcvbnm")
        val QWERTY = KeyLayout("qwerty", "qwertyuiop", "asdfghjkl", "zxcvbnm")

        fun named(id: String?): KeyLayout = if (id == "qwerty") QWERTY else QWERTZ
    }
}

/** Layout-independent keyboard content. */
object Layouts {
    /**
     * Symbol strip above the mini letter row, one entry per letter slot. The first slot opens the
     * clipboard panel and the last one the emoji panel.
     */
    val MINI_STRIP = listOf(
        CLIPBOARD_SLOT, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ":", "'", "\"", "@", "#",
        "(", ")", "_", "!", ",", ".", "?", "-", "/", EMOJI_SLOT,
    )
    const val EMOJI_SLOT = "☺"
    /** Drawn as a clipboard icon (see KeyIcons.clipboard). */
    const val CLIPBOARD_SLOT = "\u0001clip"

    /** Mini number mode: symbol strip (one per slot, clipboard first, emoji last) over a row of big digits. */
    val MINI_NUMBER_STRIP = listOf(
        CLIPBOARD_SLOT, "~", "€", "%", "&", "*", "+", "=", "<", ">", "|", ";", "'", "\"", "@", "#",
        "(", ")", "_", "!", ",", ".", "?", "[", "]", EMOJI_SLOT,
    )
    val MINI_NUMBER_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "/", ":")

    /** Markers inside the full-mode number grids. */
    const val MORE = "\u0001more"
    const val BACKSPACE = "\u0001del"

    /** Full number mode: 10 columns with a phone-style keypad in the middle (digits are drawn filled). */
    val FULL_NUMBERS_1 = listOf(
        listOf("#", "€", "(", ")", "1", "2", "3", "-", "/", "%"),
        listOf("@", "&", "<", ">", "4", "5", "6", "+", "*", "="),
        listOf(MORE, "_", "'", "\"", "7", "8", "9", "0", ":", BACKSPACE),
    )
    val FULL_NUMBERS_2 = listOf(
        listOf("~", "`", "|", "•", "1", "2", "3", "[", "]", "\\"),
        listOf("£", "¥", "$", "¢", "4", "5", "6", "{", "}", "^"),
        listOf(MORE, "©", "®", "°", "7", "8", "9", "0", "¶", BACKSPACE),
    )
}

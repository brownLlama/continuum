package com.brownllama.continuum.keyboard

import android.content.Context
import android.graphics.Paint
import java.io.File

/** One tab of the emoji panel. [id] picks the tab icon (see KeyIcons.emojiTab). */
class EmojiGroup(val id: String, val emoji: List<String>)

object Emoji {
    const val ASSET = "emoji.tsv"
    const val FREQUENT = "frequent"

    /** Shown in "frequently used" until the user has picked enough emoji of their own. */
    val DEFAULT_FREQUENT = listOf(
        "😂", "❤️", "😍", "🤣", "😊", "🙏", "💕", "😭", "😘", "👍", "😅", "👏", "😁", "🔥", "🥰", "💔",
        "💖", "💙", "😢", "🤔", "😆", "🙄", "💪", "😉", "☺️", "👌", "🤗", "💜", "😔", "😎", "😇", "🌹",
    )

    @Volatile private var cached: List<EmojiGroup>? = null

    /**
     * The groups from `assets/emoji.tsv` (built by tools/make_emoji_asset.py from Unicode's
     * emoji-test.txt), minus emoji the phone's font can't draw. Loaded once per process.
     */
    fun groups(context: Context): List<EmojiGroup> = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): List<EmojiGroup> {
        val paint = Paint()
        return context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val all = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                // Newer emoji than the font knows would show as boxes; keep the list if the check fails wholesale.
                val drawable = all.filter { paint.hasGlyph(it) }.ifEmpty { all }
                EmojiGroup(line.substring(0, tab), drawable)
            }.toList()
        }
    }
}

/** How often each emoji was picked from the panel, for the "frequently used" tab. */
class EmojiHistory(private val file: File) {
    private val counts = LinkedHashMap<String, Int>()

    init {
        if (file.exists()) runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 2) counts[p[0]] = p[1].toIntOrNull() ?: 0
            }
        }
    }

    fun use(emoji: String) {
        // Re-inserting moves it to the end, so among equal counts the most recent wins.
        val c = (counts.remove(emoji) ?: 0) + 1
        counts[emoji] = c
        runCatching { file.writeText(counts.entries.joinToString("") { "${it.key}\t${it.value}\n" }) }
    }

    /** The user's most used emoji first, topped up with [Emoji.DEFAULT_FREQUENT] to [n]. */
    fun top(n: Int): List<String> {
        val mine = counts.entries.toList().asReversed().sortedByDescending { it.value }.map { it.key }
        return (mine + Emoji.DEFAULT_FREQUENT).distinct().take(n)
    }
}

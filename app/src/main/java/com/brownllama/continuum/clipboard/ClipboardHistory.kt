package com.brownllama.continuum.clipboard

import java.io.File

/**
 * Text the user copied, newest first, for the clipboard panel. It stays on the phone in [file]
 * (app-private storage, excluded from backups), keeps at most [MAX_ITEMS] and forgets items after
 * [KEEP_MS]. Copying the same text again moves it back to the top.
 */
class ClipboardHistory(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    private class Item(val text: String, val time: Long)

    private val items = ArrayList<Item>()

    init {
        if (file.exists()) runCatching {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val time = line.substring(0, tab.coerceAtLeast(0)).toLongOrNull() ?: return@forEachLine
                items += Item(unescape(line.substring(tab + 1)), time)
            }
        }
        prune()
    }

    /** The copied texts, newest first. */
    fun texts(): List<String> {
        if (prune()) save()
        return items.map { it.text }
    }

    /** Adds [text] at the top (or moves it there). Blank text is ignored. */
    fun add(text: String) {
        val clip = text.take(MAX_LENGTH)
        if (clip.isBlank()) return
        if (items.firstOrNull()?.text == clip) return
        items.removeAll { it.text == clip }
        items.add(0, Item(clip, now()))
        prune()
        save()
    }

    fun remove(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    /** Drops expired and surplus items; true if anything was dropped. */
    private fun prune(): Boolean {
        val before = items.size
        val oldest = now() - KEEP_MS
        items.removeAll { it.time < oldest }
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        return items.size != before
    }

    private fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(items.joinToString("") { "${it.time}\t${escape(it.text)}\n" })
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    companion object {
        const val MAX_ITEMS = 30
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        /** Longer copies are kept only up to this many characters. */
        const val MAX_LENGTH = 10_000

        /** One line per item: backslashes, tabs and line breaks are escaped. */
        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

        private fun unescape(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(
                        when (s[i + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> s[i + 1]
                        },
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

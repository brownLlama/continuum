package com.brownllama.continuum.clipboard

import com.brownllama.continuum.DiskIo
import com.brownllama.continuum.Loading
import java.io.File
import java.util.concurrent.Executor

/**
 * Text the user copied, newest first, for the clipboard panel. It stays on the phone in [file]
 * (app-private storage, excluded from backups), keeps at most [MAX_ITEMS] and forgets items after
 * [keepMs]. Copying the same text again moves it back to the top.
 *
 * It also remembers the one clip that must stay out ([skip]): what was copied while a password or
 * private field had focus is still on the system clipboard later, and must not be picked up then.
 *
 * With an [io] executor the file is read and written in the background (the keyboard passes one);
 * without it, at once.
 */
class ClipboardHistory(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: Executor? = null,
) {

    private class Item(val text: String, val time: Long)

    private val items = ArrayList<Item>()

    /**
     * How long a copy is kept (setting "Keep copies for"). Older copies go the next time the
     * history is read or added to.
     */
    @Volatile var keepMs = KEEP_MS

    /** When the clip to keep out was copied (0: none, or its time isn't known); saved with the history. */
    private var skippedTime = 0L
    /** That clip's text hash, for clips without a time. Kept in memory only: it is derived from private text. */
    private var skippedHash: Int? = null

    private val loading = Loading(io) {
        if (file.exists()) runCatching {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val head = line.substring(0, tab.coerceAtLeast(0))
                if (head == SKIP) {
                    skippedTime = line.substring(tab + 1).toLongOrNull() ?: 0L
                    return@forEachLine
                }
                val time = head.toLongOrNull() ?: return@forEachLine
                items += Item(unescape(line.substring(tab + 1)), time)
            }
        }
        // Expired copies leave the file as well, not only the list.
        if (prune()) save()
    }

    /** The copied texts, newest first. */
    fun texts(): List<String> {
        loading.await()
        if (prune()) save()
        return items.map { it.text }
    }

    /** Adds [text] at the top (or moves it there). Blank text is ignored. */
    fun add(text: String) {
        loading.await()
        val clip = text.take(MAX_LENGTH)
        if (clip.isBlank()) return
        if (items.firstOrNull()?.text == clip) return
        items.removeAll { it.text == clip }
        items.add(0, Item(clip, now()))
        prune()
        save()
    }

    fun remove(index: Int) {
        loading.await()
        if (index !in items.indices) return
        items.removeAt(index)
        save()
    }

    fun clear() {
        loading.await()
        if (items.isEmpty()) return
        items.clear()
        save()
    }

    /**
     * Keeps a clip out of the history for good: the one copied at [time] (ClipDescription's
     * timestamp; 0 if it has none) whose text hashes to [hash]. Only the latest such clip is
     * remembered, which is enough: only the latest is ever on the system clipboard.
     */
    fun skip(time: Long, hash: Int) {
        loading.await()
        if (time == skippedTime && hash == skippedHash) return
        val timeChanged = time != skippedTime
        skippedTime = time
        skippedHash = hash
        if (timeChanged) save()
    }

    /** Whether the clip copied at [time] with text hash [hash] is the one [skip] set aside. */
    fun isSkipped(time: Long, hash: Int): Boolean {
        loading.await()
        return if (skippedTime != 0L) time == skippedTime else skippedHash != null && hash == skippedHash
    }

    /** Drops expired and surplus items; true if anything was dropped. */
    private fun prune(): Boolean {
        val before = items.size
        val oldest = now() - keepMs
        items.removeAll { it.time < oldest }
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        return items.size != before
    }

    private fun save() {
        val text = buildString {
            if (skippedTime != 0L) append(SKIP).append('\t').append(skippedTime).append('\n')
            for (item in items) append(item.time).append('\t').append(escape(item.text)).append('\n')
        }
        DiskIo.write(file, text, io)
    }

    companion object {
        const val MAX_ITEMS = 30
        /** The longest (and default) time a copy is kept. */
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        /** The file's line for the clip to keep out: "skip", a tab, the time it was copied. */
        private const val SKIP = "skip"
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

package com.brownllama.continuum.clipboard

import com.brownllama.continuum.DiskIo
import com.brownllama.continuum.Loading
import java.io.File
import java.util.concurrent.Executor

/**
 * What the user copied, newest first, for the clipboard panel: texts and pictures. It stays on the
 * phone in [file] (app-private storage, excluded from backups), keeps at most [MAX_ITEMS] (of them
 * at most [MAX_IMAGES] pictures) and forgets items after [keepMs]. Copying the same text again
 * moves it back to the top.
 *
 * A picture is kept as its own file in [imageDir]; the file goes when its item does.
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
    /** Where copied pictures are kept, one file each. */
    val imageDir: File = File(file.absoluteFile.parentFile, IMAGE_DIR),
) {

    /** One copy: its [text], or a picture ([image], a file in [imageDir], [width] × [height] pixels; 0 × 0 if not known). */
    class Clip(val text: String?, val image: File?, val width: Int = 0, val height: Int = 0)

    /** [image]: the picture's file name in [imageDir] (then [text] is null); [source]: which clip it came from. */
    private class Item(
        val text: String?, val time: Long, val image: String? = null, val source: Int = 0,
        val width: Int = 0, val height: Int = 0,
    )

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
                when (head) {
                    SKIP -> skippedTime = line.substring(tab + 1).toLongOrNull() ?: 0L
                    IMAGE -> readImage(line)?.let { items += it }
                    else -> {
                        val time = head.toLongOrNull() ?: return@forEachLine
                        items += Item(unescape(line.substring(tab + 1)), time)
                    }
                }
            }
        }
        // Pictures no item refers to (a copy cut short, a crash) go too.
        val kept = items.mapNotNullTo(HashSet()) { it.image }
        imageDir.listFiles()?.forEach { if (it.name !in kept) it.delete() }
        // Expired copies leave the file as well, not only the list.
        if (prune()) save()
    }

    /**
     * An "image" line: "image", the time, the source, the file name, then its width and height
     * (tab-separated; v0.0.85 wrote no size). Null if broken or the file is gone.
     */
    private fun readImage(line: String): Item? {
        val parts = line.split('\t')
        if (parts.size != 4 && parts.size != 6) return null
        val time = parts[1].toLongOrNull() ?: return null
        val source = parts[2].toIntOrNull() ?: return null
        val name = parts[3]
        if (name.isEmpty() || '/' in name || !File(imageDir, name).isFile) return null
        val width = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val height = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return Item(null, time, name, source, width, height)
    }

    /** The copied texts, newest first (pictures left out). */
    fun texts(): List<String> = clips().mapNotNull { it.text }

    /** Every copy, texts and pictures, newest first: the clipboard panel's cards, in its order. */
    fun clips(): List<Clip> {
        loading.await()
        if (prune()) save()
        return items.map { Clip(it.text, it.image?.let { name -> File(imageDir, name) }, it.width, it.height) }
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

    /** Whether the picture of the clip with this [source] (see [addImage]) is already kept. */
    fun hasImage(source: Int): Boolean {
        loading.await()
        return items.any { it.image != null && it.source == source }
    }

    /**
     * Adds a picture at the top: [image], a file already in [imageDir] (the history now owns it),
     * [width] × [height] pixels, copied from the clip [source] identifies. A clip whose picture is
     * already kept is not added twice.
     */
    fun addImage(image: File, source: Int, width: Int = 0, height: Int = 0) {
        loading.await()
        if (hasImage(source)) {
            image.delete()
            return
        }
        items.add(0, Item(null, now(), image.name, source, width, height))
        prune()
        save()
    }

    fun remove(index: Int) {
        loading.await()
        if (index !in items.indices) return
        forget(listOf(items.removeAt(index)))
        save()
    }

    fun clear() {
        loading.await()
        if (items.isEmpty()) return
        forget(items.toList())
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

    /** Drops expired and surplus items (the oldest pictures first, past [MAX_IMAGES]); true if anything was dropped. */
    private fun prune(): Boolean {
        val oldest = now() - keepMs
        val gone = ArrayList<Item>()
        items.removeAll { (it.time < oldest).also { old -> if (old) gone += it } }
        while (items.size > MAX_ITEMS) gone += items.removeAt(items.lastIndex)
        var pictures = items.count { it.image != null }
        for (i in items.indices.reversed()) {
            if (pictures <= MAX_IMAGES) break
            if (items[i].image != null) {
                gone += items.removeAt(i)
                pictures--
            }
        }
        forget(gone)
        return gone.isNotEmpty()
    }

    /** Deletes the picture files of items that left the history. */
    private fun forget(gone: List<Item>) {
        val names = gone.mapNotNull { it.image }
        if (names.isEmpty()) return
        DiskIo.run(Runnable { names.forEach { File(imageDir, it).delete() } }, io)
    }

    private fun save() {
        val text = buildString {
            if (skippedTime != 0L) append(SKIP).append('\t').append(skippedTime).append('\n')
            for (item in items) {
                if (item.image != null) {
                    append(IMAGE).append('\t').append(item.time).append('\t').append(item.source).append('\t').append(item.image)
                        .append('\t').append(item.width).append('\t').append(item.height).append('\n')
                } else {
                    append(item.time).append('\t').append(escape(item.text.orEmpty())).append('\n')
                }
            }
        }
        DiskIo.write(file, text, io)
    }

    companion object {
        const val MAX_ITEMS = 30
        /** Of [MAX_ITEMS], at most this many pictures (they take far more room than text). */
        const val MAX_IMAGES = 10
        /** A copied picture larger than this isn't kept. */
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        /** The longest (and default) time a copy is kept. */
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        /** The file's line for the clip to keep out: "skip", a tab, the time it was copied. */
        private const val SKIP = "skip"
        /** The file's line for a picture (see [readImage]). Older versions skip it, as it isn't a time. */
        private const val IMAGE = "image"
        /** The pictures' folder, next to the history's file. */
        const val IMAGE_DIR = "clip_images"
        /** Longer copies are kept only up to this many characters. */
        const val MAX_LENGTH = 10_000

        /** The picture types kept, by MIME type, with the file extension each is saved under. */
        private val TYPES = linkedMapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/heic" to "heic",
            "image/heif" to "heic",
            "image/bmp" to "bmp",
        )

        /** The extension a picture of type [mime] is saved under; null for a type that isn't kept. */
        fun extensionFor(mime: String?): String? = TYPES[mime?.lowercase()]

        /** A kept picture's MIME type, from its extension; null if it isn't one. */
        fun mimeOf(image: File): String? = TYPES.entries.firstOrNull { it.value == image.extension }?.key

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

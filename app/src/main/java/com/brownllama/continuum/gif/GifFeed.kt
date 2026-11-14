package com.brownllama.continuum.gif

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.R
import com.brownllama.continuum.keyboard.KeyboardView
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs the GIF panel for the IME: fetches pages from KLIPY, downloads and decodes previews into
 * the view's tiles, and downloads the file to send. Network work happens on a small thread pool;
 * results are applied on the main thread, and answers to an older search are dropped.
 */
class GifFeed(private val context: Context, private val prefs: SharedPreferences) {
    var view: KeyboardView? = null
    /** Up to three downloads at once; idle threads end after a while, so nothing runs between searches. */
    private val io = ThreadPoolExecutor(3, 3, IDLE_S, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.cacheDir, "gifs").apply { mkdirs() }

    /** Increases with every new search, so late answers to an older one are ignored. */
    private var generation = 0
    private var query = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private val gifs = ArrayList<Gif>()

    /** When the cache was last tidied (uptime of this process is enough: it only spaces the tidying out). */
    private var prunedAt = 0L

    init {
        pruneCache()
    }

    /**
     * Keeps the cache small: previews and sent GIFs are only needed for a while. Files older than
     * [CACHE_MS] go; if what is left is still over [MAX_CACHE_BYTES], the oldest of it goes too,
     * except files touched in the last [RECENT_MS] (one may be on its way to an app right now).
     * Runs when the keyboard starts and, at most every [PRUNE_EVERY_MS], when the GIFs are let go.
     */
    private fun pruneCache() {
        prunedAt = System.currentTimeMillis()
        background { prune(dir, System.currentTimeMillis()) }
    }

    /** The free key the user entered in settings, or null. */
    private fun client(): KlipyClient? {
        val key = prefs.getString(Prefs.KLIPY_KEY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var customer = prefs.getString(Prefs.GIF_CUSTOMER_ID, null)
        if (customer == null) {
            customer = UUID.randomUUID().toString()
            prefs.edit { putString(Prefs.GIF_CUSTOMER_ID, customer) }
        }
        return KlipyClient(key, customer, Locale.getDefault().country.lowercase())
    }

    /**
     * Starts a new search; an empty [q] shows trending GIFs. A [live] search (while typing) is skipped
     * if the query hasn't changed, and keeps the GIFs shown until the new ones arrive.
     */
    fun search(q: String, live: Boolean = false) {
        if (live && q.trim() == query) return
        val keepShown = live && gifs.isNotEmpty()
        generation++
        query = q.trim()
        page = 0
        hasNext = true
        loading = false
        gifs.clear()
        if (client() == null) {
            view?.setGifs(emptyList(), context.getString(R.string.gif_no_key), append = false)
            return
        }
        if (!keepShown) view?.setGifs(emptyList(), context.getString(R.string.gif_loading), append = false)
        more()
    }

    /** Loads the next page, if there is one and none is loading. */
    fun more() {
        if (loading || !hasNext || page >= MAX_PAGES) return
        val client = client() ?: return
        loading = true
        val gen = generation
        val q = query
        val next = page + 1
        background {
            val result = runCatching { if (q.isEmpty()) client.trending(next) else client.search(q, next) }
            main.post {
                if (gen != generation) return@post
                loading = false
                val pageOf = result.getOrNull()
                if (pageOf == null) {
                    hasNext = false
                    if (gifs.isEmpty()) view?.setGifs(emptyList(), context.getString(R.string.gif_error), append = false)
                    return@post
                }
                page = next
                hasNext = pageOf.hasNext
                gifs += pageOf.gifs
                val tiles = pageOf.gifs.map { KeyboardView.GifTile(it.aspect) }
                val message = if (gifs.isEmpty()) context.getString(R.string.gif_none) else null
                view?.setGifs(tiles, message, append = page > 1)
                pageOf.gifs.zip(tiles).forEach { (g, t) -> loadPreview(g, t, gen) }
            }
        }
    }

    fun gif(index: Int): Gif? = gifs.getOrNull(index)

    private fun loadPreview(gif: Gif, tile: KeyboardView.GifTile, gen: Int) {
        background {
            val image = runCatching {
                val file = File(dir, "${safe(gif.slug)}_p.gif")
                if (!file.exists()) KlipyClient.download(gif.previewUrl, file)
                decode(file)
            }.getOrNull() ?: return@background
            main.post { if (gen == generation) view?.setGifImage(tile, image) }
        }
    }

    /** Downloads the GIF to send (cached), then calls [done] on the main thread with the file or null. */
    fun fetchForSending(gif: Gif, done: (File?) -> Unit) {
        val client = client()
        background {
            val file = File(dir, "${safe(gif.slug)}.gif")
            val ok = runCatching {
                // A cached GIF counts as fresh again, so tidying the cache can't take it mid-send.
                if (file.exists()) file.setLastModified(System.currentTimeMillis()) else KlipyClient.download(gif.sendUrl, file)
            }.isSuccess
            client?.shared(gif.slug)
            main.post { done(if (ok) file else null) }
        }
    }

    private fun safe(slug: String) = slug.replace(UNSAFE, "_").take(80).ifEmpty { "gif" }

    /**
     * An animated drawable (Android 9+), or the first frame on older versions. Oversized images are
     * decoded at a reduced size, so a huge or malformed file can't exhaust the keyboard's memory.
     */
    private fun decode(file: File): Drawable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
            }.also {
                (it as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            BitmapFactory.decodeFile(file.path, options)?.toDrawable(context.resources)
        }

    /** The power-of-two shrink that brings a [w] × [h] image under [MAX_PIXELS] (1 = full size). */
    private fun sampleSize(w: Int, h: Int): Int {
        var sample = 1
        while (w.toLong() / sample * (h.toLong() / sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    /**
     * Forgets the loaded GIFs (the view drops their images too), to give memory back. The next time
     * the panel opens it searches again; previews are still cached on disk for a day.
     */
    fun release() {
        generation++
        loading = false
        hasNext = false
        gifs.clear()
        if (System.currentTimeMillis() - prunedAt > PRUNE_EVERY_MS) pruneCache()
    }

    /** Runs [task] on [io], unless the keyboard has shut down (then the task is dropped). */
    private fun background(task: () -> Unit) {
        if (!io.isShutdown) runCatching { io.execute(task) }
    }

    fun shutdown() {
        io.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".gifs"
        /** Characters not allowed in a cached file's name. */
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
        private const val CACHE_MS = 24 * 60 * 60 * 1000L
        /** The cache's size limit; previews are small, so this is mostly the GIFs that were sent. */
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val RECENT_MS = 10 * 60 * 1000L
        private const val PRUNE_EVERY_MS = 60 * 60 * 1000L

        /** Tidies the cache folder [dir] as of [now] (see [pruneCache]). */
        internal fun prune(dir: File, now: Long, maxBytes: Long = MAX_CACHE_BYTES) {
            val kept = ArrayList<File>()
            var total = 0L
            for (f in dir.listFiles().orEmpty()) {
                if (f.lastModified() < now - CACHE_MS) {
                    f.delete()
                } else {
                    kept += f
                    total += f.length()
                }
            }
            if (total <= maxBytes) return
            kept.sortBy { it.lastModified() }
            for (f in kept) {
                if (total <= maxBytes || f.lastModified() > now - RECENT_MS) break
                total -= f.length()
                f.delete()
            }
        }
        private const val IDLE_S = 30L
        /** Infinite scroll stops after this many pages (150 GIFs), so the panel's memory stays bounded. */
        private const val MAX_PAGES = 5
        /** Previews are decoded at no more than about 1 megapixel (KLIPY's are far smaller). */
        private const val MAX_PIXELS = 1_000_000L
    }
}

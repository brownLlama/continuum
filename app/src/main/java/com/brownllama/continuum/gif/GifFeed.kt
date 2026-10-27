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
import java.util.concurrent.Executors

/**
 * Runs the GIF panel for the IME: fetches pages from KLIPY, downloads and decodes previews into
 * the view's tiles, and downloads the file to send. Network work happens on a small thread pool;
 * results are applied on the main thread, and answers to an older search are dropped.
 */
class GifFeed(private val context: Context, private val prefs: SharedPreferences) {
    var view: KeyboardView? = null
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.cacheDir, "gifs").apply { mkdirs() }

    /** Increases with every new search, so late answers to an older one are ignored. */
    private var generation = 0
    private var query = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private val gifs = ArrayList<Gif>()

    init {
        // Keep the cache small: previews and sent GIFs are only needed for a while.
        io.execute {
            val old = System.currentTimeMillis() - CACHE_MS
            dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        }
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
        if (loading || !hasNext) return
        val client = client() ?: return
        loading = true
        val gen = generation
        val q = query
        val next = page + 1
        io.execute {
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
        io.execute {
            val image = runCatching {
                val file = File(dir, "${safe(gif.slug)}_p.gif")
                if (!file.exists()) KlipyClient.download(gif.previewUrl, file)
                decode(file)
            }.getOrNull() ?: return@execute
            main.post { if (gen == generation) view?.setGifImage(tile, image) }
        }
    }

    /** Downloads the GIF to send (cached), then calls [done] on the main thread with the file or null. */
    fun fetchForSending(gif: Gif, done: (File?) -> Unit) {
        val client = client()
        io.execute {
            val file = File(dir, "${safe(gif.slug)}.gif")
            val ok = runCatching { if (!file.exists()) KlipyClient.download(gif.sendUrl, file) }.isSuccess
            client?.shared(gif.slug)
            main.post { done(if (ok) file else null) }
        }
    }

    private fun safe(slug: String) = slug.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifEmpty { "gif" }

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

    fun shutdown() = io.shutdownNow()

    companion object {
        const val AUTHORITY_SUFFIX = ".gifs"
        private const val CACHE_MS = 24 * 60 * 60 * 1000L
        /** Previews are decoded at no more than about 1 megapixel (KLIPY's are far smaller). */
        private const val MAX_PIXELS = 1_000_000L
    }
}

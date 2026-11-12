package com.brownllama.continuum.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One sticker: built in (an asset under [ASSET_DIR]) or added by the user (a file in [userDir]). */
class Sticker(val name: String, val builtIn: Boolean)

/**
 * The stickers: Continuum's own (Schibu the llama; WebP assets under [ASSET_DIR]) and the ones the user added from their photos.
 *
 * Added images are fitted into [SIZE] × [SIZE] on a transparent square, as messengers expect of
 * stickers, and kept as WebP in the app's private files (never backed up, see
 * data_extraction_rules). Files are read and written on a background thread; callbacks run on the
 * main thread.
 */
class StickerStore(private val context: Context) {
    private val userDir = File(context.filesDir, "stickers").apply { mkdirs() }
    /** Copies handed to the app being typed in (see [forSending]); shared through the FileProvider. */
    private val sendDir = File(context.cacheDir, SEND_DIR).apply { mkdirs() }
    private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val main = Handler(Looper.getMainLooper())

    /** The user's stickers (newest first), then the built-in ones. */
    fun list(): List<Sticker> {
        val mine = userDir.listFiles { f -> f.name.endsWith(".webp") }.orEmpty()
            .sortedByDescending { it.name }
            .map { Sticker(it.name, builtIn = false) }
        val ours = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".webp") }.sorted()
            .map { Sticker(it, builtIn = true) }
        return mine + ours
    }

    /** Small copies of [stickers] for the panel, about [px] pixels square, in the same order. */
    fun thumbnails(stickers: List<Sticker>, px: Int, done: (List<Bitmap?>) -> Unit) {
        io.execute {
            val out = stickers.map { s -> runCatching { decode(s, px) }.getOrNull() }
            main.post { done(out) }
        }
    }

    /**
     * A file of [s] for the app being typed in, as WebP when [webp] or else PNG (for apps that
     * don't take WebP). Null if it couldn't be written.
     */
    fun forSending(s: Sticker, webp: Boolean, done: (File?) -> Unit) {
        io.execute {
            val file = runCatching {
                val base = s.name.substringBeforeLast('.')
                val dest = File(sendDir, base + if (webp) ".webp" else ".png")
                if (webp) {
                    open(s).use { input -> dest.outputStream().use { input.copyTo(it) } }
                } else {
                    val bmp = open(s).use { BitmapFactory.decodeStream(it) } ?: error("undecodable")
                    dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                dest
            }.getOrNull()
            main.post { done(file) }
        }
    }

    /** Adds the image at [uri] (from the photo picker) as a new sticker; [done] gets whether it worked. */
    fun add(uri: Uri, done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching {
                val src = load(uri)
                val sticker = fit(src)
                if (sticker !== src) src.recycle()
                val name = "mine_${System.currentTimeMillis()}.webp"
                val tmp = File(userDir, "$name.tmp")
                tmp.outputStream().use { sticker.compress(webpFormat(), WEBP_QUALITY, it) }
                sticker.recycle()
                tmp.renameTo(File(userDir, name))
            }.getOrDefault(false)
            main.post { done(ok) }
        }
    }

    /** Deletes one of the user's own stickers (built-in ones stay). */
    fun remove(s: Sticker, done: () -> Unit) {
        if (s.builtIn) return
        io.execute {
            File(userDir, s.name).delete()
            // The copies made for sending (see forSending), in either format.
            val base = s.name.substringBeforeLast('.')
            File(sendDir, "$base.webp").delete()
            File(sendDir, "$base.png").delete()
            main.post { done() }
        }
    }

    fun shutdown() = io.shutdown()

    private fun open(s: Sticker) =
        if (s.builtIn) context.assets.open("$ASSET_DIR/${s.name}") else File(userDir, s.name).inputStream()

    private fun decode(s: Sticker, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(s).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open(s).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** The picked image, already scaled down near [SIZE] (and turned upright, on Android 9+). */
    private fun load(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, SIZE.toFloat() / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("undecodable image")
    }

    /** [src] scaled to fit [SIZE] × [SIZE], centred on a transparent square. */
    private fun fit(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = min(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val l = (SIZE - w) / 2
        val t = (SIZE - h) / 2
        Canvas(out).drawBitmap(
            src, null, android.graphics.RectF(l, t, l + w, t + h),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    @Suppress("DEPRECATION") // WEBP: the only WebP format before Android 11.
    private fun webpFormat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    companion object {
        const val ASSET_DIR = "stickers"
        const val SEND_DIR = "stickers"
        /** Stickers are this many pixels square, the size WhatsApp and others use. */
        const val SIZE = 512
        private const val WEBP_QUALITY = 90
    }
}

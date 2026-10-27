package com.brownllama.continuum.gif

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One GIF: a small [previewUrl] for the panel and a medium [sendUrl] that is sent. */
class Gif(val slug: String, val title: String, val previewUrl: String, val sendUrl: String, val aspect: Float)

class GifPage(val gifs: List<Gif>, val hasNext: Boolean)

/**
 * KLIPY's GIF API (https://docs.klipy.com; Tenor's API closed in June 2026). The app key is part of
 * the path: `api/v1/{key}/gifs/search?q=…`. Calls block, so run them off the main thread.
 */
class KlipyClient(
    private val key: String,
    private val customerId: String,
    private val locale: String,
) {
    fun trending(page: Int): GifPage = fetch("trending", page, null)

    fun search(query: String, page: Int): GifPage = fetch("search", page, query)

    /** KLIPY's "share trigger": tells it a GIF was sent, which improves its ranking. Best effort. */
    fun shared(slug: String) {
        runCatching {
            open("$BASE/${enc(key)}/gifs/share/${enc(slug)}?customer_id=${enc(customerId)}").run {
                requestMethod = "POST"
                responseCode
                disconnect()
            }
        }
    }

    private fun fetch(kind: String, page: Int, query: String?): GifPage {
        val params = buildString {
            append("page=").append(page).append("&per_page=").append(PER_PAGE)
            if (query != null) append("&q=").append(enc(query))
            append("&customer_id=").append(enc(customerId))
            append("&locale=").append(enc(locale))
            append("&content_filter=off")
            append("&format_filter=gif")
        }
        return parse(get("$BASE/${enc(key)}/gifs/$kind?$params"))
    }

    companion object {
        private const val BASE = "https://api.klipy.com/api/v1"
        const val PER_PAGE = 30
        private const val TIMEOUT_MS = 10_000
        /** Caps on what is accepted from the network, so a bad answer can't fill memory or storage. */
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024
        private const val MAX_GIF_BYTES = 15L * 1024 * 1024

        private fun enc(s: String) = Uri.encode(s)

        /** Only https links are ever opened, sent or pasted (no http, file:, content: …). */
        fun isSafeUrl(url: String): Boolean =
            runCatching { URL(url).protocol == "https" && URL(url).host.isNotEmpty() }.getOrDefault(false)

        private fun open(url: String): HttpURLConnection {
            require(isSafeUrl(url)) { "not an https URL" }
            return (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Java never follows a redirect from https to http, so every hop stays encrypted.
            }
        }

        private fun get(url: String): String {
            val c = open(url)
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                return String(readCapped(c, MAX_JSON_BYTES), Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }

        /** Saves [url] to [to] (via a temporary file, so a half download is never used). */
        fun download(url: String, to: File) {
            val c = open(url)
            val tmp = File(to.path + ".part")
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                tmp.writeBytes(readCapped(c, MAX_GIF_BYTES))
                if (!tmp.renameTo(to)) error("rename failed")
            } finally {
                tmp.delete()
                c.disconnect()
            }
        }

        /** The response body, refusing anything larger than [max] bytes. */
        private fun readCapped(c: HttpURLConnection, max: Long): ByteArray {
            if (c.contentLengthLong > max) error("response too large")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) error("response too large")
                }
            }
            return out.toByteArray()
        }

        /**
         * `{"result":true,"data":{"data":[…],"has_next":true}}`. Each item has a slug, title, type
         * ("gif"; ads are skipped) and `file.{hd,md,sm,xs}.{gif,webp,…}.{url,width,height}`.
         */
        fun parse(json: String): GifPage {
            val data = JSONObject(json).optJSONObject("data") ?: return GifPage(emptyList(), false)
            val items = data.optJSONArray("data")
            val gifs = ArrayList<Gif>()
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                if (item.optString("type", "gif") != "gif") continue
                val file = item.optJSONObject("file") ?: continue
                val preview = pick(file, "sm", "xs", "md", "hd") ?: continue
                val send = pick(file, "md", "hd", "sm") ?: continue
                val w = preview.optDouble("width", 1.0)
                val h = preview.optDouble("height", 1.0)
                gifs += Gif(
                    slug = item.optString("slug"),
                    title = item.optString("title"),
                    previewUrl = preview.getString("url"),
                    sendUrl = send.getString("url"),
                    aspect = if (w > 0 && h > 0) (w / h).toFloat().coerceIn(MIN_ASPECT, MAX_ASPECT) else 1f,
                )
            }
            return GifPage(gifs, data.optBoolean("has_next", false))
        }

        /** Odd sizes in an answer can't stretch a tile beyond these width / height ratios. */
        private const val MIN_ASPECT = 0.25f
        private const val MAX_ASPECT = 4f

        /** The first of [sizes] that has a GIF rendition with an https link. */
        private fun pick(file: JSONObject, vararg sizes: String): JSONObject? = sizes.firstNotNullOfOrNull { s ->
            file.optJSONObject(s)?.optJSONObject("gif")?.takeIf { isSafeUrl(it.optString("url")) }
        }
    }
}

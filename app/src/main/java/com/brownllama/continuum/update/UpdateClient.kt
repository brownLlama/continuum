package com.brownllama.continuum.update

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The newest release on GitHub: its [version], its page and the link to its APK. */
class Release(val version: String, val pageUrl: String, val apkUrl: String, val apkBytes: Long)

/** Something went wrong with the check; [message] is for the log, the user gets a plain sentence. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads version numbers like "0.0.37" (a leading "v" is fine). */
object Versions {
    /** The numbers of [text], or null if it is not a plain dotted version. */
    fun parse(text: String?): List<Int>? {
        val parts = text?.trim()?.removePrefix("v")?.split('.') ?: return null
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    /** Whether [latest] is a higher version than [installed]; false when either is not a version. */
    fun isNewer(installed: String?, latest: String?): Boolean {
        val a = parse(installed) ?: return false
        val b = parse(latest) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }
}

/** The one thing the check needs from the network; replaced in tests. */
interface Transport {
    /** The text at an https [url] (at most [maxBytes] of it). */
    fun getText(url: String, maxBytes: Long): String
}

/**
 * Asks GitHub for the newest release of the app. It only reads; the app never downloads or installs
 * anything itself (that would need Android's "install unknown apps" permission, which Google Play
 * Protect treats as a warning sign). The user is sent to the release's download link in the browser.
 *
 * Nothing is sent but the request for the release (no identifiers), and only when the user asks.
 * Calls block, so run them off the main thread.
 */
class UpdateClient(private val net: Transport = HttpsTransport) {
    /** The latest release, or [UpdateException] if it can't be read or has no APK. */
    fun latest(): Release = try {
        parseRelease(net.getText(LATEST_URL, MAX_JSON_BYTES))
    } catch (e: UpdateException) {
        throw e
    } catch (e: Exception) {
        throw UpdateException("could not read the latest release", e)
    }

    companion object {
        const val APK_NAME = "Continuum.apk"
        const val REPO = "brownLlama/continuum"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        /** Only links into this repository's releases are ever opened. */
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        private const val PAGE_PREFIX = "https://github.com/$REPO/releases/"
        private const val MAX_JSON_BYTES = 1L * 1024 * 1024

        /** Reads GitHub's "latest release" answer. */
        fun parseRelease(json: String): Release {
            val o = try { JSONObject(json) } catch (e: org.json.JSONException) { throw UpdateException("unreadable answer", e) }
            val tag = o.optString("tag_name")
            if (Versions.parse(tag) == null) throw UpdateException("odd version: $tag")
            val page = o.optString("html_url").takeIf { it.startsWith(PAGE_PREFIX) } ?: "${PAGE_PREFIX}latest"
            var apk: String? = null
            var size = -1L
            val assets = o.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val url = a.optString("browser_download_url")
                if (url.startsWith(DOWNLOAD_PREFIX) && a.optString("name") == APK_NAME) {
                    apk = url
                    size = a.optLong("size", -1L)
                }
            }
            return Release(tag.removePrefix("v"), page, apk ?: throw UpdateException("the release has no $APK_NAME"), size)
        }
    }
}

/** The real network: https only, with timeouts and a cap on size. */
object HttpsTransport : Transport {
    private const val TIMEOUT_MS = 15_000

    override fun getText(url: String, maxBytes: Long): String {
        require(runCatching { URL(url).protocol == "https" }.getOrDefault(false)) { "not an https URL" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub wants a user agent; this says nothing about the phone or its owner.
            setRequestProperty("User-Agent", "Continuum-update-check")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode !in 200..299) throw UpdateException("HTTP ${c.responseCode}")
            return String(readCapped(c.inputStream, maxBytes), Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw UpdateException("answer too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

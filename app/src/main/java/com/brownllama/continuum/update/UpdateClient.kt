package com.brownllama.continuum.update

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The newest release on GitHub: its [version], the APK to download and the file of checksums. */
class Release(
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkBytes: Long,
    val sumsUrl: String?,
)

/** Something went wrong with an update; [message] is for the log, the user gets a plain sentence. */
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

/** The two things the update needs from the network; replaced in tests. */
interface Transport {
    /** The text at an https [url] (at most [maxBytes] of it). */
    fun getText(url: String, maxBytes: Long): String

    /** Downloads an https [url] into [dest], at most [maxBytes], calling [progress] with (bytes so far, total or -1). */
    fun download(url: String, dest: File, maxBytes: Long, progress: (Long, Long) -> Unit)
}

/**
 * Finds the newest release of the app on GitHub and downloads its APK, checking it against the
 * release's SHA256SUMS.txt. Android itself then only installs it if it is signed with the same key
 * as the installed app, so an update can't be swapped for another app.
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

    /**
     * Downloads [release]'s APK into [dir] and returns it, once its SHA-256 is the one the release
     * lists. A file that doesn't match is deleted.
     */
    fun download(release: Release, dir: File, progress: (Long, Long) -> Unit): File {
        val sums = release.sumsUrl ?: throw UpdateException("the release lists no checksums")
        val expected = parseSums(net.getText(sums, MAX_SUMS_BYTES), APK_NAME)
            ?: throw UpdateException("no checksum for $APK_NAME")
        dir.mkdirs()
        val file = File(dir, APK_NAME)
        file.delete()
        try {
            net.download(release.apkUrl, file, MAX_APK_BYTES, progress)
            if (!sha256(file).equals(expected, ignoreCase = true)) throw UpdateException("checksum does not match")
            return file
        } catch (e: Exception) {
            file.delete()
            throw e as? UpdateException ?: UpdateException("download failed", e)
        }
    }

    companion object {
        const val APK_NAME = "Continuum.apk"
        const val SUMS_NAME = "SHA256SUMS.txt"
        const val REPO = "brownLlama/continuum"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        /** Only files of this repository's releases are downloaded. */
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        private const val PAGE_PREFIX = "https://github.com/$REPO/releases/"
        private const val MAX_JSON_BYTES = 1L * 1024 * 1024
        private const val MAX_SUMS_BYTES = 16L * 1024
        /** The release APK is about 5 MB; anything far over that is not it. */
        const val MAX_APK_BYTES = 40L * 1024 * 1024

        /** Reads GitHub's "latest release" answer. */
        fun parseRelease(json: String): Release {
            val o = try { JSONObject(json) } catch (e: org.json.JSONException) { throw UpdateException("unreadable answer", e) }
            val tag = o.optString("tag_name")
            if (Versions.parse(tag) == null) throw UpdateException("odd version: $tag")
            val page = o.optString("html_url").takeIf { it.startsWith(PAGE_PREFIX) } ?: "${PAGE_PREFIX}latest"
            var apk: String? = null
            var size = -1L
            var sums: String? = null
            val assets = o.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val url = a.optString("browser_download_url")
                if (!url.startsWith(DOWNLOAD_PREFIX)) continue
                when (a.optString("name")) {
                    APK_NAME -> { apk = url; size = a.optLong("size", -1L) }
                    SUMS_NAME -> sums = url
                }
            }
            return Release(tag.removePrefix("v"), page, apk ?: throw UpdateException("the release has no $APK_NAME"), size, sums)
        }

        /** The hash listed for [name] in a `sha256sum` file ("hash  name" per line). */
        fun parseSums(text: String, name: String): String? = text.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name && it[0].matches(Regex("[0-9a-fA-F]{64}")) }
            ?.get(0)

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** The real network: https only, with timeouts and caps on size. */
object HttpsTransport : Transport {
    private const val TIMEOUT_MS = 15_000

    private fun open(url: String): HttpURLConnection {
        require(runCatching { URL(url).protocol == "https" }.getOrDefault(false)) { "not an https URL" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub wants a user agent; this says nothing about the phone or its owner.
            setRequestProperty("User-Agent", "Continuum-update-check")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            // Java follows redirects only between https links, so every hop stays encrypted.
        }
    }

    override fun getText(url: String, maxBytes: Long): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw UpdateException("HTTP ${c.responseCode}")
            return String(readCapped(c.inputStream, maxBytes), Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    override fun download(url: String, dest: File, maxBytes: Long, progress: (Long, Long) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw UpdateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > maxBytes) throw UpdateException("file too large")
            FileOutputStream(dest).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > maxBytes) throw UpdateException("file too large")
                        out.write(buf, 0, n)
                        val now = System.nanoTime()
                        if (now - last > 100_000_000L) { last = now; progress(done, total) }
                    }
                    progress(done, total)
                }
            }
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

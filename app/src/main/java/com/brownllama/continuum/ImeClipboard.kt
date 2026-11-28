// The clipboard panel: capturing what was copied (text and pictures) and pasting it.
package com.brownllama.continuum

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import com.brownllama.continuum.clipboard.ClipboardHistory
import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.keyboard.KeyboardView
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Saves what is on the system clipboard to the history: its text, or the picture it holds.
 * Skipped when the copying app marked it sensitive (passwords, one-time codes; Android 13+
 * password managers do) and while a password, number or incognito field has focus
 * ([privateField]). Of clips that are links to other apps' content, only pictures are opened
 * (see [clipImage]); anything else is never read.
 *
 * A clip stays on the system clipboard long after it was copied, and this is called again
 * later (a new field, the panel opening). So a clip copied in a private field ([fresh]: the
 * clipboard just changed) is set aside for good ([ClipboardHistory.skip]), not just for now.
 */
internal fun ContinuumIME.captureClip(fresh: Boolean = false) {
    if (!clipboardOn) return
    val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
    if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
    val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
    val time = clip.description?.timestamp ?: 0L
    val image = clipImage(clip.description, item)
    val text = if (image == null) item.text?.toString() ?: return else null
    // A picture is known by its address and when it was copied: apps reuse one address for every picture they copy.
    val hash = if (image != null) "$time|$image".hashCode() else text.hashCode()
    if (privateField) {
        if (fresh) clipboard.skip(time, hash)
        return
    }
    // Copied anew outside a private field, the same clip is wanted; seen again, it is still the private clip.
    if (!fresh && clipboard.isSkipped(time, hash)) return
    when {
        text != null -> clipboard.add(text)
        image != null && hash !in copyingImages && !clipboard.hasImage(hash) -> copyImage(image, hash)
    }
}

/**
 * The picture a clip holds: a content address from another app, of an image type. Null for
 * anything else, which is never opened (and for our own files, which are already ours).
 */
private fun ContinuumIME.clipImage(description: ClipDescription?, item: ClipData.Item): Uri? {
    val uri = item.uri ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority == packageName + GifFeed.AUTHORITY_SUFFIX) return null
    val isImage = description?.hasMimeType("image/*") == true ||
        runCatching { contentResolver.getType(uri) }.getOrNull()?.startsWith("image/") == true
    return uri.takeIf { isImage }
}

/**
 * Copies the picture at [uri] into the history's folder, in the background. Android lets the
 * keyboard read another app's copied picture only for a while, and the panel needs it later.
 */
private fun ContinuumIME.copyImage(uri: Uri, source: Int) {
    copyingImages += source
    val dir = clipboard.imageDir
    val resolver = contentResolver
    DiskIo.executor.execute {
        val saved = runCatching { saveImage(resolver, uri, dir) }.getOrNull()
        main.post {
            copyingImages -= source
            if (saved == null) return@post
            // Switched off while it was copied: the picture isn't kept.
            if (!clipboardOn) {
                saved.file.delete()
                return@post
            }
            clipboard.addImage(saved.file, source, saved.width, saved.height)
            if (view?.panel == KeyboardView.Panel.CLIPBOARD) showClips()
        }
    }
}

/** A copied picture saved by [saveImage], and its size in pixels. */
private class SavedImage(val file: File, val width: Int, val height: Int)

/**
 * The picture at [uri] saved in [dir] under its own type's extension; null if it can't be read,
 * is larger than [ClipboardHistory.MAX_IMAGE_BYTES], or isn't a picture Android can show.
 */
private fun saveImage(resolver: ContentResolver, uri: Uri, dir: File): SavedImage? {
    dir.mkdirs()
    val tmp = File.createTempFile("clip", ".tmp", dir)
    try {
        val copied = resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { copyAtMost(input, it, ClipboardHistory.MAX_IMAGE_BYTES) }
        } ?: false
        if (!copied) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val ext = ClipboardHistory.extensionFor(bounds.outMimeType) ?: return null
        val dest = File(dir, tmp.nameWithoutExtension + "." + ext)
        return if (tmp.renameTo(dest)) SavedImage(dest, bounds.outWidth, bounds.outHeight) else null
    } finally {
        tmp.delete()
    }
}

/** Copies [input] to [output]; false (and stops) once more than [limit] bytes have come. */
private fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Boolean {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return true
        total += n
        if (total > limit) return false
        output.write(buffer, 0, n)
    }
}

/** Why the clipboard panel has nothing to show (a string resource), or 0 if it may show the history. */
internal fun ContinuumIME.clipboardBlocked(): Int = when {
    !clipboardOn -> R.string.clip_off
    // Copied text is as private as what's on the phone: none of it while the phone is locked.
    runCatching { getSystemService(KeyguardManager::class.java)?.isDeviceLocked }.getOrNull() == true -> R.string.clip_locked
    else -> 0
}

/**
 * Hands the panel its cards: the texts at once, the pictures' thumbnails as they're decoded (in
 * the background; kept in [clipThumbs] for the next time).
 */
internal fun ContinuumIME.showClips() {
    val v = view ?: return
    val blocked = clipboardBlocked()
    if (blocked != 0) return v.setClips(emptyList(), getString(blocked))
    val clips = clipboard.clips()
    val files = clips.mapNotNull { it.image }
    clipThumbs.keys.retainAll(files.mapTo(HashSet()) { it.name })
    v.setClips(
        clips.map { it.text },
        images = clips.map { c -> c.image?.let { clipThumbs[it.name] } },
        aspects = clips.map { c -> if (c.width > 0 && c.height > 0) c.height.toFloat() / c.width else 0f },
    )
    val missing = files.filter { it.name !in clipThumbs }
    if (missing.isEmpty()) return
    // A card is about half the keyboard wide, and at most as tall as the panel; it isn't laid out yet the first time.
    val w = v.clipCellW.toInt().takeIf { it > 1 } ?: (resources.displayMetrics.widthPixels / 2)
    val maxH = v.clipMaxCardH.toInt().takeIf { it > 1 } ?: (resources.displayMetrics.heightPixels / 3)
    DiskIo.executor.execute {
        val decoded = missing.associate { it.name to runCatching { thumbnail(it, w, maxH) }.getOrNull() }
        main.post {
            for ((name, bitmap) in decoded) if (bitmap != null) clipThumbs[name] = bitmap
            val shown = view ?: return@post
            if (shown.panel != KeyboardView.Panel.CLIPBOARD || clipboardBlocked() != 0) return@post
            shown.setClipImages(clipboard.clips().map { c -> c.image?.let { clipThumbs[it.name] } })
        }
    }
}

/**
 * [file] at the size its card shows it: [w] wide, or narrower for a picture taller than [maxH]
 * (the tallest card). Smaller pictures aren't enlarged here; the card scales them up.
 */
private fun thumbnail(file: File, w: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val fit = min(w.toFloat() / bounds.outWidth, maxH.toFloat() / bounds.outHeight)
    val tw = max(1, (bounds.outWidth * fit).roundToInt())
    val th = max(1, (bounds.outHeight * fit).roundToInt())
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    if (decoded.width <= tw) return decoded
    val scaled = Bitmap.createScaledBitmap(decoded, tw, th, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

internal fun ContinuumIME.handleClipboardOpen() {
    captureClip()
    showClips()
}

internal fun ContinuumIME.handleClipPicked(index: Int) {
    if (clipboardBlocked() != 0) return showClips()
    val clip = clipboard.clips().getOrNull(index) ?: return
    val image = clip.image
    val ic = if (image == null) editor() ?: return else null
    if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
    if (image != null) {
        finishComposing()
        pasteImage(image)
    } else {
        ic?.commitText(clip.text.orEmpty(), 1)
    }
    autoSpace = false
    lastCommit = null
    updateAutoShift()
    showPredictions()
}

/**
 * Pastes a kept picture the way apps take images from a keyboard: a content address (our
 * FileProvider) through [InputConnection.commitContent], when the field takes its type. Apps
 * that take no pictures there get Android's share sheet, so it can still be sent from there.
 */
private fun ContinuumIME.pasteImage(image: File) {
    val mime = ClipboardHistory.mimeOf(image)
    val uri = runCatching { FileProvider.getUriForFile(this, packageName + GifFeed.AUTHORITY_SUFFIX, image) }.getOrNull()
    if (mime == null || uri == null || !image.isFile) {
        Toast.makeText(this, R.string.clip_image_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val accepted = currentInputEditorInfo?.contentMimeTypes.orEmpty().any { ClipDescription.compareMimeTypes(mime, it) }
    val ic = inputConnection()
    val sent = accepted && ic != null && ic.commitContent(
        InputContentInfo(uri, ClipDescription(getString(R.string.clip_image_label), arrayOf(mime)), null),
        InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
    )
    if (!sent) shareImage(uri, mime)
}

/** The field can't take the picture: offer Android's share sheet for it instead. */
private fun ContinuumIME.shareImage(uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    val chooser = Intent.createChooser(send, getString(R.string.clip_image_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching { startActivity(chooser) }.isSuccess
    Toast.makeText(this, if (ok) R.string.clip_image_shared else R.string.clip_image_failed, Toast.LENGTH_SHORT).show()
}

internal fun ContinuumIME.handleClipRemove(index: Int) {
    if (clipboardBlocked() == 0) clipboard.remove(index)
    showClips()
}

internal fun ContinuumIME.handleClipClear() {
    if (clipboardBlocked() == 0) clipboard.clear()
    showClips()
}

// The clipboard and sticker panels: capturing, pasting and sending.
package com.brownllama.continuum

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import com.brownllama.continuum.clipboard.ClipboardHistory
import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.sticker.AddStickerActivity
import com.brownllama.continuum.sticker.Sticker
import com.brownllama.continuum.sticker.StickerStore

// ---- clipboard ------------------------------------------------------------------------

/**
 * Saves the system clipboard's text to the history. Skipped when the copying app marked it
 * sensitive (passwords, one-time codes; Android 13+ password managers do) and while a
 * password, number or incognito field has focus ([privateField]). Only plain text is kept: a
 * clip that is a link to another app's content is never opened.
 *
 * A clip stays on the system clipboard long after it was copied, and this is called again
 * later (a new field, the panel opening). So a clip copied in a private field ([fresh]: the
 * clipboard just changed) is set aside for good ([ClipboardHistory.skip]), not just for now.
 */
internal fun ContinuumIME.captureClip(fresh: Boolean = false) {
    if (!clipboardOn) return
    val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
    if (clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
    val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return
    val time = clip.description?.timestamp ?: 0L
    val hash = text.hashCode()
    if (privateField) {
        if (fresh) clipboard.skip(time, hash)
        return
    }
    // Copied anew outside a private field, the same text is wanted; seen again, it is still the private clip.
    if (!fresh && clipboard.isSkipped(time, hash)) return
    clipboard.add(text)
}

/** Why the clipboard panel has nothing to show (a string resource), or 0 if it may show the history. */
internal fun ContinuumIME.clipboardBlocked(): Int = when {
    !clipboardOn -> R.string.clip_off
    // Copied text is as private as what's on the phone: none of it while the phone is locked.
    runCatching { getSystemService(KeyguardManager::class.java)?.isDeviceLocked }.getOrNull() == true -> R.string.clip_locked
    else -> 0
}

internal fun ContinuumIME.showClips() {
    val blocked = clipboardBlocked()
    if (blocked != 0) view?.setClips(emptyList(), getString(blocked)) else view?.setClips(clipboard.texts())
}

internal fun ContinuumIME.handleClipboardOpen() {
    captureClip()
    showClips()
}

internal fun ContinuumIME.handleClipPicked(index: Int) {
    if (clipboardBlocked() != 0) return showClips()
    val text = clipboard.texts().getOrNull(index) ?: return
    val ic = editor() ?: return
    if (taps.isNotEmpty()) commitWord(spaced = false, choice = null)
    ic.commitText(text, 1)
    autoSpace = false
    lastCommit = null
    updateAutoShift()
    showPredictions()
}

internal fun ContinuumIME.handleClipRemove(index: Int) {
    if (clipboardBlocked() == 0) clipboard.remove(index)
    showClips()
}

internal fun ContinuumIME.handleClipClear() {
    if (clipboardBlocked() == 0) clipboard.clear()
    showClips()
}

/** Lists the stickers and hands the panel their thumbnails once they're decoded. */
internal fun ContinuumIME.loadStickers() {
    val list = stickerStore.list()
    stickers = list
    val px = (resources.displayMetrics.widthPixels / STICKER_THUMBS_ACROSS).coerceIn(64, StickerStore.SIZE)
    stickerStore.thumbnails(list, px) { images ->
        if (stickers === list) view?.setStickers(images, list.map { !it.builtIn })
    }
}

internal fun ContinuumIME.handleStickerPicked(index: Int) {
    stickers.getOrNull(index)?.let(::sendSticker)
}

internal fun ContinuumIME.handleStickerRemove(index: Int) {
    val s = stickers.getOrNull(index) ?: return
    stickerStore.remove(s) {
        Toast.makeText(this, R.string.sticker_removed, Toast.LENGTH_SHORT).show()
        if (view?.panel == KeyboardView.Panel.STICKERS) loadStickers()
    }
}

internal fun ContinuumIME.handleStickerAdd() {
    finishComposing()
    val intent = Intent(this, AddStickerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
    runCatching { startActivity(intent) }
}

/**
 * Sends a sticker the way messaging apps expect images from a keyboard: a content URI (our
 * FileProvider) through [InputConnection.commitContent], as WebP where the app takes it (WhatsApp
 * then shows it as a sticker) or else PNG. Apps that take no images get Android's share sheet,
 * so the sticker can still be sent from there.
 */
internal fun ContinuumIME.sendSticker(s: Sticker) {
    finishComposing()
    val info = currentInputEditorInfo
    val types = info?.contentMimeTypes.orEmpty()
    fun accepts(mime: String) = types.any { ClipDescription.compareMimeTypes(mime, it) }
    val webp = accepts("image/webp")
    val inline = webp || accepts("image/png")
    stickerStore.forSending(s, webp = webp || !inline) { file ->
        if (file == null) {
            Toast.makeText(this, R.string.sticker_send_failed, Toast.LENGTH_SHORT).show()
            return@forSending
        }
        val mime = if (file.name.endsWith(".webp")) "image/webp" else "image/png"
        val uri = FileProvider.getUriForFile(this, packageName + GifFeed.AUTHORITY_SUFFIX, file)
        val ic = inputConnection()
        val sent = inline && ic != null && currentInputEditorInfo === info &&
            ic.commitContent(
                InputContentInfo(uri, ClipDescription(s.name, arrayOf(mime)), null),
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
            )
        if (!sent) shareSticker(uri, mime)
    }
}

/** The app can't take the sticker inline: offer Android's share sheet for it instead. */
internal fun ContinuumIME.shareSticker(uri: android.net.Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    val chooser = Intent.createChooser(send, getString(R.string.sticker_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching { startActivity(chooser) }.isSuccess
    Toast.makeText(this, if (ok) R.string.sticker_shared else R.string.sticker_send_failed, Toast.LENGTH_SHORT).show()
}

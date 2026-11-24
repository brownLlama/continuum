// GIFs: picking one, and the search keyboard that edits the query.
package com.brownllama.continuum

import android.content.ClipDescription
import android.text.Editable
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.brownllama.continuum.gif.Gif
import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.gif.KlipyClient
import com.brownllama.continuum.keyboard.KeyboardView.Shift

internal fun ContinuumIME.handleGifPicked(index: Int) {
    gifs.gif(index)?.let(::sendGif)
}

internal fun ContinuumIME.handleGifSearchOpen() {
    if (queryEditor != null) return
    finishComposing()
    lastCommit = null
    fieldState = FieldState(predict, capsAllowed, learning, prevWord, textReadable)
    predict = true
    capsAllowed = false
    learning = false
    // The query is a text of its own: its words follow each other, not the app's last word.
    prevWord = null
    textReadable = null
    resetWordState()
    if (query.getSpans(0, query.length, TextWatcher::class.java).isEmpty()) {
        query.setSpan(queryWatcher, 0, query.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
    Selection.setSelection(query, query.length)
    queryEditor = object : BaseInputConnection(view!!, true) {
        override fun getEditable(): Editable = query
    }
    if (shift != Shift.LOCK) setShift(Shift.OFF)
    view?.emailField = false
    showPredictions()
}

internal fun ContinuumIME.handleGifSearchClose() {
    if (queryEditor == null) return
    finishComposing()
    queryEditor?.finishComposingText()
    main.removeCallbacks(liveSearch)
    queryEditor = null
    fieldState?.let {
        predict = it.predict
        capsAllowed = it.capsAllowed
        learning = it.learning
        prevWord = it.prevWord
        textReadable = it.textReadable
    }
    fieldState = null
    view?.emailField = emailField
    resetWordState()
    updateAutoShift()
    showPredictions()
}

/** Forgets the word being typed and what it was attached to (switching between the app and the query). */
internal fun ContinuumIME.resetWordState() {
    taps.clear()
    deletedTaps.clear()
    result = null
    lastCommit = null
    pendingGlue = null
    wordGlue = null
    deletedSlots = null
    autoSpace = false
}

internal fun ContinuumIME.deliverSendGif(gif: Gif) {
    // While searching, the word in progress belongs to the query; the app has none.
    if (queryEditor == null) finishComposing()
    val info = currentInputEditorInfo
    val acceptsGif = info?.contentMimeTypes?.any { ClipDescription.compareMimeTypes("image/gif", it) } == true
    // KlipyClient only lets https links through; checked again because this text goes into the app.
    if (!KlipyClient.isSafeUrl(gif.sendUrl)) return
    if (!acceptsGif) {
        val ic = inputConnection() ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        ic.commitText((if (before.isNotEmpty() && !before[0].isWhitespace()) " " else "") + gif.sendUrl, 1)
        autoSpace = false
        Toast.makeText(this, R.string.gif_link_pasted, Toast.LENGTH_SHORT).show()
        return
    }
    gifs.fetchForSending(gif) { file ->
        val ic = inputConnection()
        if (file == null || ic == null || currentInputEditorInfo !== info) {
            if (file == null) Toast.makeText(this, R.string.gif_send_failed, Toast.LENGTH_SHORT).show()
            return@fetchForSending
        }
        val uri = FileProvider.getUriForFile(this, packageName + GifFeed.AUTHORITY_SUFFIX, file)
        val content = InputContentInfo(uri, ClipDescription(gif.title, arrayOf("image/gif")), gif.sendUrl.toUri())
        if (!ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
            ic.commitText(gif.sendUrl, 1)
        }
    }
}

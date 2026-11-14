package com.brownllama.continuum.fixes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.brownllama.continuum.ContinuumIME
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.prediction.Tap
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The service with its editor connection pointed at the test's text field. */
class TestIme : ContinuumIME() {
    var ic: InputConnection? = null
    override fun inputConnection(): InputConnection? = ic
}

/** Drives the real keyboard service and its view against a real text field, as Android and a finger would. */
class Keyboard(prefs: Map<String, Any> = emptyMap()) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val edit = EditText(context)
    val ime: TestIme
    lateinit var view: KeyboardView

    init {
        val p = PreferenceManager.getDefaultSharedPreferences(context).edit()
        for ((k, v) in prefs) when (v) {
            is Boolean -> p.putBoolean(k, v)
            is String -> p.putString(k, v)
            is Int -> p.putInt(k, v)
        }
        p.putBoolean(Prefs.ANIMALS, false).putBoolean(Prefs.VIBRATE, false).commit()
        ime = Robolectric.buildService(TestIme::class.java).create().get()
        view = ime.onCreateInputView() as KeyboardView
        layoutView()
    }

    /** Sizes the view as the window would (its height follows the panel that shows). */
    fun layoutView() {
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), 0)
        view.layout(0, 0, 1080, view.measuredHeight)
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun waitReady() {
        val end = System.currentTimeMillis() + 60_000
        while (!ime.isReady && System.currentTimeMillis() < end) { idle(); Thread.sleep(20) }
        check(ime.isReady) { "dictionary did not load" }
    }

    fun info(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) = EditorInfo().apply {
        inputType = type; imeOptions = options; packageName = "test.app"
    }

    /** Focus a field of this kind and show the keyboard for it. */
    fun focus(info: EditorInfo = info()) {
        edit.setText("")
        ime.ic = edit.onCreateInputConnection(EditorInfo())
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        idle()
    }

    /** The field loses focus (keyboard hidden first). */
    fun blur() {
        ime.onFinishInputView(true)
        ime.onFinishInput()
        idle()
    }

    fun copy(text: String, time: Long? = null, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("", text)
        if (time != null) android.content.ClipDescription::class.java.getMethod("setTimestamp", Long::class.javaPrimitiveType).invoke(clip.description, time)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        idle()
    }

    val text: String get() = edit.text.toString()

    private val layout get() = KeyLayout.named(PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.LAYOUT, Prefs.LAYOUT_DEFAULT))
    private val full get() = PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.MODE, "mini") == "full"

    /** Taps the letters of [word], as full-mode key taps when [full] (password fields are always full mode). */
    fun type(word: String, full: Boolean = this.full) {
        for (ch in word) {
            val c = ch.lowercaseChar() - 'a'
            ime.onLetterTap(if (full) Tap(layout.fullX[c], layout.fullY[c]) else Tap(layout.miniSlot[c].toFloat(), 0f))
        }
    }

    /** A finger tap on the keyboard view at (x, y) in view pixels. */
    fun tap(x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(t, t + 30, android.view.MotionEvent.ACTION_UP, x, y, 0)
        view.onTouchEvent(down); view.onTouchEvent(up)
        down.recycle(); up.recycle()
        idle()
    }
    fun space() = ime.onSpace()
}

package com.brownllama.continuum.fixes

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import com.brownllama.continuum.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When the keyboard service learns how the user taps, and when it must not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TouchLearningTest {
    private val full = mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwerty")

    /** Types [words] in full mode, each tap 0.3 keys below its key, each word followed by a space. */
    private fun typeLow(k: Keyboard, vararg words: String) {
        for (w in words) {
            k.type(w, dy = 0.3f)
            k.space()
        }
    }

    @Test fun `kept words teach it where the taps land`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        repeat(15) { typeLow(k, "hello", "there", "people") }
        k.blur()
        val s = k.ime.touchShape()
        assertNotNull(s)
        assertTrue("e learned ${s!!.dy['e' - 'a']}", s.dy['e' - 'a'] > 0.15f)
    }

    @Test fun `a word counts only once the next word starts, and not if it was taken back`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        typeLow(k, "hello")
        assertNull("not yet: it may still be taken back", k.ime.touchShape())
        k.ime.onBackspace() // reopens "hello"
        k.ime.onDeleteWord() // and deletes it
        k.blur()
        assertNull(k.ime.touchShape())
        k.focus()
        typeLow(k, "hello", "there")
        // "hello" counts as soon as "there" is typed; "there" once the field is left.
        assertNotNull(k.ime.touchShape())
    }

    @Test fun `private fields teach it nothing`() {
        for (info in listOf(
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING },
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS },
        )) {
            val k = Keyboard(full)
            k.waitReady()
            k.focus(info.apply { packageName = "test.app" })
            repeat(5) { typeLow(k, "hello", "there") }
            k.blur()
            assertNull(k.ime.touchShape())
        }
    }

    @Test fun `switched off, it neither learns nor changes how taps are read`() {
        val k = Keyboard(full + (Prefs.TOUCH_ADAPT to false))
        k.waitReady()
        k.focus()
        repeat(15) { typeLow(k, "hello", "there", "people") }
        k.blur()
        assertNull(k.ime.touchShape())
    }

    @Test fun `forget learned words forgets how the user taps too`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        repeat(5) { typeLow(k, "hello", "there") }
        k.blur()
        assertNotNull(k.ime.touchShape())
        PreferenceManager.getDefaultSharedPreferences(k.context).edit().putLong(Prefs.CLEAR_LEARNED, 1L).commit()
        k.idle()
        assertNull(k.ime.touchShape())
    }

    @Test fun `what was learned survives a restart of the keyboard`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        repeat(15) { typeLow(k, "hello", "there", "people") }
        k.blur()
        val learned = k.ime.touchShape()!!.dy['e' - 'a']
        k.ime.onDestroy()
        Thread.sleep(300) // the background write
        val again = Keyboard(full)
        again.waitReady()
        again.focus()
        assertEquals(learned, again.ime.touchShape()!!.dy['e' - 'a'], 1e-5f)
    }

    @Test fun `ordinary typing still works with it on`() {
        val k = Keyboard(full)
        k.waitReady()
        k.focus()
        repeat(15) { typeLow(k, "hello", "there", "people") }
        k.ime.onEnter()
        // Typed on the centres now: still read right.
        k.type("hello"); k.space()
        k.type("people"); k.space()
        assertTrue(k.text.endsWith("hello people "))
    }
}

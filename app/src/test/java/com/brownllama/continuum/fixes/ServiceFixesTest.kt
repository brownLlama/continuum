package com.brownllama.continuum.fixes

import android.app.KeyguardManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The keyboard service, driven against a real text field: what the clipboard history keeps and
 * shows, fields that ask for no suggestions, and the GIFs being let go when the keyboard hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceFixesTest {
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val number = InputType.TYPE_CLASS_NUMBER

    // ---- clipboard privacy ----------------------------------------------------------------

    private fun privateCopyStaysOut(k: Keyboard, privateInfo: EditorInfo, time: (Int) -> Long?) {
        k.focus()
        k.copy("hello", time(1))
        assertEquals(listOf("hello"), k.ime.clipTexts)
        k.blur()
        k.focus(privateInfo)
        k.copy("hunter2", time(2))
        assertEquals(listOf("hello"), k.ime.clipTexts)
        k.blur()
        // Before the fix each of these picked the private clip up.
        k.focus()
        assertEquals("after the first normal field", listOf("hello"), k.ime.clipTexts)
        k.ime.onClipboardOpen()
        assertEquals("after opening the panel", listOf("hello"), k.ime.clipTexts)
        k.blur()
        k.focus()
        assertEquals("after the second normal field", listOf("hello"), k.ime.clipTexts)
        k.blur()
        k.focus()
        k.ime.onClipboardOpen()
        assertEquals(listOf("hello"), k.ime.clipTexts)
        // Copied again on purpose in an ordinary field, the same text is kept.
        k.copy("hunter2", time(3))
        assertEquals(listOf("hunter2", "hello"), k.ime.clipTexts)
    }

    @Test fun passwordCopyStaysOut_withTimestamps() = privateCopyStaysOut(Keyboard(), Keyboard().info(password)) { 1000L + it }
    @Test fun passwordCopyStaysOut_withoutTimestamps() = privateCopyStaysOut(Keyboard(), Keyboard().info(password)) { null }
    @Test fun numberFieldCopyStaysOut() = privateCopyStaysOut(Keyboard(), Keyboard().info(number)) { 1000L + it }
    @Test fun incognitoCopyStaysOut() = privateCopyStaysOut(
        Keyboard(), Keyboard().info(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
    ) { 1000L + it }

    @Test fun privateFieldAlreadyFocusedBeforeKeyboardShows() {
        val k = Keyboard()
        // The field has focus but the keyboard isn't showing (onStartInput only).
        k.ime.onStartInput(k.info(password), false)
        k.copy("s3cret", 5L)
        k.ime.onFinishInput()
        k.focus()
        k.ime.onClipboardOpen()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
    }

    @Test fun copyAfterLeavingAPrivateFieldIsKept() {
        val k = Keyboard()
        k.focus(k.info(password))
        k.blur()
        // Keyboard hidden, no field focused: an ordinary copy somewhere in an app.
        k.copy("an article", 9L)
        assertEquals(listOf("an article"), k.ime.clipTexts)
    }

    @Test fun copyMadeWhileKeyboardWasNotRunningIsPickedUp() {
        val k0 = Keyboard()
        k0.copy("early", 3L) // captured by this service's listener; clear so the next check means something
        k0.ime.onClipClear()
        k0.focus()
        assertEquals(listOf("early"), k0.ime.clipTexts)
    }

    @Test fun sensitiveClipIsNeverKept() {
        val k = Keyboard()
        k.focus()
        k.copy("otp 123456", 4L, sensitive = true)
        k.ime.onClipboardOpen()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
    }

    @Test fun aNormalFieldThenAPrivateFieldDoesNotMarkAnOrdinaryClip() {
        val k = Keyboard()
        k.focus()
        k.copy("shopping list", 11L)
        k.blur()
        k.focus(k.info(password))
        k.blur()
        k.focus()
        assertEquals(listOf("shopping list"), k.ime.clipTexts)
    }

    @Test fun clipboardOff_keepsNothing_andDeletesWhatWasKept() {
        val k = Keyboard()
        k.focus()
        k.copy("one", 1L)
        assertEquals(listOf("one"), k.ime.clipTexts)
        val prefs = PreferenceManager.getDefaultSharedPreferences(k.context)
        prefs.edit().putBoolean(Prefs.CLIPBOARD, false).commit()
        k.idle()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
        k.copy("two", 2L)
        k.ime.onClipboardOpen()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
        k.ime.onClipPicked(0)
        assertEquals("", k.text)
        prefs.edit().putBoolean(Prefs.CLIPBOARD, true).commit()
        k.idle()
        k.copy("three", 3L)
        assertEquals(listOf("three"), k.ime.clipTexts)
    }

    @Test fun clipboardOffAtStart() {
        val k = Keyboard(mapOf(Prefs.CLIPBOARD to false))
        k.focus()
        k.copy("x", 1L)
        k.ime.onClipboardOpen()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
    }

    @Test fun keepTimeSettingIsApplied() {
        val k = Keyboard(mapOf(Prefs.CLIPBOARD_KEEP to "1"))
        k.focus()
        k.copy("kept an hour", 1L)
        assertEquals(listOf("kept an hour"), k.ime.clipTexts)
        // Nonsense values fall back to a week rather than breaking.
        PreferenceManager.getDefaultSharedPreferences(k.context).edit().putString(Prefs.CLIPBOARD_KEEP, "banana").commit()
        k.idle()
        assertEquals(listOf("kept an hour"), k.ime.clipTexts)
    }

    @Test fun lockedPhone_showsAndPastesNothing() {
        val k = Keyboard()
        k.focus()
        k.copy("my address", 1L)
        val keyguard = shadowOf(k.context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        k.ime.onClipboardOpen()
        k.ime.onClipPicked(0)
        assertEquals("", k.text)
        k.ime.onClipRemove(0)
        k.ime.onClipClear()
        assertEquals("still kept for when it is unlocked", listOf("my address"), k.ime.clipTexts)
        keyguard.setIsDeviceLocked(false)
        k.ime.onClipboardOpen()
        k.ime.onClipPicked(0)
        assertEquals("my address", k.text)
        k.ime.onClipRemove(0)
        assertEquals(emptyList<String>(), k.ime.clipTexts)
    }

    @Test fun clipboardPanelStillWorksAsBefore() {
        val k = Keyboard()
        k.focus()
        k.copy("a", 1L); k.copy("b", 2L); k.copy("a", 3L)
        assertEquals(listOf("a", "b"), k.ime.clipTexts)
        k.view.showPanel(KeyboardView.Panel.CLIPBOARD)
        k.ime.onClipPicked(1)
        assertEquals("b", k.text)
        k.ime.onClipClear()
        assertEquals(emptyList<String>(), k.ime.clipTexts)
    }

    // ---- typing, learning, no-suggestions fields --------------------------------------------

    @Test fun miniTypingAndLearningStillWork() {
        val k = Keyboard()
        k.waitReady()
        k.focus()
        k.type("hello"); k.space()
        k.type("world"); k.space()
        assertEquals("hello world ", k.text)
        assertEquals(1, k.ime.learnedCount("hello"))
        assertEquals(1, k.ime.learnedCount("world"))
        // Backspace reopens the word and takes back what it taught.
        k.ime.onBackspace()
        assertEquals(0, k.ime.learnedCount("world"))
        k.space()
        assertEquals("hello world ", k.text)
        assertEquals(1, k.ime.learnedCount("world"))
    }

    @Test fun learnedWordsSurviveARestartOfTheService() {
        val k = Keyboard()
        k.waitReady()
        k.focus()
        repeat(2) { k.type("hello"); k.space() }
        k.blur()
        k.ime.onDestroy()
        val again = Keyboard()
        assertEquals(2, again.ime.learnedCount("hello"))
    }

    @Test fun fullModeAutocorrect_normalField_corrects() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.AUTOCORRECT to true, Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        k.type("teh"); k.space()
        assertEquals("the ", k.text)
    }

    @Test fun fullModeAutocorrect_noSuggestionsField_typesExactly_andLearnsNothing() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.AUTOCORRECT to true, Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus(k.info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        k.type("teh"); k.space()
        assertEquals("teh ", k.text)
        assertEquals(0, k.ime.learnedCount("teh"))
        k.type("hello"); k.space()
        assertEquals(0, k.ime.learnedCount("hello"))
        // The next ordinary field corrects and learns again.
        k.blur()
        k.focus()
        k.type("teh"); k.space()
        assertEquals("the ", k.text)
        assertEquals(1, k.ime.learnedCount("the"))
    }

    @Test fun passwordFieldTypesLettersAndLearnsNothing() {
        val k = Keyboard()
        k.waitReady()
        k.focus(k.info(password))
        k.type("hello", full = true)
        assertEquals("hello", k.text)
        assertEquals(0, k.ime.learnedCount("hello"))
    }

    // ---- GIFs ------------------------------------------------------------------------------

    private fun tiles(v: KeyboardView): List<*> =
        KeyboardView::class.java.getDeclaredField("gifTiles").apply { isAccessible = true }.get(v) as List<*>

    @Test fun hidingTheKeyboardLetsTheGifsGo() {
        val k = Keyboard()
        k.focus()
        k.view.showPanel(KeyboardView.Panel.GIF)
        k.view.setGifs(List(30) { KeyboardView.GifTile(1f) }, null, append = false)
        assertEquals(30, tiles(k.view).size)
        // Low memory alone leaves an open GIF panel alone, as before.
        k.ime.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(30, tiles(k.view).size)
        k.ime.onFinishInputView(false)
        k.ime.onWindowHidden()
        assertEquals(0, tiles(k.view).size)
        assertEquals(KeyboardView.Panel.LETTERS, k.view.panel)
        assertFalse(k.view.gifSearching)
        // Opening the panel again asks for GIFs anew (no key here, so it shows the hint instead).
        k.focus()
        k.view.showPanel(KeyboardView.Panel.GIF)
        assertEquals(KeyboardView.Panel.GIF, k.view.panel)
    }

    @Test fun hidingTheKeyboardWhileSearchingGifsClosesTheSearch() {
        val k = Keyboard()
        k.waitReady()
        k.focus()
        k.view.showPanel(KeyboardView.Panel.GIF)
        k.layoutView()
        // Tap the search field in the GIF panel's bar, as a finger would.
        k.tap(600f, k.view.overlayH + 8f)
        assertTrue(k.view.gifSearching)
        k.layoutView()
        k.type("cat")
        assertEquals("", k.text)
        assertTrue(k.ime.gifQuery.isNotEmpty())
        k.ime.onFinishInputView(false)
        k.ime.onWindowHidden()
        assertFalse(k.view.gifSearching)
        assertEquals(KeyboardView.Panel.LETTERS, k.view.panel)
        k.focus()
        k.type("hello"); k.space()
        assertEquals("hello ", k.text)
    }
}

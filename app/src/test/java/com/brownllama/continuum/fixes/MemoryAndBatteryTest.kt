package com.brownllama.continuum.fixes

import android.content.ComponentCallbacks2
import android.os.Looper
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.SAVE_DELAY_MS
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Less memory and fewer disk writes: the word list goes while the app is cached and comes back
 * with the next field, and words learned in a burst are written once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryAndBatteryTest {
    private fun keyboard() =
        Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to false)).also { it.waitReady() }

    private fun userFile(k: Keyboard) = File(k.context.filesDir, "user_model.tsv")

    /** Whether the saved words include [key] (the write happens on its own thread, so give it a moment). */
    private fun saved(k: Keyboard, key: String, wait: Boolean = true): Boolean {
        val end = System.currentTimeMillis() + if (wait) 5_000 else 300
        while (true) {
            val has = userFile(k).takeIf { it.exists() }?.readText()?.contains("u\t$key\t") == true
            if (has || System.currentTimeMillis() >= end) return has
            Thread.sleep(20)
        }
    }

    private fun Keyboard.passTime(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // ---- saving ------------------------------------------------------------------------------

    @Test fun `a new word is written a moment later, not at once`() {
        val k = keyboard()
        k.focus()
        k.type("zxqvk"); k.space()
        assertEquals(1, k.ime.learnedCount("zxqvk"))
        assertFalse("not written yet", saved(k, "zxqvk", wait = false))
        k.passTime(SAVE_DELAY_MS - 500)
        assertFalse("still inside the delay", saved(k, "zxqvk", wait = false))
        k.passTime(1_000)
        assertTrue("written once typing paused", saved(k, "zxqvk"))
    }

    @Test fun `new words typed in a burst are written together`() {
        val k = keyboard()
        k.focus()
        k.type("zxqvk"); k.space()
        k.passTime(SAVE_DELAY_MS - 500)
        // The next new word restarts the wait, so the first one is not written on its own.
        k.type("qjwxz"); k.space()
        k.passTime(SAVE_DELAY_MS - 500)
        assertFalse(saved(k, "zxqvk", wait = false))
        assertFalse(saved(k, "qjwxz", wait = false))
        k.passTime(1_000)
        assertTrue(saved(k, "zxqvk"))
        assertTrue(saved(k, "qjwxz"))
    }

    @Test fun `closing the keyboard writes at once`() {
        val k = keyboard()
        k.focus()
        k.type("zxqvk"); k.space()
        assertFalse(saved(k, "zxqvk", wait = false))
        k.blur()
        assertTrue(saved(k, "zxqvk"))
    }

    @Test fun `switching language writes the old language's words at once`() {
        val k = keyboard()
        k.focus()
        k.type("zxqvk"); k.space()
        assertFalse(saved(k, "zxqvk", wait = false))
        k.ime.onLanguageToggle()
        assertTrue(saved(k, "zxqvk"))
    }

    @Test fun `stopping the service writes at once too`() {
        val k = keyboard()
        k.focus()
        k.type("zxqvk"); k.space()
        assertFalse(saved(k, "zxqvk", wait = false))
        k.ime.onDestroy()
        assertTrue(saved(k, "zxqvk"))
    }

    @Test fun `a model without the hook still writes a new word at once`() {
        val dir = java.nio.file.Files.createTempDirectory("um").toFile()
        val file = File(dir, "user_model.tsv")
        UserModel(file).apply { learn(null, "brandnew", "brandnew"); flush() }
        assertEquals(1, UserModel(file).count("brandnew"))
        val later = UserModel(File(dir, "later.tsv"))
        var asked = 0
        later.onNewWord = { asked++ }
        later.learn(null, "brandnew", "brandnew")
        later.learn(null, "brandnew", "brandnew")
        assertEquals("asked once, for the new word only", 1, asked)
        assertFalse("nothing written until it is asked for", File(dir, "later.tsv").exists())
        later.flush()
        assertEquals(2, UserModel(File(dir, "later.tsv")).count("brandnew"))
        dir.deleteRecursively()
    }

    // ---- the word list ---------------------------------------------------------------------------

    @Test fun `the word list goes while the app is cached and is read again with the next field`() {
        val k = keyboard()
        k.focus()
        k.type("hel")
        assertTrue(k.bar.isNotEmpty())
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue("a field still has the keyboard", k.ime.isReady)
        k.ime.onFinishInputView(false)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertFalse("nothing is typed: let it go", k.ime.isReady)
        k.focus()
        k.waitReady()
        k.type("hel")
        assertTrue(k.bar.toString(), "hello" in k.bar || "help" in k.bar || "hell" in k.bar)
    }

    @Test fun `light trimming and a word in progress keep the word list`() {
        val k = keyboard()
        k.focus()
        k.ime.onFinishInputView(false)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(k.ime.isReady)
        k.focus()
        k.type("hel")
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertTrue(k.ime.isReady)
        assertTrue(k.bar.isNotEmpty())
    }

    @Test fun `trimming twice and opening twice reads the word list once`() {
        val k = keyboard()
        k.focus(); k.ime.onFinishInputView(false)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertFalse(k.ime.isReady)
        k.focus(); k.focus()
        assertEquals("one load under way", 1, k.ime.dictLoads)
        k.waitReady()
        k.idle()
        assertEquals(0, k.ime.dictLoads)
    }

    @Test fun `the German word list is brought back too`() {
        val k = keyboard()
        k.ime.onLanguageToggle(); k.waitReady()
        k.focus(); k.ime.onFinishInputView(false)
        k.ime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertFalse(k.ime.isReady)
        k.focus(); k.waitReady()
        k.type("hall")
        assertTrue(k.bar.toString(), k.bar.isNotEmpty())
    }
}

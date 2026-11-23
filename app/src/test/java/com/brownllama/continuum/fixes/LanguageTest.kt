package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.KeyLayout
import com.brownllama.continuum.prediction.Decoder
import com.brownllama.continuum.prediction.Dictionary
import com.brownllama.continuum.prediction.Language
import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.prediction.Tap
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * English and German: each has its own word list and its own learned words (what is typed in one is
 * never learned in, or offered by, the other), and a swipe up and left switches between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanguageTest {
    private val layout = KeyLayout.QWERTZ

    private fun mini(word: String) = word.map { Tap(layout.miniSlot[it - 'a'].toFloat(), 0f) }
    private fun full(word: String) = word.map { Tap(layout.fullX[it - 'a'], layout.fullY[it - 'a']) }
    private fun user() = UserModel(File.createTempFile("user", ".tsv").also { it.delete() })

    @Test fun `the German word list is whole and spells German words with their own letters`() {
        assertTrue(GERMAN.size > 40_000)
        assertEquals(Language.DE, GERMAN.language)
        for (w in listOf("ich", "und", "nicht", "für", "über", "können", "schön", "weiß")) {
            val key = Dictionary.keyOf(w)!!
            assertTrue(w, GERMAN.contains(key))
        }
        assertEquals("für", GERMAN.displayFor("fur"))
        assertEquals("über", GERMAN.displayFor("uber"))
        assertEquals("weiß", GERMAN.displayFor("weiss"))
        // Nothing English in it: no contractions, no capital "I".
        assertFalse(GERMAN.contains("dont"))
        assertEquals("i", GERMAN.displayFor("i").lowercase())
    }

    @Test fun `German words are found from their plain letters, in both modes`() {
        val d = Decoder(GERMAN, user())
        for (w in listOf("fur", "uber", "konnen", "nicht", "und", "ich", "wissen", "zuruck")) {
            assertEquals("$w mini", w, Dictionary.keyOf(d.decode(mini(w), Mode.MINI, layout, null).words.first().display))
            assertEquals("$w full", w, Dictionary.keyOf(d.decode(full(w), Mode.FULL, layout, null).words.first().display))
        }
        assertEquals("für", d.decode(full("fur"), Mode.FULL, layout, null).words.first().display)
        assertEquals("über", d.decode(mini("uber"), Mode.MINI, layout, null).words.first().display)
    }

    @Test fun `the bar offers German words in German and English ones in English`() {
        val de = Decoder(GERMAN, user())
        val en = Decoder(ENGLISH, user())
        val german = de.predict(null, sentenceStart = true, limit = 5)
        val english = en.predict(null, sentenceStart = true, limit = 5)
        assertEquals(5, german.size)
        assertEquals(5, english.size)
        assertTrue(german.toString(), german.all { GERMAN.contains(Dictionary.keyOf(it)!!) })
        assertTrue(english.toString(), english.all { ENGLISH.contains(Dictionary.keyOf(it)!!) })
        assertFalse(german == english)
        assertEquals("the", en.predict("of", sentenceStart = false, limit = 3).first().lowercase())
        assertTrue(de.predict("in", sentenceStart = false, limit = 3).all { GERMAN.contains(Dictionary.keyOf(it)!!) })
    }

    @Test fun `the German next-word model file is sound and reads as German`() {
        val rows = File(ASSETS, Language.DE.nextAsset).inputStream().use(Dictionary::readNext)
        assertTrue("rows: ${rows.size}", rows.size > 10_000)
        assertTrue("<s>" in rows)
        for ((prev, row) in rows) {
            assertTrue(prev, prev == "<s>" || GERMAN.contains(prev))
            assertTrue(prev, row.backoff <= 0f)
            for ((next, lift) in row.next) {
                assertTrue("$prev $next", GERMAN.contains(next))
                assertTrue("$prev $next", lift > 0f && lift < 16f)
            }
        }
        // What follows "ich" and "ist" is what a German speaker expects.
        assertTrue(rows.getValue("ich").next.any { it.first == "habe" })
        assertTrue(rows.getValue("ich").next.any { it.first == "bin" })
        assertTrue(rows.getValue("ist").next.any { it.first == "nicht" })
        assertTrue(rows.getValue("<s>").next.any { it.first == "ich" })
    }

    @Test fun `German words come with their emoji`() {
        fun emojiFor(d: Dictionary, w: String) = d.indexOf(Dictionary.keyOf(w)!!).let { if (it >= 0) d.emojiFor(it) else null }
        assertEquals("👋", emojiFor(GERMAN, "hallo"))
        assertEquals("❤️", emojiFor(GERMAN, "herz"))
        assertEquals("🙏", emojiFor(GERMAN, "danke"))
        assertEquals("🎂", emojiFor(GERMAN, "geburtstag"))
        assertEquals("🍕", emojiFor(GERMAN, "pizza"))
        // And English ones still have theirs, and don't leak into German.
        assertEquals("👋", emojiFor(ENGLISH, "hi"))
        assertEquals(null, emojiFor(GERMAN, "hello"))
    }

    @Test fun `a swipe up and left switches the language, and the same swipe goes back`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(k.context)
        assertEquals("en", prefs.getString(Prefs.LANGUAGE, "en"))
        k.focus()
        k.swipeUpLeft()
        assertEquals("de", prefs.getString(Prefs.LANGUAGE, null))
        k.waitReady()
        k.swipeUpLeft()
        assertEquals("en", prefs.getString(Prefs.LANGUAGE, null))
        // Up and right is enter, not a language switch.
        k.waitReady()
        k.swipeUpRight()
        assertEquals("en", prefs.getString(Prefs.LANGUAGE, null))
        // A swipe that is not diagonal doesn't switch it either.
        k.swipe(-140f, 0f)
        k.swipe(0f, -140f)
        assertEquals("en", prefs.getString(Prefs.LANGUAGE, null))
    }

    /** A swipe at [degrees] above flat towards the left (or right), 160 px long. */
    private fun Keyboard.swipeAt(degrees: Double, left: Boolean = true) {
        val r = Math.toRadians(degrees)
        swipe((if (left) -1 else 1) * (160 * Math.cos(r)).toFloat(), (-160 * Math.sin(r)).toFloat())
    }

    @Test fun `a swipe up and left in a wide sector switches the language`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(k.context)
        k.focus()
        var expected = "en"
        // From a flat-ish diagonal to a steep one: every one switches (and the next switches back).
        for (degrees in listOf(33.0, 38.0, 45.0, 52.0, 60.0, 66.0)) {
            expected = if (expected == "en") "de" else "en"
            k.swipeAt(degrees)
            assertEquals("$degrees degrees", expected, prefs.getString(Prefs.LANGUAGE, null))
            k.waitReady()
        }
    }

    @Test fun `flat, steep, rightward and downward swipes don't switch the language`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(k.context)
        k.focus(text = "one two ")
        // Flat left: delete a word, as before.
        k.swipeAt(8.0)
        assertEquals("one ", k.text)
        k.swipeAt(20.0)
        assertEquals("", k.text)
        for (degrees in listOf(72.0, 80.0, 89.0)) { k.swipeAt(degrees); k.focus(text = "") } // steep: full keyboard
        k.swipeAt(45.0, left = false)                  // up and right: enter
        k.swipe(-140f, 140f)                           // down and left
        k.swipe(140f, 140f)
        k.swipe(0f, 140f)
        assertEquals("en", prefs.getString(Prefs.LANGUAGE, "en"))
    }

    @Test fun `a language change shows a hint, and the label follows the language`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        k.focus()
        assertEquals("en", k.view.languageCode)
        assertFalse(k.view.languageHintShowing)
        k.swipeUpLeft()
        assertEquals("de", k.view.languageCode)
        assertTrue(k.view.languageHintShowing)
        // It draws (the pill and the label), in mini mode and in full mode.
        fun draw() = k.view.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1080, 600, android.graphics.Bitmap.Config.ARGB_8888)))
        k.layoutView(); draw()
        // And is gone after a moment.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600))
        assertFalse(k.view.languageHintShowing)
        draw()
        k.waitReady()
        k.swipeUpLeft()
        assertEquals("en", k.view.languageCode)
        assertTrue(k.view.languageHintShowing)
        k.ime.onModeToggle(); k.idle(); k.layoutView(); draw()
    }

    @Test fun `words typed in one language are learned there only`() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        // English: "zorbix" twice.
        repeat(2) { k.focus(text = "ok "); k.type("zorbix"); k.space() }
        k.ime.onLanguageToggle(); k.waitReady()
        // German: "blorpf" twice, and "zorbix" has never been seen here.
        repeat(2) { k.focus(text = "ok "); k.type("blorpf"); k.space() }
        k.focus(text = "ok "); k.type("zorbi")
        assertFalse(k.bar.toString(), k.bar.any { it.startsWith("zorbix") })
        k.focus(text = "ok "); k.type("blorp")
        assertTrue(k.bar.toString(), k.bar.any { it.startsWith("blorpf") })
        k.ime.onLanguageToggle(); k.waitReady()
        // Back in English: zorbix is known, blorpf is not.
        k.focus(text = "ok "); k.type("zorbi")
        assertTrue(k.bar.toString(), k.bar.any { it.startsWith("zorbix") })
        k.focus(text = "ok "); k.type("blorp")
        assertFalse(k.bar.toString(), k.bar.any { it.startsWith("blorpf") })
        k.blur()
        k.ime.onDestroy()
        val dir = k.context.filesDir
        assertEquals(2, UserModel(File(dir, Language.EN.userFile)).count("zorbix"))
        assertEquals(0, UserModel(File(dir, Language.EN.userFile)).count("blorpf"))
        assertEquals(2, UserModel(File(dir, Language.DE.userFile)).count("blorpf"))
        assertEquals(0, UserModel(File(dir, Language.DE.userFile)).count("zorbix"))
    }

    @Test fun `typing German in German mini mode gives German words with their umlauts`() {
        val k = Keyboard(mapOf(Prefs.MODE to "mini", Prefs.LAYOUT to "qwertz"))
        k.waitReady()
        k.ime.onLanguageToggle(); k.waitReady()
        k.focus(text = "")
        k.type("uber"); k.space()
        assertEquals("über ", k.text)
        k.focus(text = "ich ")
        k.type("bin"); k.space()
        assertEquals("ich bin ", k.text)
    }

    @Test fun `English only features stay out of German`() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwertz", Prefs.AUTOCORRECT to true))
        k.waitReady()
        // English: a lone i is a capital I and "im" becomes I'm.
        k.focus(text = "so "); k.type("im"); k.space()
        assertEquals("so I'm ", k.text)
        k.ime.onLanguageToggle(); k.waitReady()
        // German: "im" is a word of its own, and nothing is turned into I'm.
        k.focus(text = "so "); k.type("im"); k.space()
        assertEquals("so im ", k.text)
    }

    private companion object {
        val ASSETS = File("src/main/assets")
        fun load(language: Language): Dictionary {
            val wrong = language.misspellingsAsset?.let { File(ASSETS, it).inputStream().use(Dictionary::readMisspellings) }.orEmpty()
            val emoji = language.emojiAsset?.let { File(ASSETS, it).inputStream().use(Dictionary::readEmoji) }.orEmpty()
            val next = File(ASSETS, language.nextAsset).takeIf { it.exists() }?.inputStream()?.use(Dictionary::readNext).orEmpty()
            return File(ASSETS, language.wordsAsset).inputStream().use { Dictionary.loadOpen(it, wrong, emoji, next, language) }
                .also { it.language = language }
        }
        val ENGLISH: Dictionary = load(Language.EN)
        val GERMAN: Dictionary = load(Language.DE)
    }
}

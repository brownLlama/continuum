package com.brownllama.continuum.fixes

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.AccessNode
import com.brownllama.continuum.keyboard.EmojiGroup
import com.brownllama.continuum.keyboard.KeyboardView
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import com.brownllama.continuum.prediction.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the keyboard offers a screen reader (its named, clickable nodes) in each mode and panel,
 * and that every mode and panel still draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenReaderTest {
    @Suppress("UNCHECKED_CAST")
    private fun nodes(v: KeyboardView): List<AccessNode> =
        KeyboardView::class.java.getDeclaredMethod("accessNodes").apply { isAccessible = true }.invoke(v) as List<AccessNode>

    private fun checkSane(k: Keyboard, what: String): List<AccessNode> {
        k.layoutView()
        val list = nodes(k.view)
        assertTrue("$what: has nodes", list.isNotEmpty())
        for (n in list) {
            assertTrue("$what: '${n.label}' has a name", n.label.isNotBlank())
            assertFalse("$what: '${n.label}' has bounds", n.bounds.isEmpty)
            assertTrue("$what: '${n.label}' ${n.bounds} is inside the view", n.bounds.left >= 0 && n.bounds.right <= k.view.width &&
                n.bounds.top >= 0 && n.bounds.bottom <= k.view.height)
        }
        assertEquals("$what: no two nodes in the same place", list.size, list.map { it.bounds.toString() }.toSet().size)
        return list
    }

    private fun AccessNode.at(list: List<AccessNode>) = list.indexOf(this)
    private fun List<AccessNode>.named(label: String) = single { it.label == label }

    private fun draw(k: Keyboard) {
        k.layoutView()
        val bmp = Bitmap.createBitmap(k.view.width, k.view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        k.view.draw(Canvas(bmp))
    }

    @Test fun fullKeyboard_everyKeyIsNamed_andLettersTypeExactly() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwerty", Prefs.AUTOCORRECT to true))
        k.waitReady()
        k.focus()
        val list = checkSane(k, "full letters")
        val letters = list.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }
        assertEquals(26, letters.size)
        // "teh" would be autocorrected from ordinary taps; picked key by key it is typed as it stands.
        for (c in "teh") list.named(c.toString()).activate()
        // The key list is rebuilt after each press (shift state, suggestions), so look the space key up afresh.
        nodes(k.view).single { it.bounds.width() > k.view.width / 3 && it.bounds.top > k.view.height / 2 }.activate()
        assertEquals("teh ", k.text)
    }

    @Test fun fullKeyboard_shiftDeleteAndSymbolsWork() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        val before = nodes(k.view).map { it.label }
        assertTrue(before.contains("a"))
        // Keys with icons are found by their place, not their (translatable) names: shift is left of the bottom letter row.
        val z = nodes(k.view).named("z")
        val shift = nodes(k.view).single { it.bounds.top == z.bounds.top && it.bounds.right <= z.bounds.left && it.bounds.left == 0 }
        shift.activate()
        assertEquals(KeyboardView.Shift.ON, k.view.shift)
        assertTrue(nodes(k.view).map { it.label }.contains("A"))
        nodes(k.view).named("H").activate()
        nodes(k.view).named("i").activate()
        assertEquals("Hi", k.text)
        val backspace = nodes(k.view).single { it.bounds.top == z.bounds.top && it.bounds.right == k.view.width }
        backspace.activate()
        assertEquals("H", k.text)
        // The 123 key: bottom-left.
        val bottom = nodes(k.view).maxOf { it.bounds.top }
        nodes(k.view).single { it.bounds.top == bottom && it.bounds.left == 0 }.activate()
        assertEquals(Panel.SYMBOLS, k.view.panel)
        val symbols = checkSane(k, "symbols")
        assertTrue(symbols.any { it.label == "7" })
    }

    @Test fun miniKeyboard_lettersAndSymbolsAreNamed() {
        val k = Keyboard(mapOf(Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        assertEquals(Mode.MINI, k.view.mode)
        val list = checkSane(k, "mini")
        assertEquals(26, list.count { it.label.length == 1 && it.label[0] in 'a'..'z' })
        // A typed letter may also show as a suggestion; the letter itself is the lower of the two.
        for (c in "zzyzx") nodes(k.view).filter { it.label == c.toString() }.maxByOrNull { it.bounds.top }!!.activate()
        k.space()
        assertEquals("zzyzx ", k.text)
        nodes(k.view).named("@").activate()
        assertEquals("zzyzx @", k.text)
    }

    @Test fun suggestionsAreNodes_andPickingOneCommitsIt() {
        val k = Keyboard(mapOf(Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        k.type("hello")
        val slots = k.ime.suggestionSlots.filter { it.isNotEmpty() }
        val list = checkSane(k, "suggestions")
        assertTrue(slots.isNotEmpty())
        val firstShown = list.first { it.label in slots }
        firstShown.activate()
        assertEquals(firstShown.label + " ", k.text)
    }

    @Test fun panels_emojiClipboardGif() {
        val k = Keyboard()
        k.waitReady()
        k.focus()
        k.copy("first clip", 1L)
        k.copy("second clip", 2L)
        k.view.emojiGroups = listOf(EmojiGroup("smileys", List(40) { "😀" + ('a' + it % 26) }), EmojiGroup("food", listOf("🍕", "🍔")))
        k.view.showPanel(Panel.EMOJI)
        val emoji = checkSane(k, "emoji")
        // The grid opens on "frequently used"; only the rows in view are offered.
        val frequent = com.brownllama.continuum.keyboard.Emoji.DEFAULT_FREQUENT
        val shown = emoji.filter { it.label in frequent }
        assertTrue(shown.isNotEmpty() && shown.size < frequent.size)
        shown.first().activate()
        assertEquals(shown.first().label, k.text)
        draw(k)

        k.view.showPanel(Panel.CLIPBOARD)
        val clips = checkSane(k, "clipboard")
        assertTrue(clips.any { it.label == "second clip" })
        clips.named("first clip").activate()
        assertTrue(k.text.endsWith("first clip"))
        draw(k)

        k.view.showPanel(Panel.GIF)
        k.view.setGifs(List(30) { KeyboardView.GifTile(1f) }, null, append = false)
        checkSane(k, "gif grid")
        draw(k)
        k.layoutView()
        k.tap(600f, k.view.overlayH + 8f)
        assertTrue(k.view.gifSearching)
        checkSane(k, "gif search")
        draw(k)
    }

    @Test fun nodeProvider_reportsTheSameNodes_andClicks() {
        val k = Keyboard(mapOf(Prefs.MODE to "full", Prefs.LAYOUT to "qwerty"))
        k.waitReady()
        k.focus()
        k.layoutView()
        val am = k.context.getSystemService(AccessibilityManager::class.java)
        shadowOf(am).setEnabled(true)
        shadowOf(am).setTouchExplorationEnabled(true)
        // What a screen reader itself asks: the view's node provider.
        val provider = k.view.accessibilityNodeProvider
        assertNotNull(provider)
        val host = provider!!.createAccessibilityNodeInfo(-1)
        assertNotNull(host)
        val list = nodes(k.view)
        assertEquals(list.size, host!!.childCount)
        for (i in list.indices) {
            val info = provider.createAccessibilityNodeInfo(i)!!
            assertEquals(list[i].label, info.contentDescription.toString())
            assertTrue(info.isClickable)
        }
        // An id from an older, longer list still answers instead of throwing.
        assertNotNull(provider.createAccessibilityNodeInfo(list.size + 50))
        assertFalse(provider.performAction(list.size + 50, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        val q = list.named("q").at(list)
        assertTrue(provider.performAction(q, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        assertEquals("q", k.text)
        // Hovering (touch exploration) and plain touches both still work.
        val t = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_ENTER, 500f, k.view.height - 20f, 0)
        hover.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        k.view.dispatchGenericMotionEvent(hover)
        hover.recycle()
        k.idle()
    }

    @Test fun hoverWithoutAScreenReaderDoesNothing() {
        val k = Keyboard()
        k.focus()
        k.layoutView()
        val t = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 500f, k.view.height - 20f, 0)
        hover.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        assertFalse(k.view.dispatchGenericMotionEvent(hover))
        hover.recycle()
        assertEquals("", k.text)
    }

    @Test fun touchExplorationOpensTheFullKeyboard_otherwiseTheChosenMode() {
        val k = Keyboard()
        k.focus()
        assertEquals(Mode.MINI, k.view.mode)
        k.blur()
        val am = k.context.getSystemService(AccessibilityManager::class.java)
        shadowOf(am).setEnabled(true)
        shadowOf(am).setTouchExplorationEnabled(true)
        k.focus()
        assertEquals(Mode.FULL, k.view.mode)
        k.blur()
        shadowOf(am).setTouchExplorationEnabled(false)
        k.focus()
        assertEquals(Mode.MINI, k.view.mode)
    }

    @Test fun drawing_everyModeAndPanel_doesNotThrow() {
        for (mode in listOf("mini", "full")) {
            val k = Keyboard(mapOf(Prefs.MODE to mode))
            k.waitReady()
            k.focus()
            draw(k)
            k.type("hel", full = mode == "full")
            draw(k)
            k.view.showPanel(Panel.SYMBOLS); draw(k)
            if (mode == "full") { k.view.showPanel(Panel.SYMBOLS2); draw(k) }
            k.view.showPanel(Panel.CLIPBOARD); draw(k)              // empty: the usual hint
            val keyguard = shadowOf(k.context.getSystemService(KeyguardManager::class.java))
            k.copy("a clip", 1L)
            keyguard.setIsDeviceLocked(true)
            k.view.showPanel(Panel.LETTERS); k.view.showPanel(Panel.CLIPBOARD); draw(k)   // locked message
            assertTrue(nodes(k.view).none { it.label == "a clip" })
            keyguard.setIsDeviceLocked(false)
            k.view.showPanel(Panel.LETTERS); k.view.showPanel(Panel.CLIPBOARD); draw(k)   // the clip
            assertTrue(nodes(k.view).any { it.label == "a clip" })
            k.view.showPanel(Panel.GIF); draw(k)                    // "add a key" message
            k.view.showPanel(Panel.EMOJI); draw(k)
            k.view.showPanel(Panel.LETTERS); draw(k)
        }
    }
}

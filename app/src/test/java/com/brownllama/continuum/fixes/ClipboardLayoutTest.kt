package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brownllama.continuum.keyboard.KeyboardView.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The clipboard panel's cards, Pinterest-style: as tall as what they hold, in two columns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring, for the cards' heights
class ClipboardLayoutTest {
    private fun clipboard(): Keyboard = Keyboard().apply {
        focus()
        view.showPanel(Panel.CLIPBOARD)
        layoutView()
    }

    @Test fun `the clipboard panel is taller than the emoji panel, but not over half the screen`() {
        val k = Keyboard()
        k.focus()
        k.view.showPanel(Panel.EMOJI)
        val emoji = k.view.keyboardHeight
        k.view.showPanel(Panel.CLIPBOARD)
        k.layoutView()
        assertTrue(k.view.keyboardHeight > emoji)
        assertTrue(k.view.keyboardHeight < k.context.resources.displayMetrics.heightPixels * 0.5f)
        assertEquals("the view is measured to fit it", true, k.view.height >= k.view.keyboardHeight)
    }

    @Test fun `a card is as tall as its whole text`() {
        val k = clipboard()
        val long = "word ".repeat(80).trim()
        k.view.setClips(listOf("short", long))
        val (short, tall) = k.view.clipCards
        assertTrue("the long text's card is taller", tall.height() > short.height() * 2)
        val layout = k.view.clipLayouts.getValue(long)
        assertTrue("more than the three lines cards had before", layout.lineCount > 3)
        assertEquals("nothing is cut off", 0, layout.getEllipsisCount(layout.lineCount - 1))
        assertTrue("a long text's card may be taller than the panel shows at once", tall.height() > k.view.clipMaxCardH)
    }

    @Test fun `only a very long text is cut off, at three panels' height`() {
        val k = clipboard()
        val huge = "word ".repeat(2000)
        k.view.setClips(listOf(huge))
        val card = k.view.clipCards[0]
        assertEquals(k.view.clipMaxTextH, card.height(), 0.5f)
        val layout = k.view.clipLayouts.getValue(huge)
        assertTrue(layout.getEllipsisCount(layout.lineCount - 1) > 0)
    }

    @Test fun `each card goes under the shorter column`() {
        val k = clipboard()
        k.view.setClips(listOf("word ".repeat(40), "a", "b", "c"))
        val cards = k.view.clipCards
        val left = cards[0].left
        val right = cards[1].left
        assertTrue(right > left)
        assertEquals("the next short card goes under the first short one, not the tall one", right, cards[2].left)
        assertTrue(cards[2].top > cards[1].bottom)
    }

    @Test fun `a picture card has the picture's shape, and none is taller than the panel shows`() {
        val k = clipboard()
        k.view.setClips(listOf(null, null, null), aspects = listOf(1.2f, 0.5f, 20f))
        val (tall, wide, huge) = k.view.clipCards
        assertTrue(tall.width() * 1.2f < k.view.clipMaxCardH)
        assertEquals(tall.width() * 1.2f, tall.height(), 1f)
        assertEquals(wide.width() * 0.5f, wide.height(), 1f)
        assertEquals(k.view.clipMaxCardH, huge.height(), 0.5f)
        assertTrue(huge.height() <= k.view.clipRect.height())
    }

    @Test fun `a picture whose size wasn't kept takes its thumbnail's shape once that is decoded`() {
        val k = clipboard()
        k.view.setClips(listOf(null), images = listOf(null))
        val square = k.view.clipCards[0]
        assertEquals(square.width(), square.height(), 1f)
        k.view.setClipImages(listOf(Bitmap.createBitmap(100, 110, Bitmap.Config.ARGB_8888)))
        val card = k.view.clipCards[0]
        assertEquals(card.width() * 1.1f, card.height(), 1f)
        // And it draws.
        k.view.draw(Canvas(Bitmap.createBitmap(k.view.width, k.view.height, Bitmap.Config.ARGB_8888)))
        assertFalse(k.view.clipCards.isEmpty())
    }
}

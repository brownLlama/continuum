package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.preference.PreferenceManager
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.keyboard.ANIMAL_FEET_ROWS
import com.brownllama.continuum.keyboard.animalFeet
import com.brownllama.continuum.keyboard.drawGrass
import com.brownllama.continuum.keyboard.grassBottom
import com.brownllama.continuum.keyboard.grassHeight
import com.brownllama.continuum.keyboard.grassTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Pixel grass grows from the bottom of the keyboard's window (the bottom of the screen) up to the
 * keys, through the space under them (0–30 dp, 15 by default): a higher value is taller grass, 0 is
 * none. The visiting animals walk on it. It only decorates: a tap on it still reaches the keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceBelowTest {
    private fun keyboard(prefs: Map<String, Any> = emptyMap()) = Keyboard(prefs).also { it.waitReady(); it.focus() }

    private fun Keyboard.px(dp: Int) = dp * context.resources.displayMetrics.density

    /** Draws the grass as a frame would, so its bitmap is built. */
    private fun Keyboard.paintGrass() = view.drawGrass(Canvas(Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)))

    @Test fun `the space below the keyboard is 15 dp by default and at most 30`() {
        assertEquals(15, Prefs.BOTTOM_SPACE_DEFAULT)
        val plain = keyboard()
        assertEquals(15f, plain.view.bottomSpaceDp, 0f)
        val saved = keyboard(mapOf(Prefs.BOTTOM_SPACE to 60))
        assertEquals("a 60 saved by an older version is cut back to the slider's top", 30f, saved.view.bottomSpaceDp, 0f)
    }

    @Test fun `the grass grows from the bottom of the window up to the keys`() {
        val k = keyboard()
        val v = k.view
        assertEquals("it reaches the bottom of the keys", v.keyboardHeight, v.grassTop, 0f)
        assertEquals("it stands on the window's last pixel", v.height.toFloat(), v.overlayH + v.gifHeaderH + v.grassBottom, 0.01f)
        assertEquals((v.grassBottom - v.grassTop).roundToInt(), v.grassHeight)
        // Here Android has no bar under the keys, so the grass is just the space above it.
        assertEquals(k.px(15), v.grassHeight.toFloat(), 1f)
        k.paintGrass()
        assertEquals("drawn across the whole width", 1080, v.grass.imageWidth)
        assertEquals(v.grassHeight, v.grass.imageHeight)
    }

    @Test fun `a higher value is taller grass, and 0 is none`() {
        val tall = keyboard(mapOf(Prefs.BOTTOM_SPACE to 30))
        assertEquals(tall.px(30), tall.view.grassHeight.toFloat(), 1f)
        val none = keyboard(mapOf(Prefs.BOTTOM_SPACE to 0))
        assertEquals(0, none.view.grassHeight)
        none.paintGrass()
        assertEquals("no grass is drawn at 0", 0, none.view.grass.imageHeight)
    }

    @Test fun `moving the slider changes the grass at once`() {
        val k = keyboard()
        k.paintGrass()
        PreferenceManager.getDefaultSharedPreferences(k.context).edit().putInt(Prefs.BOTTOM_SPACE, 25).commit()
        k.idle()
        assertEquals(25f, k.view.bottomSpaceDp, 0f)
        k.layoutView()
        assertEquals(k.px(25), k.view.grassHeight.toFloat(), 1f)
        k.paintGrass()
        assertEquals("redrawn at the new height", k.view.grassHeight, k.view.grass.imageHeight)
    }

    @Test fun `the animals walk on the grass, a few pixels above its bottom`() {
        val v = keyboard().view
        val feet = v.animalFeet()
        assertEquals(v.grassBottom - ANIMAL_FEET_ROWS * v.spritePx, feet, 0.01f)
        assertTrue("their feet are in the grass", feet > v.grassTop && feet < v.grassBottom)
        // On the grass's pixel grid, counted from the bottom of the window.
        val fromBottom = v.height - (v.overlayH + v.gifHeaderH + feet)
        assertEquals(0f, fromBottom % v.spritePx, 0.01f)
    }

    @Test fun `without grass the animals walk along the letter row`() {
        val v = keyboard(mapOf(Prefs.BOTTOM_SPACE to 0)).view
        assertEquals(v.letterRect.bottom - v.dp(4f), v.animalFeet(), 0.01f)
    }

    @Test fun `a tap on the grass still reaches the keys above it`() {
        val k = keyboard(mapOf(Prefs.MODE to "mini"))
        k.layoutView()
        // The middle of the grass, in view pixels (the view starts with the popups' transparent strip).
        k.tap(540f, k.view.overlayH + k.view.grassTop + k.view.grassHeight / 2f)
        assertEquals("mini mode: the grass counts as the letter row", 1, k.text.length)
    }
}

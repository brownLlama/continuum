package com.brownllama.continuum.fixes

import com.brownllama.continuum.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The space under the keys is plain space again (no grass); a value saved while it went up to 60 is kept within the slider's range. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceBelowTest {
    @Test fun `the space below the keyboard is 15 dp by default and at most 30`() {
        assertEquals(15, Prefs.BOTTOM_SPACE_DEFAULT)
        val plain = Keyboard().also { it.waitReady(); it.focus() }
        assertEquals(15f, plain.view.bottomSpaceDp, 0f)
        val saved = Keyboard(mapOf(Prefs.BOTTOM_SPACE to 60)).also { it.waitReady(); it.focus() }
        assertEquals("a 60 saved by the grass version is cut back to the slider's top", 30f, saved.view.bottomSpaceDp, 0f)
    }
}

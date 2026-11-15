package com.brownllama.continuum.fixes

import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.brownllama.continuum.Prefs
import com.brownllama.continuum.R
import com.brownllama.continuum.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every settings page opens, and the clipboard settings are there with their defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsPagesTest {
    private fun page(activity: SettingsActivity, name: String): PreferenceFragmentCompat {
        val f = SettingsActivity.ScreenFragment.of(name)
        activity.supportFragmentManager.beginTransaction().replace(R.id.prefs, f).commitNow()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return f
    }

    @Test fun everySettingsPageOpens() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        for (name in listOf("root", "appearance", "size", "typing", "gestures", "shortcuts", "gifs", "guide")) {
            assertNotNull(name, page(activity, name).preferenceScreen)
        }
    }

    @Test fun typingPage_hasTheClipboardSettings_withTheirDefaults() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val typing = page(activity, "typing")
        val switch = typing.findPreference<SwitchPreferenceCompat>(Prefs.CLIPBOARD)!!
        val keep = typing.findPreference<ListPreference>(Prefs.CLIPBOARD_KEEP)!!
        assertTrue(switch.isChecked)
        assertEquals("Clipboard history", switch.title.toString())
        assertEquals(listOf("1", "24", "168"), keep.entryValues.map { it.toString() })
        assertEquals(listOf("1 hour", "1 day", "7 days"), keep.entries.map { it.toString() })
        assertEquals(Prefs.CLIPBOARD_KEEP_DEFAULT.toString(), keep.value)
        assertEquals("7 days", keep.summary.toString())
        assertTrue(keep.isEnabled)
        // The list follows the switch.
        switch.isChecked = false
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(keep.isEnabled)
        keep.value = "24"
        assertEquals("1 day", keep.summary.toString())
        assertEquals("24", typing.preferenceManager.sharedPreferences!!.getString(Prefs.CLIPBOARD_KEEP, null))
    }
}

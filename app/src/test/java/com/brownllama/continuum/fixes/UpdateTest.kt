package com.brownllama.continuum.fixes

import android.content.pm.PackageManager
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.brownllama.continuum.SettingsActivity
import com.brownllama.continuum.update.Transport
import com.brownllama.continuum.update.UpdateClient
import com.brownllama.continuum.update.UpdateException
import com.brownllama.continuum.update.UpdateFlow
import com.brownllama.continuum.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executor

private const val BASE = "https://github.com/brownLlama/continuum/releases/download/v0.0.38"

/** What GitHub answers for the latest release (the parts the app reads). */
private fun releaseJson(tag: String = "v0.0.38", apk: String = "$BASE/Continuum.apk") = """
{"tag_name":"$tag","html_url":"https://github.com/brownLlama/continuum/releases/tag/$tag",
 "assets":[{"name":"Continuum.apk","size":1234,"browser_download_url":"$apk"}]}
""".trimIndent()

/** A network that answers from memory. */
private class FakeNet(val json: String = releaseJson(), val fail: Boolean = false) : Transport {
    val asked = ArrayList<String>()
    override fun getText(url: String, maxBytes: Long): String {
        asked += url
        if (fail) throw java.io.IOException("offline")
        return json
    }
}

/** The release parsing and version comparison (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateClientTest {
    @Test fun versionsCompareNumbersNotText() {
        assertTrue(Versions.isNewer("0.0.36", "0.0.37"))
        assertTrue(Versions.isNewer("0.0.9", "0.0.10"))
        assertTrue(Versions.isNewer("0.0.99", "v0.1.0"))
        assertTrue(Versions.isNewer("0.9", "1.0.0"))
        assertFalse(Versions.isNewer("0.0.37", "0.0.37"))
        assertFalse(Versions.isNewer("0.0.37", "v0.0.37"))
        assertFalse(Versions.isNewer("1.0", "0.0.37"))
        assertFalse(Versions.isNewer("1.0.0", "1.0"))
        assertFalse(Versions.isNewer(null, "0.0.37"))
        assertFalse(Versions.isNewer("0.0.36", "latest"))
        assertFalse(Versions.isNewer("0.0.36", "0.0.37-beta"))
        assertNull(Versions.parse("1.2.x"))
    }

    @Test fun readsTheLatestRelease() {
        val net = FakeNet()
        val r = UpdateClient(net).latest()
        assertEquals(listOf(UpdateClient.LATEST_URL), net.asked)
        assertEquals("0.0.38", r.version)
        assertEquals("$BASE/Continuum.apk", r.apkUrl)
        assertEquals(1234L, r.apkBytes)
        assertEquals("https://github.com/brownLlama/continuum/releases/tag/v0.0.38", r.pageUrl)
    }

    @Test fun refusesReleasesItCannotTrust() {
        // Links from anywhere but this repository's releases are never taken.
        assertThrows { UpdateClient.parseRelease(releaseJson(apk = "https://evil.example/Continuum.apk")) }
        assertThrows { UpdateClient.parseRelease(releaseJson(apk = "http://github.com/brownLlama/continuum/releases/download/v1/Continuum.apk")) }
        assertThrows { UpdateClient.parseRelease(releaseJson(apk = "https://github.com/someone/else/releases/download/v1/Continuum.apk")) }
        assertThrows { UpdateClient.parseRelease(releaseJson(tag = "nightly")) }
        assertThrows { UpdateClient.parseRelease("""{"tag_name":"v1.0.0","assets":[]}""") }
        assertThrows { UpdateClient.parseRelease("not json") }
        assertThrows { UpdateClient(FakeNet(fail = true)).latest() }
        // A page link outside the repository falls back to the releases page.
        val page = UpdateClient.parseRelease(releaseJson().replace("\"html_url\":\"https://github.com/brownLlama", "\"html_url\":\"https://evil.example"))
        assertTrue(page.pageUrl.startsWith("https://github.com/brownLlama/continuum/releases/"))
    }

    private fun assertThrows(block: () -> Unit) {
        try { block(); fail("expected an UpdateException") } catch (_: UpdateException) {}
    }

    @Test fun theAppAsksForNoInstallPermission_andOnlyTheTwoItNeeds() {
        // Google Play Protect treats "install unknown apps" plus downloading an APK as a warning sign.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val perms = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.toSet()
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in perms)
        assertEquals(setOf("android.permission.INTERNET", "android.permission.VIBRATE"), perms)
    }
}

/** Settings → Check for updates, with the network replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateFlowTest {
    private val now = Executor { it.run() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dialog(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }

    private fun text(d: AlertDialog) = d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString() + " | " +
        (d.findViewById<TextView>(android.R.id.message)?.text ?: "")

    private fun flow(net: FakeNet, installed: String, opened: MutableList<String>): UpdateFlow {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        return UpdateFlow(activity, UpdateClient(net), now, installed) { opened += it }
    }

    @Test fun aNewerVersionIsOffered_andDownloadOpensItsLinkInTheBrowser() {
        val opened = ArrayList<String>()
        val net = FakeNet()
        flow(net, "0.0.37", opened).start()
        val offer = dialog()
        assertTrue(text(offer), text(offer).contains("Version 0.0.38 is available"))
        assertTrue(text(offer), text(offer).contains("You have 0.0.37"))
        assertEquals("only the release was asked for", listOf(UpdateClient.LATEST_URL), net.asked)
        assertTrue("nothing opened before asking", opened.isEmpty())
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("$BASE/Continuum.apk"), opened)
    }

    @Test fun whatsNewOpensTheReleasePage() {
        val opened = ArrayList<String>()
        flow(FakeNet(), "0.0.37", opened).start()
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(listOf("https://github.com/brownLlama/continuum/releases/tag/v0.0.38"), opened)
    }

    @Test fun nothingIsOpenedWhenYouAreUpToDateOrSayNotNow() {
        val opened = ArrayList<String>()
        flow(FakeNet(), "0.0.38", opened).start()
        assertTrue(text(dialog()).contains("up to date"))
        flow(FakeNet(), "0.0.30", opened).start()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(opened.isEmpty())
    }

    @Test fun anOfflinePhoneIsToldInPlainWords() {
        flow(FakeNet(fail = true), "0.0.30", ArrayList()).start()
        assertTrue(text(dialog()).contains("Couldn't reach GitHub"))
    }

    @Test fun theSettingsPageHasTheRow() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val f = SettingsActivity.ScreenFragment.of("root")
        activity.supportFragmentManager.beginTransaction().replace(com.brownllama.continuum.R.id.prefs, f).commitNow()
        idle()
        val row = f.findPreference<androidx.preference.Preference>("update")
        assertNotNull(row)
        assertEquals("Check for updates", row!!.title.toString())
    }
}

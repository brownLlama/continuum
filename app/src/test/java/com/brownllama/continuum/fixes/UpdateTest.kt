package com.brownllama.continuum.fixes

import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.brownllama.continuum.SettingsActivity
import com.brownllama.continuum.update.Release
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

private const val BASE = "https://github.com/brownLlama/continuum/releases/download/v0.0.38"

private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** What GitHub answers for the latest release (the parts the app reads). */
private fun releaseJson(tag: String = "v0.0.38", apk: String = "$BASE/Continuum.apk", sums: String = "$BASE/SHA256SUMS.txt") = """
{"tag_name":"$tag","html_url":"https://github.com/brownLlama/continuum/releases/tag/$tag",
 "assets":[{"name":"Continuum.apk","size":1234,"browser_download_url":"$apk"},
           {"name":"SHA256SUMS.txt","size":90,"browser_download_url":"$sums"}]}
""".trimIndent()

/** A network that answers from memory. */
private class FakeNet(
    val json: String = releaseJson(),
    val apk: ByteArray = "the apk".toByteArray(),
    val sums: String? = null,
    val fail: Boolean = false,
) : Transport {
    val asked = ArrayList<String>()
    override fun getText(url: String, maxBytes: Long): String {
        asked += url
        if (fail) throw java.io.IOException("offline")
        return if (url.endsWith("SHA256SUMS.txt")) sums ?: "${sha(apk)}  Continuum.apk\n" else json
    }
    override fun download(url: String, dest: File, maxBytes: Long, progress: (Long, Long) -> Unit) {
        asked += url
        if (fail) throw java.io.IOException("offline")
        dest.writeBytes(apk)
        progress(apk.size.toLong(), apk.size.toLong())
    }
}

/** The release parsing, version comparison and checked download (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateClientTest {
    @get:Rule val tmp = TemporaryFolder()

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
        assertEquals("$BASE/SHA256SUMS.txt", r.sumsUrl)
        assertEquals(1234L, r.apkBytes)
        assertEquals("https://github.com/brownLlama/continuum/releases/tag/v0.0.38", r.pageUrl)
    }

    @Test fun refusesReleasesItCannotTrust() {
        // Files from anywhere but this repository's releases are never taken.
        val foreign = releaseJson(apk = "https://evil.example/Continuum.apk", sums = "https://evil.example/SHA256SUMS.txt")
        assertThrows { UpdateClient.parseRelease(foreign) }
        val http = releaseJson(apk = "http://github.com/brownLlama/continuum/releases/download/v1/Continuum.apk")
        assertThrows { UpdateClient.parseRelease(http) }
        val other = releaseJson(apk = "https://github.com/someone/else/releases/download/v1/Continuum.apk")
        assertThrows { UpdateClient.parseRelease(other) }
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

    @Test fun readsChecksumFiles() {
        val h = "a".repeat(64)
        val g = "b".repeat(64)
        assertEquals(h, UpdateClient.parseSums("$h  Continuum.apk\n$g  Other.apk\n", "Continuum.apk"))
        assertEquals(g, UpdateClient.parseSums("$h  Continuum.apk\n$g *Other.apk\n", "Other.apk"))
        assertNull(UpdateClient.parseSums("$h  Other.apk", "Continuum.apk"))
        assertNull(UpdateClient.parseSums("tooshort  Continuum.apk", "Continuum.apk"))
    }

    @Test fun downloadsAndChecksTheFile() {
        val net = FakeNet()
        val client = UpdateClient(net)
        var last = -1L
        val file = client.download(client.latest(), tmp.root) { done, _ -> last = done }
        assertEquals("the apk", file.readText())
        assertEquals(7L, last)
    }

    @Test fun aDamagedOrUnlistedDownloadIsDeleted() {
        val wrong = FakeNet(sums = "${"0".repeat(64)}  Continuum.apk")
        val client = UpdateClient(wrong)
        assertThrows { client.download(client.latest(), tmp.root) { _, _ -> } }
        assertFalse(File(tmp.root, "Continuum.apk").exists())
        val unlisted = FakeNet(sums = "${sha("x".toByteArray())}  Other.apk")
        assertThrows { UpdateClient(unlisted).download(UpdateClient(unlisted).latest(), tmp.root) { _, _ -> } }
        // No checksums in the release: nothing is trusted.
        val noSums = Release("0.0.38", "https://github.com/brownLlama/continuum/releases/latest", "$BASE/Continuum.apk", 1, null)
        assertThrows { UpdateClient(FakeNet()).download(noSums, tmp.root) { _, _ -> } }
        assertFalse(File(tmp.root, "Continuum.apk").exists())
    }
}

/** Settings → Check for updates, with the network and the installer replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateFlowTest {
    private val now = Executor { it.run() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dialog(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }

    private fun text(d: AlertDialog) = d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString() + " | " +
        (d.findViewById<TextView>(android.R.id.message)?.text ?: "")

    private fun flow(net: FakeNet, installed: String, installs: MutableList<File>): UpdateFlow {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        return UpdateFlow(activity, UpdateClient(net), now, installed) { installs += it }
    }

    @Test fun aNewerVersionIsOfferedDownloadedAndHandedToTheInstaller() {
        val installs = ArrayList<File>()
        val net = FakeNet()
        flow(net, "0.0.37", installs).start()
        val offer = dialog()
        assertTrue(text(offer), text(offer).contains("Version 0.0.38 is available"))
        assertTrue(text(offer), text(offer).contains("You have 0.0.37"))
        assertTrue("nothing downloaded before asking", net.asked.none { it.endsWith(".apk") })
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, installs.size)
        assertEquals("the apk", installs[0].readText())
        assertEquals(listOf(UpdateClient.LATEST_URL, "$BASE/SHA256SUMS.txt", "$BASE/Continuum.apk"), net.asked)
    }

    @Test fun nothingIsDownloadedWhenYouAreUpToDateOrSayNotNow() {
        val installs = ArrayList<File>()
        val net = FakeNet()
        flow(net, "0.0.38", installs).start()
        assertTrue(text(dialog()).contains("up to date"))
        val later = FakeNet()
        flow(later, "0.0.30", installs).start()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(installs.isEmpty())
        assertEquals(listOf(UpdateClient.LATEST_URL), later.asked)
    }

    @Test fun failuresAreSaidInPlainWords() {
        val installs = ArrayList<File>()
        flow(FakeNet(fail = true), "0.0.30", installs).start()
        assertTrue(text(dialog()).contains("Couldn't reach GitHub"))
        flow(FakeNet(sums = "${"0".repeat(64)}  Continuum.apk"), "0.0.30", installs).start()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(text(dialog()).contains("checksum"))
        assertTrue("a damaged file is never installed", installs.isEmpty())
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

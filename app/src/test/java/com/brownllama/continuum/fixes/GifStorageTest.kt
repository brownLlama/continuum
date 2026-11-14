package com.brownllama.continuum.fixes

import com.brownllama.continuum.gif.GifFeed
import com.brownllama.continuum.gif.KlipyClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** GIF downloads are capped as they stream, and the GIF cache is kept small by age and by size. */
class GifStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a download within the limit is copied whole`() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        KlipyClient.copyCapped(ByteArrayInputStream(data), out, 100_000)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun `a download over the limit is refused before the extra bytes are written`() {
        val out = ByteArrayOutputStream()
        try {
            KlipyClient.copyCapped(ByteArrayInputStream(ByteArray(100_000)), out, 50_000)
            fail("expected the copy to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(out.size() <= 50_000)
        }
    }

    private fun file(name: String, bytes: Int, ageMs: Long, now: Long) =
        File(tmp.root, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(now - ageMs)
        }

    @Test fun `files older than a day leave the cache`() {
        val now = System.currentTimeMillis()
        val old = file("old.gif", 10, 25 * HOUR, now)
        val fresh = file("fresh.gif", 10, HOUR, now)
        GifFeed.prune(tmp.root, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test fun `over the size limit the oldest files go first, but never ones just used`() {
        val now = System.currentTimeMillis()
        val oldest = file("a.gif", 400, 5 * HOUR, now)
        val older = file("b.gif", 400, 4 * HOUR, now)
        val newer = file("c.gif", 400, 3 * HOUR, now)
        val justUsed = file("d.gif", 400, 60_000, now)
        GifFeed.prune(tmp.root, now, maxBytes = 900)
        assertFalse(oldest.exists())
        assertFalse(older.exists())
        assertTrue(newer.exists())
        assertTrue(justUsed.exists())

        // Even far over the limit, a file touched in the last minutes stays: it may be on its way to an app.
        GifFeed.prune(tmp.root, now, maxBytes = 100)
        assertFalse(newer.exists())
        assertTrue(justUsed.exists())
    }

    @Test fun `under the size limit nothing is touched`() {
        val now = System.currentTimeMillis()
        val a = file("a.gif", 400, 5 * HOUR, now)
        val b = file("b.gif", 400, HOUR, now)
        GifFeed.prune(tmp.root, now, maxBytes = 800)
        assertEquals(listOf(true, true), listOf(a.exists(), b.exists()))
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}

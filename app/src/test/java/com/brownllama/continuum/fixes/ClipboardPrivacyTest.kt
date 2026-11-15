package com.brownllama.continuum.fixes

import com.brownllama.continuum.clipboard.ClipboardHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The clipboard history's privacy rules: the clip to keep out, how long copies are kept, and its file. */
class ClipboardPrivacyTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000L
    private fun history(file: File, io: Executor? = null) = ClipboardHistory(file, { clock }, io)

    @Test fun `a skipped clip is recognised by the time it was copied`() {
        val h = history(tmp.newFile())
        assertFalse(h.isSkipped(500L, "secret".hashCode()))
        h.skip(500L, "secret".hashCode())
        assertTrue(h.isSkipped(500L, "secret".hashCode()))
        // Another clip, even of the same text, was copied at another time.
        assertFalse(h.isSkipped(501L, "secret".hashCode()))
        assertFalse(h.isSkipped(0L, "secret".hashCode()))
    }

    @Test fun `a skipped clip stays skipped after a restart, and its text is not in the file`() {
        val file = tmp.newFile()
        history(file).apply {
            add("hello")
            skip(777L, "hunter2".hashCode())
        }
        assertFalse(file.readText().contains("hunter2"))
        assertFalse(file.readText().contains("hunter2".hashCode().toString()))
        val again = history(file)
        assertTrue(again.isSkipped(777L, "hunter2".hashCode()))
        assertEquals(listOf("hello"), again.texts())
    }

    @Test fun `without a copy time the text decides, but only until the restart`() {
        val file = tmp.newFile()
        val h = history(file)
        h.skip(0L, "secret".hashCode())
        assertTrue(h.isSkipped(0L, "secret".hashCode()))
        assertFalse(h.isSkipped(0L, "other".hashCode()))
        assertFalse(history(file).isSkipped(0L, "secret".hashCode()))
    }

    @Test fun `only the latest skipped clip is remembered`() {
        val h = history(tmp.newFile())
        h.skip(10L, 1)
        h.skip(20L, 2)
        assertFalse(h.isSkipped(10L, 1))
        assertTrue(h.isSkipped(20L, 2))
    }

    @Test fun `a file written by an older version still loads`() {
        val file = tmp.newFile()
        file.writeText("${clock - 5}\tone\n${clock - 9}\ttwo\\nlines\n")
        assertEquals(listOf("one", "two\nlines"), history(file).texts())
    }

    @Test fun `a shorter keep time drops older copies, also from the file`() {
        val file = tmp.newFile()
        val h = history(file)
        h.add("old")
        clock += 2 * HOUR
        h.add("new")
        assertEquals(listOf("new", "old"), h.texts())
        h.keepMs = HOUR
        assertEquals(listOf("new"), h.texts())
        assertFalse(file.readText().contains("old"))
        assertEquals(listOf("new"), history(file).texts())
    }

    @Test fun `expired copies leave the file when it is next read`() {
        val file = tmp.newFile()
        history(file).add("stale")
        clock += ClipboardHistory.KEEP_MS + 1
        assertEquals(emptyList<String>(), history(file).texts())
        assertFalse(file.readText().contains("stale"))
    }

    @Test fun `clear empties the history and its file`() {
        val file = tmp.newFile()
        val h = history(file)
        h.add("a")
        h.add("b")
        h.clear()
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(emptyList<String>(), history(file).texts())
        h.clear() // nothing to do, and nothing breaks
    }

    @Test fun `with an executor, reading and writing happen in the background and nothing is lost`() {
        val file = tmp.newFile()
        file.writeText("${clock - 1}\tfrom disk\n")
        val io = Executors.newSingleThreadExecutor()
        try {
            val h = history(file, io)
            // The first use waits for the file to be read.
            h.add("typed now")
            assertEquals(listOf("typed now", "from disk"), h.texts())
            h.skip(42L, 7)
            // A second history on the same executor reads after the first one's writes.
            val again = history(file, io)
            assertEquals(listOf("typed now", "from disk"), again.texts())
            assertTrue(again.isSkipped(42L, 7))
        } finally {
            io.shutdown()
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `an executor that takes no more work falls back to doing it at once`() {
        val file = tmp.newFile()
        val io = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val h = history(file, io)
        h.add("still saved")
        assertEquals(listOf("still saved"), history(file).texts())
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}

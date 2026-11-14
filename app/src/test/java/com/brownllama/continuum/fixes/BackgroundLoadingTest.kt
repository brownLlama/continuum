package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.EmojiHistory
import com.brownllama.continuum.prediction.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The learned words and the emoji counts read in the background behave exactly as when read at
 * once: whatever is asked first waits for the file, and nothing learned meanwhile is lost.
 */
class BackgroundLoadingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 20_000

    /** A model with some history, saved to [file]. */
    private fun seed(file: File) {
        UserModel(file, { day }).apply {
            repeat(3) { learn(null, "zorp", "Zorp") }
            learn("zorp", "quux", "quux")
            learn("zorp", "quux", "quux")
            learn(null, "once", "once")
            forget(null, "badword")
            flush()
        }
    }

    private fun snapshot(m: UserModel) = listOf(
        m.unigrams.toSortedMap().toString(),
        m.count("zorp"), m.count("quux"), m.count("once"), m.count("missing"),
        m.bigramCount("zorp", "quux"), m.formOf("zorp"), m.formOf("quux"),
        m.isConfirmed("zorp"), m.isConfirmed("once"), m.isBlocked("BadWord"), m.anyBlocked,
        m.following("zorp", 3), m.mostUsed(2),
    )

    @Test fun `a model read in the background equals one read at once`() {
        val file = File(tmp.root, "user_model.tsv")
        seed(file)
        val direct = UserModel(file, { day })
        val background = UserModel(file, { day }, background = true)
        assertEquals(snapshot(direct), snapshot(background))
        assertEquals(3, background.count("zorp"))
        assertEquals("Zorp", background.formOf("zorp"))
        assertTrue(background.isBlocked("badword"))
    }

    @Test fun `every entry point waits for the file`() {
        val file = File(tmp.root, "user_model.tsv")
        seed(file)
        // Each of these is the very first call on a fresh background model.
        assertEquals(3, UserModel(file, { day }, background = true).count("zorp"))
        assertEquals(2, UserModel(file, { day }, background = true).bigramCount("zorp", "quux"))
        assertEquals("Zorp", UserModel(file, { day }, background = true).formOf("zorp"))
        assertTrue(UserModel(file, { day }, background = true).isConfirmed("zorp"))
        assertTrue(UserModel(file, { day }, background = true).isBlocked("badword"))
        assertTrue(UserModel(file, { day }, background = true).anyBlocked)
        assertEquals(listOf("quux"), UserModel(file, { day }, background = true).following("zorp", 3))
        assertEquals(listOf("zorp"), UserModel(file, { day }, background = true).mostUsed(1))
        assertEquals(3, UserModel(file, { day }, background = true).unigrams.size)
        assertFalse(UserModel(file, { day }, background = true).compact())
    }

    @Test fun `learning right after start adds to the file's words instead of replacing them`() {
        val file = File(tmp.root, "user_model.tsv")
        seed(file)
        UserModel(file, { day }, background = true).apply {
            learn(null, "brandnew", "brandnew") // a new word is saved at once
            learn(null, "zorp", null)
            flush()
        }
        val after = UserModel(file, { day })
        assertEquals(4, after.count("zorp"))
        assertEquals(2, after.count("quux"))
        assertEquals(1, after.count("brandnew"))
        assertEquals(2, after.bigramCount("zorp", "quux"))
        assertTrue(after.isBlocked("badword"))
    }

    @Test fun `unlearn, confirm, forget and clear work on a model read in the background`() {
        val file = File(tmp.root, "user_model.tsv")
        seed(file)
        UserModel(file, { day }, background = true).apply {
            unlearn("zorp", "quux")
            confirm("once")
            forget("zorp", "Zorp")
            unblock("badword")
            flush()
        }
        val after = UserModel(file, { day })
        assertEquals(1, after.count("quux"))
        // Forgetting "zorp" took its pairs along.
        assertEquals(0, after.bigramCount("zorp", "quux"))
        assertTrue(after.isConfirmed("once"))
        assertEquals(0, after.count("zorp"))
        assertTrue(after.isBlocked("zorp"))
        assertFalse(after.isBlocked("badword"))

        UserModel(file, { day }, background = true).apply {
            clear()
            flush()
        }
        assertFalse(file.exists())
        assertEquals(0, UserModel(file, { day }, background = true).unigrams.size)
    }

    @Test fun `words unused for too long are dropped while reading in the background too`() {
        val file = File(tmp.root, "user_model.tsv")
        seed(file)
        val later = day + UserModel.FORGET_DAYS + 1
        val direct = UserModel(file, { later })
        val background = UserModel(file, { later }, background = true)
        assertEquals(0, direct.count("zorp"))
        assertEquals(0, background.count("zorp"))
        assertNull(background.formOf("zorp"))
    }

    @Test fun `a missing file gives an empty model that can learn`() {
        val file = File(tmp.root, "none.tsv")
        val m = UserModel(file, { day }, background = true)
        assertEquals(0, m.count("anything"))
        m.learn(null, "first", "first")
        m.flush()
        assertEquals(1, UserModel(file, { day }).count("first"))
    }

    @Test fun `emoji counts read and written in the background keep their order`() {
        val file = File(tmp.root, "emoji.tsv")
        EmojiHistory(file).apply {
            use("🙂"); use("🔥"); use("🔥")
        }
        val io = Executors.newSingleThreadExecutor()
        try {
            val h = EmojiHistory(file, io)
            assertEquals(listOf("🔥", "🙂"), h.top(2))
            h.use("🙂"); h.use("🙂")
            assertEquals(listOf("🙂", "🔥"), h.top(2))
            // Read back on the same executor, after the writes above.
            assertEquals(listOf("🙂", "🔥"), EmojiHistory(file, io).top(2))
        } finally {
            io.shutdown()
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf("🙂", "🔥"), EmojiHistory(file).top(2))
        assertFalse(File(file.path + ".tmp").exists())
    }
}

package com.brownllama.continuum.fixes

import com.brownllama.continuum.clipboard.ClipboardHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Copied pictures in the clipboard history: kept with the texts, and their files deleted when they go. */
class ClipboardImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000L
    private var made = 0
    private fun history(file: File) = ClipboardHistory(file, { clock })

    /** A picture file in the history's folder, as the keyboard saves one before adding it. */
    private fun picture(h: ClipboardHistory): File {
        h.imageDir.mkdirs()
        return File(h.imageDir, "clip${made++}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test fun `a picture is kept among the texts, in order, and after a restart`() {
        val file = tmp.newFile()
        val h = history(file)
        h.add("hello")
        clock += 1
        val p = picture(h)
        h.addImage(p, 42)
        assertEquals(listOf(null, "hello"), h.clips().map { it.text })
        assertEquals(p, h.clips()[0].image)
        assertEquals("texts leave pictures out", listOf("hello"), h.texts())
        val again = history(file)
        assertEquals(listOf(null, "hello"), again.clips().map { it.text })
        assertEquals(p.name, again.clips()[0].image?.name)
        assertTrue(again.hasImage(42))
        assertFalse(again.hasImage(43))
    }

    @Test fun `the same clip's picture is kept once`() {
        val h = history(tmp.newFile())
        h.addImage(picture(h), 7)
        val second = picture(h)
        h.addImage(second, 7)
        assertEquals(1, h.clips().size)
        assertFalse("the extra copy is deleted", second.exists())
    }

    @Test fun `removing a picture deletes its file`() {
        val h = history(tmp.newFile())
        val p = picture(h)
        h.addImage(p, 1)
        h.add("text")
        h.remove(1)
        assertEquals(listOf("text"), h.clips().map { it.text })
        assertFalse(p.exists())
    }

    @Test fun `clearing deletes every picture`() {
        val h = history(tmp.newFile())
        val a = picture(h)
        val b = picture(h)
        h.addImage(a, 1)
        h.addImage(b, 2)
        h.clear()
        assertTrue(h.clips().isEmpty())
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test fun `an expired picture goes with its file`() {
        val h = history(tmp.newFile())
        val p = picture(h)
        h.addImage(p, 1)
        clock += ClipboardHistory.KEEP_MS + 1
        assertTrue(h.clips().isEmpty())
        assertFalse(p.exists())
    }

    @Test fun `only so many pictures are kept, the oldest going first`() {
        val h = history(tmp.newFile())
        val files = (0..ClipboardHistory.MAX_IMAGES).map { i ->
            picture(h).also {
                h.addImage(it, i)
                clock += 1
            }
        }
        h.add("text stays")
        val clips = h.clips()
        assertEquals(ClipboardHistory.MAX_IMAGES, clips.count { it.image != null })
        assertEquals("text stays", clips[0].text)
        assertFalse("the oldest picture is gone", files[0].exists())
        assertFalse(h.hasImage(0))
        assertTrue(files.last().exists())
    }

    @Test fun `stray picture files are deleted, and a picture whose file is gone is dropped, at the next start`() {
        val file = tmp.newFile()
        val h = history(file)
        val kept = picture(h)
        val lost = picture(h)
        h.addImage(kept, 1)
        h.addImage(lost, 2)
        lost.delete()
        val stray = picture(h)
        val again = history(file)
        assertEquals(listOf(kept.name), again.clips().mapNotNull { it.image?.name })
        assertFalse(stray.exists())
        assertTrue(kept.exists())
    }

    @Test fun `older history files still read, and a broken picture line is skipped`() {
        val file = tmp.newFile()
        file.writeText("1000000000\tplain text\nimage\tbroken\n")
        assertEquals(listOf("plain text"), history(file).clips().map { it.text })
    }

    @Test fun `picture types and their extensions`() {
        assertEquals("png", ClipboardHistory.extensionFor("image/png"))
        assertEquals("jpg", ClipboardHistory.extensionFor("IMAGE/JPEG"))
        assertNull(ClipboardHistory.extensionFor("image/svg+xml"))
        assertNull(ClipboardHistory.extensionFor(null))
        assertEquals("image/gif", ClipboardHistory.mimeOf(File("a.gif")))
        assertEquals("image/heic", ClipboardHistory.mimeOf(File("a.heic")))
        assertNull(ClipboardHistory.mimeOf(File("a.txt")))
    }
}

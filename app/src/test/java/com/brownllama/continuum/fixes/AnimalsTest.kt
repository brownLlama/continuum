package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brownllama.continuum.keyboard.Animal
import com.brownllama.continuum.keyboard.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The visiting animals: their sprites are well formed (a bad one would take the keyboard down the
 * first time it appears), they line up with the settings list, and a visit draws and ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnimalsTest {
    @Test fun `every sprite frame is whole, the same size, and keeps its margin clear`() {
        for (s in Species.ALL) {
            val frames = s.frames
            for ((i, f) in frames.withIndex()) {
                val what = "${s.key} frame $i"
                assertEquals("$what height", s.height, f.size)
                for ((y, row) in f.withIndex()) {
                    assertEquals("$what row $y width", Species.W, row.length)
                    // Only colours the species has, '.' and the outline; nothing in the first or last column
                    // but an outline (a sprite there would be clipped, with no outline outside it).
                    for ((x, ch) in row.withIndex()) {
                        if (ch == '.' || ch == '#') continue
                        runCatching { s.color(ch) }.getOrNull() ?: throw AssertionError("$what: no colour for '$ch' at $x,$y")
                        assertTrue("$what: '$ch' in the margin at $x,$y", x in 1 until Species.W - 1)
                    }
                }
                assertTrue("$what has something drawn", f.any { r -> r.any { it != '.' } })
                // The feet stand on the last row: it is the outline below the lowest pixels.
                assertTrue("$what: nothing under the outline", f.last().all { it == '.' || it == '#' })
            }
        }
    }

    @Test fun `the outline goes round the silhouette and nowhere else`() {
        val blank = ".".repeat(Species.W)
        val dot = ".".repeat(11) + "X" + ".".repeat(Species.W - 12)
        val out = Species.outlined(listOf(blank, dot, blank))
        assertEquals(5, out.size)
        fun row(y: Int) = out[y].indices.filter { out[y][it] != '.' }.map { it to out[y][it] }
        assertEquals(emptyList<Pair<Int, Char>>(), row(0))
        assertEquals(listOf(11 to '#'), row(1))
        assertEquals(listOf(10 to '#', 11 to 'X', 12 to '#'), row(2))
        assertEquals(listOf(11 to '#'), row(3))
        assertEquals(emptyList<Pair<Int, Char>>(), row(4))
    }

    @Test fun `the animals are the ones the settings list offers, in its order`() {
        val xml = File("src/main/res/values/strings.xml").readText()
        fun array(name: String): List<String> {
            val body = Regex("<string-array name=\"$name\"[^>]*>(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)!!.groupValues[1]
            return Regex("<item>(.*?)</item>").findAll(body).map { it.groupValues[1] }.toList()
        }
        assertEquals(Species.ALL.map { it.key }, array("animal_values"))
        assertEquals(Species.ALL.size, array("animal_names").size)
        assertEquals(Species.ALL.size, Species.ALL.map { it.key }.toSet().size)
        for (s in Species.ALL) assertEquals(s, Species.named(s.key))
    }

    @Test fun `the characters are Schibu, Alex, Schimba, Nibu and Scheepu`() {
        assertEquals(listOf("schibu", "alex", "schimba", "nibu", "scheepu"), Species.ALL.map { it.key })
    }

    @Test fun `a choice saved by an older version carries over to the characters that took its place`() {
        assertEquals(Species.ALL, Species.picked(null))
        assertEquals(emptyList<Species>(), Species.picked(emptySet()))
        assertEquals(listOf("schibu"), Species.picked(setOf("llama", "cat")).map { it.key })
        assertEquals(listOf("schibu", "alex", "schimba"), Species.picked(setOf("snow_leopard", "dog", "llama")).map { it.key })
        assertEquals(listOf("nibu", "scheepu"), Species.picked(setOf("sheep")).map { it.key })
        assertEquals(listOf("alex", "scheepu"), Species.picked(setOf("alex", "scheepu")).map { it.key })
        // Only the cat and the turtle were picked, and they are gone: all of the new ones.
        assertEquals(Species.ALL, Species.picked(setOf("cat", "turtle")))
    }

    @Test fun `the saved choice is rewritten with the new names once`() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        prefs.edit().putStringSet(com.brownllama.continuum.Prefs.ANIMAL_KINDS, setOf("llama", "sheep", "cat")).commit()
        com.brownllama.continuum.Prefs.migrateAnimals(prefs)
        assertEquals(
            setOf("schibu", "nibu", "scheepu"),
            prefs.getStringSet(com.brownllama.continuum.Prefs.ANIMAL_KINDS, null),
        )
        // Nothing saved, or a choice of none, is left as it is.
        prefs.edit().remove(com.brownllama.continuum.Prefs.ANIMAL_KINDS).commit()
        com.brownllama.continuum.Prefs.migrateAnimals(prefs)
        assertEquals(null, prefs.getStringSet(com.brownllama.continuum.Prefs.ANIMAL_KINDS, null))
        prefs.edit().putStringSet(com.brownllama.continuum.Prefs.ANIMAL_KINDS, emptySet()).commit()
        com.brownllama.continuum.Prefs.migrateAnimals(prefs)
        assertEquals(emptySet<String>(), prefs.getStringSet(com.brownllama.continuum.Prefs.ANIMAL_KINDS, null))
    }

    @Test fun `no two animals have the same silhouette`() {
        val shapes = Species.ALL.associate { s -> s.key to s.stand.map { r -> r.map { it != '.' } } }
        for (a in shapes) for (b in shapes) if (a.key < b.key) assertFalse("${a.key} and ${b.key}", a.value == b.value)
    }

    @Test fun `a visit of every animal draws both ways and ends`() {
        val bmp = Bitmap.createBitmap(1080, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (s in Species.ALL) repeat(4) {
            val animal = Animal(6f)
            animal.start(s, 1080f, 300f, now = 0L)
            assertTrue(animal.visiting)
            var now = 0L
            var last = 0L
            while (animal.visiting && now < 20 * 60_000L) {
                now += 50
                last = animal.draw(canvas, 1080f, now)
                assertTrue("${s.key}: redraw delay $last", last == -1L || last >= 1L)
            }
            assertFalse("${s.key} leaves within 20 minutes", animal.visiting)
            assertEquals(-1L, last)
        }
    }
}

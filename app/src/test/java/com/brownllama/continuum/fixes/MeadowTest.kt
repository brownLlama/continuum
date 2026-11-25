package com.brownllama.continuum.fixes

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brownllama.continuum.keyboard.Animal
import com.brownllama.continuum.keyboard.Flower
import com.brownllama.continuum.keyboard.Meadow
import com.brownllama.continuum.keyboard.Species
import com.brownllama.continuum.keyboard.animalFeet
import com.brownllama.continuum.keyboard.animalFeetRows
import com.brownllama.continuum.keyboard.drawGrass
import com.brownllama.continuum.keyboard.drawGrassFront
import com.brownllama.continuum.keyboard.grassHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/**
 * The flowers in the grass and the animals that eat them: flowers grow inside the grass and apart,
 * an animal on the grass stops with its mouth on each flower it reaches, lowers its head and eats it
 * bite by bite, then walks on; eaten flowers grow back elsewhere. Some stretches of grass stand in
 * front of a passing animal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeadowTest {
    /**
     * A canvas to draw a visit on. Robolectric's canvas keeps a record of everything drawn on it, so
     * each visit gets its own, and visits step 50 ms at a time (as in AnimalsTest): a whole test on one
     * canvas in 20 ms steps drew over three million rectangles and ran out of memory.
     */
    private fun canvas() = Canvas(Bitmap.createBitmap(1080, 400, Bitmap.Config.ARGB_8888))

    /** Grass 180 × 15 pixels, the animals' feet 3 up: a phone's default grass. */
    private fun meadow(seed: Int) = Meadow(Random(seed)).also { it.fit(180, 15, 3) }

    @Test fun `flowers grow inside the grass, apart from each other, and not in grass too short for them`() {
        for (seed in 0 until 100) {
            val m = meadow(seed)
            assertEquals("seed $seed", 3, m.flowers.size)
            for (f in m.flowers) {
                assertTrue("in from the edges: ${f.col}", f.col in 4 until 176)
                assertTrue("head inside the grass: ${f.row}", f.row - 1 >= 1 && f.row + 1 <= 14)
                assertTrue("head above the animals' feet: ${f.row}", f.row > 3)
            }
            for (a in m.flowers) for (b in m.flowers) if (a !== b) assertTrue(abs(a.col - b.col) >= 12)
        }
        assertTrue(Meadow(Random(1)).also { it.fit(180, 5, 2) }.flowers.isEmpty())
    }

    @Test fun `eaten flowers grow back somewhere new, the uneaten stay`() {
        val m = meadow(7)
        val kept = m.flowers.drop(1)
        m.flowers[0].gone = true
        m.regrow()
        assertEquals(3, m.flowers.size)
        assertTrue(m.flowers.none { it.gone })
        assertTrue("the uneaten ones stay where they were", m.flowers.containsAll(kept))
    }

    @Test fun `a grazing frame lowers the head by its dip, mouth first`() {
        for (s in Species.ALL) for (d in 0..minOf(Species.MAX_DIP, s.mouthLift - 1)) {
            val frame = s.graze(d)
            val ch = frame[s.height - 1 - (s.mouthLift - d)][s.mouthCol]
            assertTrue("${s.key} dip $d: the mouth is '$ch'", ch != '.' && ch != '#')
        }
    }

    @Test fun `an animal on the grass eats every flower it comes to, mouth on it, and walks on`() {
        for (s in Species.ALL) repeat(4) { run ->
            val m = meadow(run)
            val animal = Animal(6f).also { it.meadow = m }
            animal.start(s, 1080f, 90f - 3 * 6f, now = 0L, groundRows = 3)
            val canvas = canvas()
            var now = 0L
            val bites = HashMap<Flower, Int>()
            while (animal.visiting && now < 10 * 60_000L) {
                now += 50
                val next = animal.draw(canvas, 1080f, now)
                assertTrue("${s.key}: redraw delay $next", next == -1L || next >= 1L)
                val f = animal.eating ?: continue
                assertEquals("${s.key}: its mouth is on the flower", f.col, animal.mouthColumn)
                assertTrue("${s.key}: bites only go up", f.bites >= (bites[f] ?: 0))
                bites[f] = f.bites
            }
            assertFalse("${s.key} leaves", animal.visiting)
            assertEquals("${s.key} ate all three", 3, bites.size)
            assertTrue(m.flowers.all { it.gone })
            assertTrue("bite by bite", bites.values.all { it == Flower.BITES - 1 })
        }
    }

    @Test fun `an animal not on grass eats nothing`() {
        val m = meadow(3)
        val animal = Animal(6f).also { it.meadow = m }
        animal.start(Species.ALEX, 1080f, 300f, now = 0L)
        val canvas = canvas()
        var now = 0L
        while (animal.visiting) {
            now += 50
            animal.draw(canvas, 1080f, now)
            assertEquals(null, animal.eating)
        }
        assertTrue(m.flowers.none { it.gone || it.bites > 0 })
    }

    @Test fun `a visit cut short leaves the flower it was eating whole`() {
        val m = meadow(5)
        val animal = Animal(6f).also { it.meadow = m }
        animal.start(Species.NIBU, 1080f, 72f, now = 0L, groundRows = 3)
        val canvas = canvas()
        var now = 0L
        while (animal.visiting && (animal.eating?.bites ?: 0) < 2) {
            now += 50
            animal.draw(canvas, 1080f, now)
        }
        val f = animal.eating!!
        animal.end()
        assertEquals(0, f.bites)
        assertFalse(f.gone)
    }

    @Test fun `some stretches of grass stand in front of the animals, others behind`() {
        val k = Keyboard().also { it.waitReady(); it.focus() }
        val v = k.view
        val c = Canvas(Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888))
        v.drawGrass(c)
        val columns = (0 until 540).count { v.grass.inFront(it) }
        assertTrue("$columns of 540 columns in front", columns in 540 / 5..540 * 4 / 5)
        // With an animal on the grass, the grass in front of it is drawn over it.
        assertTrue(v.grassHeight > 0)
        v.animal.start(Species.SCHEEPU, 1080f, v.animalFeet(), 0L, groundRows = v.animalFeetRows())
        v.animal.draw(c, 1080f, 16L)
        v.drawGrassFront(c)
        // And the flowers grew in the grass that was drawn.
        assertTrue(v.meadow.flowers.isNotEmpty())
    }
}

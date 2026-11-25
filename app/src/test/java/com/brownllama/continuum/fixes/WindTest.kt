package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindTest {
    private val times = (0L until 3_000_000L step 97L)

    @Test fun `gusts differ in force, direction, reach and timing, and some slots are calm`() {
        val gusts = (0L until 200L).map { Wind.gust(it) }
        assertTrue(gusts.any { it == null })
        val real = gusts.filterNotNull()
        assertTrue(real.map { it.dir }.toSet().size == 2)
        assertTrue(real.map { (it.force * 10).toInt() }.toSet().size > 3)
        assertTrue(real.map { ((it.to - it.from) * 10).toInt() }.toSet().size > 3)
        assertTrue(real.map { it.startMs / 500 }.toSet().size > 3)
        for (g in real) assertTrue("a gust ends inside its slot", g.endMs <= Wind.SLOT_MS && g.startMs >= 0)
    }

    @Test fun `still when calm, and nothing is scheduled until the next gust`() {
        var calm = 0
        for (t in times) {
            if (Wind.blowing(t)) continue
            calm++
            assertEquals(0, Wind.lean(50, 14, 200, 15, t))
            val next = t + Wind.nextIn(t)
            assertTrue(next > t)
            assertTrue("something blows at the time it said", Wind.blowing(next))
        }
        assertTrue(calm > 0)
    }

    @Test fun `the ground never moves and the tips lean a few pixels at most, both ways`() {
        var right = false
        var left = false
        for (t in times) for (col in 0 until 200 step 7) {
            assertEquals(0, Wind.lean(col, 0, 200, 15, t))
            val l = Wind.lean(col, 14, 200, 15, t)
            assertTrue(l in -4..4)
            if (l > 0) right = true
            if (l < 0) left = true
        }
        assertTrue(right && left)
    }

    @Test fun `redraws at a steady pace while it blows`() {
        val t = times.first { Wind.blowing(it) }
        assertEquals(Wind.FRAME_MS, Wind.nextIn(t))
    }

    @Test fun `grass of the default height (about 8 pixels) visibly sways`() {
        // A short strip of grass, 8 sprite pixels tall (the setting at about 15 dp). A gust must move its tips.
        val moved = times.count { Wind.blowing(it) && Wind.lean(60, 7, 200, 8, it) != 0 }
        val blowing = times.count { Wind.blowing(it) }
        assertTrue("tips move in most of a gust ($moved of $blowing)", moved > blowing / 3)
    }
}

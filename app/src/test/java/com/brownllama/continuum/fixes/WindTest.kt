package com.brownllama.continuum.fixes

import com.brownllama.continuum.keyboard.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindTest {
    @Test fun `still between gusts, and nothing is scheduled until the next one`() {
        val calm = Wind.GUST_MS + Wind.SWEEP_MS + 500
        assertTrue(!Wind.blowing(calm))
        assertEquals(Wind.PERIOD_MS - calm, Wind.nextIn(calm))
        assertEquals(0, Wind.lean(50, 14, 200, 15, calm))
    }

    @Test fun `the ground never moves and the tips lean most, never far`() {
        val peak = Wind.GUST_MS / 2
        for (col in 0 until 200) {
            assertEquals(0, Wind.lean(col, 0, 200, 15, peak))
            for (row in 0 until 15) assertTrue(Wind.lean(col, row, 200, 15, peak) in -1..3)
        }
        assertTrue((0 until 200).any { Wind.lean(it, 14, 200, 15, peak) >= 1 })
    }

    @Test fun `redraws at a steady pace while it blows`() {
        assertEquals(Wind.FRAME_MS, Wind.nextIn(1_000))
    }
}

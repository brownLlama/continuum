package com.brownllama.continuum.fixes

import com.brownllama.continuum.prediction.Mode
import com.brownllama.continuum.stats.TypingStats
import com.brownllama.continuum.stats.TypingStats.Outcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The typing statistics: what is counted, over which days, and what is kept on disk. */
class TypingStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var day = 20_000
    private fun stats(file: File = File(tmp.root, "stats.tsv")) = TypingStats(file, null) { day }

    @Test fun `each outcome is counted for its mode`() {
        val s = stats()
        repeat(3) { s.word(Mode.MINI, Outcome.KEPT) }
        s.word(Mode.MINI, Outcome.PICKED)
        s.word(Mode.MINI, Outcome.FIXED)
        s.word(Mode.FULL, Outcome.KEPT)
        s.prediction(Mode.MINI)
        s.split(Mode.FULL); s.splitUndone(Mode.FULL)
        s.tapsLearned(Mode.FULL, 7)
        val mini = s.counts(null).getValue(Mode.MINI)
        val full = s.counts(null).getValue(Mode.FULL)
        assertEquals(listOf(3L, 1L, 1L, 5L, 1L), listOf(mini.kept, mini.picked, mini.fixed, mini.words, mini.predictions))
        assertEquals(listOf(1L, 1L, 1L, 7L), listOf(full.words, full.splits, full.splitsUndone, full.tapsLearned))
        assertEquals(day, s.since)
    }

    @Test fun `the last days and the totals are counted apart`() {
        val s = stats()
        s.word(Mode.MINI, Outcome.KEPT)
        day += 10
        s.word(Mode.MINI, Outcome.FIXED)
        assertEquals(1L, s.counts(7).getValue(Mode.MINI).words)
        assertEquals(2L, s.counts(null).getValue(Mode.MINI).words)
        assertEquals(2L, s.counts(11).getValue(Mode.MINI).words)
    }

    @Test fun `old days are dropped from the file, the totals are not`() {
        val file = File(tmp.root, "stats.tsv")
        val s = stats(file)
        s.word(Mode.FULL, Outcome.KEPT)
        day += TypingStats.DAYS + 5
        s.word(Mode.FULL, Outcome.KEPT)
        s.save()
        val again = stats(file)
        assertEquals(2L, again.counts(null).getValue(Mode.FULL).words)
        assertEquals(1L, again.counts(TypingStats.DAYS + 10).getValue(Mode.FULL).words)
        assertEquals(1, file.readLines().count { it.startsWith("d\t") })
    }

    @Test fun `saved, read back, reset`() {
        val file = File(tmp.root, "stats.tsv")
        val s = stats(file)
        repeat(5) { s.word(Mode.MINI, Outcome.PICKED) }
        s.save()
        assertEquals(5L, stats(file).counts(7).getValue(Mode.MINI).picked)
        day += 3
        s.reset()
        assertEquals(0L, s.counts(null).getValue(Mode.MINI).words)
        assertEquals(day, s.since)
        assertEquals(0L, stats(file).counts(null).getValue(Mode.MINI).words)
        assertEquals(day, stats(file).since)
    }

    @Test fun `a damaged file is read as far as it makes sense`() {
        val file = File(tmp.root, "stats.tsv")
        file.writeText("since\t19990\nt\tMINI\t1\t2\t3\t4\t5\t6\t7\nt\tFULL\t1\t2\nd\t19999\tMINI\t1\t0\t0\t0\t0\t0\t0\nd\tx\tMINI\t1\t0\t0\t0\t0\t0\t0\nt\tNOPE\t1\t1\t1\t1\t1\t1\t1\nt\tFULL\t-1\t0\t0\t0\t0\t0\t0\n")
        val s = stats(file)
        assertEquals(19990, s.since)
        assertEquals(6L, s.counts(null).getValue(Mode.MINI).words)
        assertEquals(0L, s.counts(null).getValue(Mode.FULL).words)
        assertEquals(1L, s.counts(7).getValue(Mode.MINI).kept)
    }

    @Test fun `counted in the background, nothing is lost`() {
        val file = File(tmp.root, "stats.tsv")
        val io = Executors.newSingleThreadExecutor()
        try {
            val s = TypingStats(file, io) { day }
            repeat(45) { s.word(Mode.MINI, Outcome.KEPT) }
            s.save()
            assertEquals(45L, TypingStats(file, io) { day }.counts(null).getValue(Mode.MINI).kept)
        } finally {
            io.shutdown()
            assert(io.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

package com.brownllama.continuum.stats

import android.content.Context
import com.brownllama.continuum.DiskIo
import com.brownllama.continuum.Loading
import com.brownllama.continuum.prediction.Mode
import java.io.File
import java.util.concurrent.Executor

/**
 * How typing goes, as counts only: no words, no text. Per mode and per day, how many words were
 * kept as typed, picked from the suggestion bar, or fixed, how often a next-word prediction was
 * used, how often two words were typed for a missed space (and how often that was undone), and
 * how many taps taught "Learn how you tap". Shown on the settings page "Typing statistics", so the
 * user can see whether a change to the keyboard helps their own typing.
 *
 * Kept in [file] (app-private, excluded from backups) for the last [DAYS] days, plus totals since
 * [since]. Written in the background, every [SAVE_EVERY] changes and when the keyboard closes.
 * One instance per app process ([get]): the keyboard counts, the settings page reads.
 */
class TypingStats(
    private val file: File,
    private val io: Executor? = null,
    private val today: () -> Int = { (System.currentTimeMillis() / DAY_MS).toInt() },
) {
    /** What happened to one typed word. */
    enum class Outcome { KEPT, PICKED, FIXED }

    /** The counts for one mode, over some days. */
    class Counts {
        var kept = 0L
        var picked = 0L
        var fixed = 0L
        var predictions = 0L
        var splits = 0L
        var splitsUndone = 0L
        var tapsLearned = 0L

        /** Typed words: kept, picked or fixed. */
        val words: Long get() = kept + picked + fixed

        internal fun add(o: Counts) {
            kept += o.kept; picked += o.picked; fixed += o.fixed; predictions += o.predictions
            splits += o.splits; splitsUndone += o.splitsUndone; tapsLearned += o.tapsLearned
        }

        internal fun values() = longArrayOf(kept, picked, fixed, predictions, splits, splitsUndone, tapsLearned)

        internal fun set(v: List<Long>) {
            kept = v[0]; picked = v[1]; fixed = v[2]; predictions = v[3]; splits = v[4]; splitsUndone = v[5]; tapsLearned = v[6]
        }
    }

    /** Counts by day, then by mode. */
    private val days = HashMap<Int, HashMap<Mode, Counts>>()
    private val total = HashMap<Mode, Counts>()
    private var sinceDay = 0
    private var dirty = 0

    private val loading = Loading(io) { load() }

    /** The day counting started (or was last reset), in days since 1970. */
    val since: Int
        get() {
            loading.await()
            return sinceDay
        }

    fun word(mode: Mode, outcome: Outcome) = count(mode) {
        when (outcome) {
            Outcome.KEPT -> it.kept++
            Outcome.PICKED -> it.picked++
            Outcome.FIXED -> it.fixed++
        }
    }

    fun prediction(mode: Mode) = count(mode) { it.predictions++ }

    fun split(mode: Mode) = count(mode) { it.splits++ }

    fun splitUndone(mode: Mode) = count(mode) { it.splitsUndone++ }

    fun tapsLearned(mode: Mode, n: Int) = count(mode) { it.tapsLearned += n }

    /** The counts per mode over the last [lastDays] days (today included), or since [since] when null. */
    fun counts(lastDays: Int?): Map<Mode, Counts> {
        loading.await()
        if (lastDays == null) return Mode.entries.associateWith { m -> Counts().also { c -> total[m]?.let(c::add) } }
        val first = today() - lastDays + 1
        return Mode.entries.associateWith { m ->
            Counts().also { c -> for ((d, byMode) in days) if (d >= first) byMode[m]?.let(c::add) }
        }
    }

    /** Starts counting afresh. */
    fun reset() {
        loading.await()
        days.clear()
        total.clear()
        sinceDay = today()
        dirty++
        save()
    }

    /** Writes what changed since the last save. */
    fun save() {
        loading.await()
        if (dirty == 0) return
        dirty = 0
        prune()
        val text = buildString {
            append("since\t").append(sinceDay).append('\n')
            for ((m, c) in total) line("t", null, m, c)
            for ((d, byMode) in days) for ((m, c) in byMode) line("d", d, m, c)
        }
        DiskIo.write(file, text, io)
    }

    private fun StringBuilder.line(kind: String, day: Int?, m: Mode, c: Counts) {
        append(kind).append('\t')
        if (day != null) append(day).append('\t')
        append(m.name)
        for (v in c.values()) append('\t').append(v)
        append('\n')
    }

    private inline fun count(mode: Mode, change: (Counts) -> Unit) {
        loading.await()
        val d = today()
        if (sinceDay == 0) sinceDay = d
        change(days.getOrPut(d) { HashMap() }.getOrPut(mode) { Counts() })
        change(total.getOrPut(mode) { Counts() })
        if (++dirty >= SAVE_EVERY) save()
    }

    /** Days older than [DAYS] go. */
    private fun prune() {
        val first = today() - DAYS + 1
        days.keys.removeAll { it < first }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                fun counts(from: Int): List<Long>? =
                    p.drop(from).takeIf { it.size == VALUES }?.map { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: return null }
                when {
                    p.size == 2 && p[0] == "since" -> sinceDay = p[1].toIntOrNull() ?: 0
                    p.size == 2 + VALUES && p[0] == "t" -> {
                        val m = Mode.entries.firstOrNull { it.name == p[1] } ?: return@forEachLine
                        total.getOrPut(m) { Counts() }.set(counts(2) ?: return@forEachLine)
                    }
                    p.size == 3 + VALUES && p[0] == "d" -> {
                        val d = p[1].toIntOrNull() ?: return@forEachLine
                        val m = Mode.entries.firstOrNull { it.name == p[2] } ?: return@forEachLine
                        days.getOrPut(d) { HashMap() }.getOrPut(m) { Counts() }.set(counts(3) ?: return@forEachLine)
                    }
                }
            }
        }
        prune()
    }

    companion object {
        /** Days kept by day (the page shows the last 7). */
        const val DAYS = 30
        private const val VALUES = 7
        private const val SAVE_EVERY = 20
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile private var instance: TypingStats? = null

        /**
         * The app's one instance (the keyboard and the settings page share it), for the app's files
         * folder: a new folder (as in tests, where each test has an app of its own) gets a new one.
         */
        fun get(context: Context): TypingStats {
            val f = File(context.applicationContext.filesDir, "typing_stats.tsv")
            instance?.takeIf { it.file == f }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.file == f } ?: TypingStats(f, DiskIo.executor).also { instance = it }
            }
        }
    }
}

package com.brownllama.continuum.prediction

import com.brownllama.continuum.DiskIo
import com.brownllama.continuum.Loading
import com.brownllama.continuum.keyboard.KeyLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.sqrt

/**
 * Where this user's taps land: for each letter, how far from its key's centre they usually are.
 * The decoder ([Decoder.touch]) then expects each letter where the user actually puts it.
 *
 * It learns from words the user kept ([learn]): every tap of such a word was meant for its letter.
 * Words whose taps don't fit their letters (a typo that was corrected, a slip) are left out whole,
 * so a correction can't teach that a letter is somewhere it isn't. It also learns from letters
 * the user fixed ([learnTap]): a tap deleted and typed again was meant for the letter typed in
 * its place. Those matter most when the learned offsets have gone wrong (a big change of grip):
 * words are then rarely read right, and without the fixes it would hardly learn anything new.
 * A tap counts if it fits where its letter is expected now or the key's centre, so a model that
 * went wrong can still find its way back. An offset is learned in two
 * parts: where taps land on the whole (one thumb's reach, landing low), from every tap, and each
 * letter's own difference from that, which needs [PRIOR_LETTER] taps of the letter before it
 * counts half. Only the latest taps count ([MAX_ALL_N], [MAX_LETTER_N]), so it follows a change of
 * grip, and offsets stay within [MAX_OFFSET], so a few odd taps can't move a letter far.
 *
 * How widely taps scatter is not learned: measured on simulated typists, a spread learned from a
 * precise typist kept a sloppier one (typing while walking, someone else) worse off than the
 * default for hundreds of words, because only words that were read right are learned from.
 *
 * Mini mode also learns how high each keyboard row's taps land ([Shape.rowY]): the letters are
 * drawn staggered by row, and a user who aims at them says with each tap which row it was for
 * (Q, A and Y share a slot column and differ only in height). The rows start at the overall
 * average height and only move apart as the user's taps show they do ([PRIOR_ROW] taps of a row
 * before its own average counts half), so a user who taps the middle of the row whatever the
 * letter gets no difference between the rows, and the height changes nothing for them. Measured on
 * simulated typists (noise 0.8 slots): one aiming with a scatter of 0.6 rows went from 95.0% to
 * 96.9% of words right, 1.0 rows to 95.8%; one not aiming stayed at 95.0%.
 *
 * Kept separately per layout and mode (a mini-mode slot and a full-mode key are different
 * things), in [file]: app-private, excluded from backups. With [io], the file is read and
 * written in the background. Internally offsets are in units of the decoder's default spread
 * ([Decoder.sigmaX]), so the same bounds apply to both modes; [Shape] gives them in slots or keys.
 */
class TouchModel(private val file: File, private val io: Executor? = null) {

    /**
     * How far each letter's taps land from its key's centre, by letter code: across ([dx]) and down
     * ([dy], full mode). Mini mode: [rowY] is where each keyboard row's taps land in height (in rows
     * of the stagger, see [Tap.y]), with [rowSpread] their scatter; null until enough is known.
     */
    class Shape(val dx: FloatArray, val dy: FloatArray, val rowY: FloatArray? = null, val rowSpread: Float = 1f)

    /** A running mean of where taps landed, over about the latest [n] of them. */
    private class Mean {
        var n = 0f
        var dx = 0f
        var dy = 0f

        fun add(x: Float, y: Float, max: Float) {
            n = minOf(n + 1f, max)
            dx += (x - dx) / n
            dy += (y - dy) / n
        }
    }

    /** A running mean and variance of one row's tap heights, over about the latest [MAX_ROW_N]. */
    private class Height {
        var n = 0f
        var mean = 0f
        var variance = 0f

        fun add(y: Float) {
            n = minOf(n + 1f, MAX_ROW_N)
            val d = y - mean
            mean += d / n
            variance += (d * (y - mean) - variance) / n
        }
    }

    private class Group {
        val letters = Array(26) { Mean() }
        /** Mini mode: tap heights by keyboard row. */
        val rows = Array(3) { Height() }
        /** All letters together. */
        val all = Mean()
        /** [build]'s result, until the next [learn]. */
        var shape: Shape? = null
    }

    private val groups = HashMap<String, Group>()
    private var dirty = 0

    private val loading = Loading(io) { load() }

    private fun groupKey(layout: KeyLayout, mode: Mode) = layout.id + "/" + mode.name.lowercase(Locale.ROOT)

    /** How this user taps on [layout] in [mode], or null while nothing has been learned there. */
    fun shape(layout: KeyLayout, mode: Mode): Shape? {
        loading.await()
        val g = groups[groupKey(layout, mode)] ?: return null
        return g.shape ?: build(g, mode).also { g.shape = it }
    }

    /**
     * The user kept the word with letters-only [key], typed with [taps] on [layout] in [mode].
     * Each tap counts for its letter, unless it was picked exactly (zoom, a screen reader), which
     * says nothing about where the finger lands. Returns whether the word was used: not when it
     * has fewer than [MIN_TAPS] such taps, its letters don't match the taps one for one (a
     * completion, a correction that added or dropped a letter), or any tap lies further than
     * [OUTLIER] default spreads from where its letter is expected.
     */
    fun learn(layout: KeyLayout, mode: Mode, taps: List<Tap>, key: String): Boolean {
        loading.await()
        if (taps.size != key.length || key.any { it !in 'a'..'z' }) return false
        val now = shape(layout, mode)
        val used = ArrayList<Int>(taps.size)
        val r = Array(taps.size) { FloatArray(2) }
        for (i in taps.indices) {
            if (taps[i].exact >= 0) continue
            if (!residual(layout, mode, taps[i], key[i] - 'a', now, r[i])) return false
            used += i
        }
        if (used.size < MIN_TAPS) return false
        val group = groups.getOrPut(groupKey(layout, mode)) { Group() }
        for (i in used) {
            add(group, key[i] - 'a', r[i])
            if (mode == Mode.MINI) height(group, layout, key[i] - 'a', taps[i])
        }
        changed(group)
        return true
    }

    /**
     * The user deleted [tap] and typed [letter] in its place: it was meant for [letter]. Returns
     * whether it was used: not if it was picked exactly, or lies too far from [letter] (deleting
     * and typing something else, rather than fixing one letter).
     */
    fun learnTap(layout: KeyLayout, mode: Mode, tap: Tap, letter: Char): Boolean {
        loading.await()
        if (tap.exact >= 0 || letter !in 'a'..'z') return false
        val r = FloatArray(2)
        if (!residual(layout, mode, tap, letter - 'a', shape(layout, mode), r)) return false
        val group = groups.getOrPut(groupKey(layout, mode)) { Group() }
        add(group, letter - 'a', r)
        if (mode == Mode.MINI) height(group, layout, letter - 'a', tap)
        changed(group)
        return true
    }

    /** Mini mode: [tap], meant for letter [c], landed this high in [c]'s row. */
    private fun height(group: Group, layout: KeyLayout, c: Int, tap: Tap) {
        if (tap.y.isFinite()) group.rows[layout.rowOf[c]].add(tap.y)
    }

    /**
     * Where [tap] landed from letter [c]'s key centre, in default spreads, into [out]; false if it
     * is further than [OUTLIER] both from there and from where the letter is expected [now].
     */
    private fun residual(layout: KeyLayout, mode: Mode, tap: Tap, c: Int, now: Shape?, out: FloatArray): Boolean {
        val sx = Decoder.sigmaX(mode)
        val sy = Decoder.sigmaY(mode)
        val rx = (tap.x - centreX(layout, mode, c)) / sx
        val ry = if (mode == Mode.FULL) (tap.y - layout.fullY[c]) / sy else 0f
        out[0] = rx
        out[1] = ry
        val ex = rx - (now?.dx?.get(c) ?: 0f) / sx
        val ey = ry - (now?.dy?.get(c) ?: 0f) / sy
        val limit = OUTLIER * OUTLIER
        return ex * ex + ey * ey <= limit || rx * rx + ry * ry <= limit
    }

    private fun add(group: Group, c: Int, r: FloatArray) {
        group.letters[c].add(r[0], r[1], MAX_LETTER_N)
        group.all.add(r[0], r[1], MAX_ALL_N)
    }

    private fun changed(group: Group) {
        group.shape = null
        if (++dirty >= SAVE_EVERY) save()
    }

    /** Forgets everything learned (with the learned words). */
    fun clear() {
        loading.await()
        groups.clear()
        dirty = 0
        DiskIo.write(file, "", io)
    }

    /** Writes what changed since the last save. */
    fun save() {
        loading.await()
        if (dirty == 0) return
        dirty = 0
        val text = buildString {
            fun line(kind: String, name: String, letter: Char?, m: Mean) {
                append(kind).append('\t').append(name).append('\t')
                if (letter != null) append(letter).append('\t')
                append(m.n).append('\t').append(m.dx).append('\t').append(m.dy).append('\n')
            }
            for ((name, g) in groups) {
                line("a", name, null, g.all)
                g.letters.forEachIndexed { c, m -> if (m.n > 0f) line("l", name, 'a' + c, m) }
                g.rows.forEachIndexed { r, h ->
                    if (h.n > 0f) append("r\t").append(name).append('\t').append(r).append('\t')
                        .append(h.n).append('\t').append(h.mean).append('\t').append(h.variance).append('\n')
                }
            }
        }
        DiskIo.write(file, text, io)
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 6 && p[0] == "r") {
                    val r = p[2].toIntOrNull()?.takeIf { it in 0..2 } ?: return@forEachLine
                    val v = p.subList(3, 6).map { it.toFloatOrNull()?.takeIf { f -> f.isFinite() } ?: return@forEachLine }
                    groups.getOrPut(p[1]) { Group() }.rows[r].apply { n = v[0].coerceIn(0f, MAX_ROW_N); mean = v[1]; variance = maxOf(0f, v[2]) }
                    return@forEachLine
                }
                val (m, max, at) = when {
                    p.size == 5 && p[0] == "a" -> Triple(groups.getOrPut(p[1]) { Group() }.all, MAX_ALL_N, 2)
                    p.size == 6 && p[0] == "l" && p[2].length == 1 && p[2][0] in 'a'..'z' ->
                        Triple(groups.getOrPut(p[1]) { Group() }.letters[p[2][0] - 'a'], MAX_LETTER_N, 3)
                    else -> return@forEachLine
                }
                val n = p[at].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@forEachLine
                val dx = p[at + 1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@forEachLine
                val dy = p[at + 2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@forEachLine
                m.n = n.coerceIn(0f, max)
                m.dx = dx
                m.dy = dy
            }
        }
    }

    /**
     * The learned offsets as the decoder uses them: where taps land on the whole, weighed by how
     * many taps that rests on, plus each letter's own difference from it, weighed by the
     * letter's taps, within [MAX_OFFSET].
     */
    private fun build(g: Group, mode: Mode): Shape {
        val sx = Decoder.sigmaX(mode)
        val sy = Decoder.sigmaY(mode)
        val a = g.all
        val aw = a.n / (a.n + PRIOR_ALL)
        val gx = a.dx * aw
        val gy = a.dy * aw
        val dx = FloatArray(26)
        val dy = FloatArray(26)
        for (c in 0 until 26) {
            val l = g.letters[c]
            val w = l.n / (l.n + PRIOR_LETTER)
            dx[c] = (gx + (l.dx - gx) * w).coerceIn(-MAX_OFFSET, MAX_OFFSET) * sx
            dy[c] = if (mode == Mode.FULL) (gy + (l.dy - gy) * w).coerceIn(-MAX_OFFSET, MAX_OFFSET) * sy else 0f
        }
        if (mode != Mode.MINI) return Shape(dx, dy)
        // Each row's height, from the overall average height toward the row's own as its taps add up.
        val rows = g.rows
        val total = rows.sumOf { it.n.toDouble() }.toFloat()
        if (total < MIN_ROW_TAPS) return Shape(dx, dy)
        val all = rows.sumOf { (it.n * it.mean).toDouble() }.toFloat() / total
        val rowY = FloatArray(3) { r -> all + (rows[r].mean - all) * rows[r].n / (rows[r].n + PRIOR_ROW) }
        val pooled = sqrt(rows.sumOf { (it.n * it.variance).toDouble() }.toFloat() / total)
        return Shape(dx, dy, rowY, maxOf(MIN_ROW_SPREAD, pooled * ROW_SPREAD_FACTOR))
    }

    private fun centreX(layout: KeyLayout, mode: Mode, c: Int) =
        if (mode == Mode.MINI) layout.miniSlot[c].toFloat() else layout.fullX[c]

    companion object {
        /** Words with fewer taps that say where a finger landed aren't used. */
        const val MIN_TAPS = 2
        /**
         * A tap further than this many default spreads from its letter (from where it is expected
         * now and from the key's centre) means the word doesn't fit its taps.
         */
        const val OUTLIER = 2.2f
        /** Taps in all before the shared offset counts half; only about the latest [MAX_ALL_N] count. */
        const val PRIOR_ALL = 20f
        const val MAX_ALL_N = 200f
        /**
         * Taps of a letter before its own difference from the shared offset counts half; only about
         * the latest [MAX_LETTER_N] count. Tuned on simulated typists: a weaker prior (12) made
         * offsets noisy enough to cost a typist who hits the centres a point.
         */
        const val PRIOR_LETTER = 30f
        const val MAX_LETTER_N = 60f
        /** How far a letter may move, in default spreads (about one slot in mini mode, 0.4 keys in full). */
        const val MAX_OFFSET = 0.75f
        /**
         * Mini mode heights: taps of a row before its own average height counts half, about how many
         * latest taps count, and how many taps in all before heights are used. The scatter of the
         * taps that were learned from (words read right) is widened by [ROW_SPREAD_FACTOR], as those
         * are the taps that fit best, and is never taken below [MIN_ROW_SPREAD] rows.
         */
        const val PRIOR_ROW = 40f
        const val MAX_ROW_N = 400f
        const val MIN_ROW_TAPS = 10f
        const val ROW_SPREAD_FACTOR = 1.3f
        const val MIN_ROW_SPREAD = 0.5f
        /** Learned words between saves (and the keyboard saves when it closes). */
        private const val SAVE_EVERY = 10
    }
}

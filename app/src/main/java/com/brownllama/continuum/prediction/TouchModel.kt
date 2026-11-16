package com.brownllama.continuum.prediction

import com.brownllama.continuum.DiskIo
import com.brownllama.continuum.Loading
import com.brownllama.continuum.keyboard.KeyLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.sqrt

/**
 * Where this user's taps land: for each letter, how far from the key's centre they usually are,
 * and for the keyboard as a whole, how widely they scatter. The decoder ([Decoder.touch]) then
 * expects each letter where the user actually puts it, instead of at the centre of its key.
 *
 * It learns from words the user kept ([learn]): every tap of such a word was meant for its letter.
 * Words whose taps don't fit their letters (a typo that was corrected, a slip) are left out whole,
 * so a correction can't teach that a letter is somewhere it isn't. It changes slowly: a letter
 * needs [PRIOR_LETTER] taps before its offset counts half, only the last [MAX_LETTER_N] taps of
 * it matter, and offsets and spread stay within fixed bounds, so a few odd taps or a different
 * grip can't take the keyboard far from its defaults.
 *
 * Kept separately per layout and mode (a mini-mode slot and a full-mode key are different
 * things), in [file]: app-private, excluded from backups. With [io], the file is read and
 * written in the background.
 *
 * Internally offsets and residuals are in units of the decoder's default spread ([Decoder.sigmaX]),
 * so the same bounds apply to both modes; [Shape] gives them back in the layout's own units.
 */
class TouchModel(private val file: File, private val io: Executor? = null) {

    /**
     * What the decoder needs for one layout and mode: how far each letter's taps land from its
     * key's centre ([dx], [dy] by letter code, in slots or keys), and how much wider or tighter
     * than the default they scatter ([scaleX], [scaleY]).
     */
    class Shape(val dx: FloatArray, val dy: FloatArray, val scaleX: Float, val scaleY: Float)

    private class Letter {
        var n = 0f
        var dx = 0f
        var dy = 0f
    }

    /** Mean squared distance of taps from where they were expected, in default-spread units. */
    private class Spread {
        var n = 0f
        var vx = 1f
        var vy = 1f
    }

    private class Group {
        val letters = Array(26) { Letter() }
        val spread = Spread()
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
     * [OUTLIER] spreads from where its letter is expected.
     */
    fun learn(layout: KeyLayout, mode: Mode, taps: List<Tap>, key: String): Boolean {
        loading.await()
        if (taps.size != key.length || key.any { it !in 'a'..'z' }) return false
        val sx = Decoder.sigmaX(mode)
        val sy = Decoder.sigmaY(mode)
        val g = groups[groupKey(layout, mode)]
        val now = g?.let { it.shape ?: build(it, mode).also { s -> it.shape = s } }
        val used = ArrayList<Int>(taps.size)
        val rx = FloatArray(taps.size)
        val ry = FloatArray(taps.size)
        for (i in taps.indices) {
            val t = taps[i]
            if (t.exact >= 0) continue
            val c = key[i] - 'a'
            rx[i] = (t.x - centreX(layout, mode, c)) / sx
            ry[i] = if (mode == Mode.FULL) (t.y - layout.fullY[c]) / sy else 0f
            // How far it is from where the letter is expected now, in spreads.
            val ex = (rx[i] - (now?.dx?.get(c) ?: 0f) / sx) / (now?.scaleX ?: 1f)
            val ey = (ry[i] - (now?.dy?.get(c) ?: 0f) / sy) / (now?.scaleY ?: 1f)
            if (ex * ex + ey * ey > OUTLIER * OUTLIER) return false
            used += i
        }
        if (used.size < MIN_TAPS) return false
        val group = g ?: Group().also { groups[groupKey(layout, mode)] = it }
        val spread = group.spread
        for (i in used) {
            val c = key[i] - 'a'
            val l = group.letters[c]
            // The residual from the letter's current offset, for the spread.
            val ex = rx[i] - (now?.dx?.get(c) ?: 0f) / sx
            val ey = ry[i] - (now?.dy?.get(c) ?: 0f) / sy
            l.n = minOf(l.n + 1f, MAX_LETTER_N)
            l.dx += (rx[i] - l.dx) / l.n
            l.dy += (ry[i] - l.dy) / l.n
            spread.n = minOf(spread.n + 1f, MAX_SPREAD_N)
            spread.vx += (ex * ex - spread.vx) / spread.n
            if (mode == Mode.FULL) spread.vy += (ey * ey - spread.vy) / spread.n
        }
        group.shape = null
        if (++dirty >= SAVE_EVERY) save()
        return true
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
            for ((name, g) in groups) {
                append("s\t").append(name).append('\t').append(g.spread.n).append('\t')
                    .append(g.spread.vx).append('\t').append(g.spread.vy).append('\n')
                g.letters.forEachIndexed { c, l ->
                    if (l.n > 0f) {
                        append("l\t").append(name).append('\t').append('a' + c).append('\t').append(l.n).append('\t')
                            .append(l.dx).append('\t').append(l.dy).append('\n')
                    }
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
                when {
                    p.size == 5 && p[0] == "s" -> groups.getOrPut(p[1]) { Group() }.spread.apply {
                        n = p[2].toFloatOrNull()?.coerceIn(0f, MAX_SPREAD_N) ?: return@forEachLine
                        vx = p[3].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: 1f
                        vy = p[4].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: 1f
                    }
                    p.size == 6 && p[0] == "l" && p[2].length == 1 && p[2][0] in 'a'..'z' ->
                        groups.getOrPut(p[1]) { Group() }.letters[p[2][0] - 'a'].apply {
                            n = p[3].toFloatOrNull()?.coerceIn(0f, MAX_LETTER_N) ?: return@forEachLine
                            dx = p[4].toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
                            dy = p[5].toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
                        }
                }
            }
        }
    }

    /**
     * The learned numbers as the decoder uses them: each letter's offset weighed by how many of
     * its taps there are, the spread by how many taps there are in all (both starting from the
     * defaults), each within its bounds.
     */
    private fun build(g: Group, mode: Mode): Shape {
        val sx = Decoder.sigmaX(mode)
        val sy = Decoder.sigmaY(mode)
        val dx = FloatArray(26)
        val dy = FloatArray(26)
        for (c in 0 until 26) {
            val l = g.letters[c]
            val weight = l.n / (l.n + PRIOR_LETTER)
            dx[c] = (l.dx * weight).coerceIn(-MAX_OFFSET, MAX_OFFSET) * sx
            dy[c] = if (mode == Mode.FULL) (l.dy * weight).coerceIn(-MAX_OFFSET, MAX_OFFSET) * sy else 0f
        }
        val s = g.spread
        fun scale(v: Float): Float {
            // Taps beyond OUTLIER were never counted, which makes the spread look a little tighter.
            val blended = (s.n * v / TRUNCATION + PRIOR_SPREAD) / (s.n + PRIOR_SPREAD)
            return sqrt(blended).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        if (!EVAL_SPREAD) return Shape(dx, dy, 1f, 1f)
        return Shape(dx, dy, scale(s.vx), if (mode == Mode.FULL) scale(s.vy) else 1f)
    }

    private fun centreX(layout: KeyLayout, mode: Mode, c: Int) =
        if (mode == Mode.MINI) layout.miniSlot[c].toFloat() else layout.fullX[c]

    companion object {
        @JvmField internal var EVAL_SPREAD = true
        /** Words with fewer taps that say where a finger landed aren't used. */
        const val MIN_TAPS = 2
        /** A tap further than this many spreads from its letter means the word doesn't fit its taps. */
        const val OUTLIER = 2.2f
        /** Taps of a letter before its learned offset counts half. */
        const val PRIOR_LETTER = 12f
        /** Only about this many of a letter's latest taps count, so the offset follows a change of grip. */
        const val MAX_LETTER_N = 60f
        /** Taps in all before the learned spread counts half, and how many of the latest count. */
        const val PRIOR_SPREAD = 150f
        const val MAX_SPREAD_N = 600f
        /** Bounds, in default spreads: how far a letter may move, and how much tighter or wider taps may be. */
        const val MAX_OFFSET = 0.75f
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.25f
        /** The share of a normal spread's variance left once taps beyond [OUTLIER] are dropped. */
        private const val TRUNCATION = 0.87f
        /** Learned words between saves (and the keyboard saves when it closes). */
        private const val SAVE_EVERY = 10
    }
}

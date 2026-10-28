package com.brownllama.continuum.prediction

import com.brownllama.continuum.keyboard.KeyLayout
import kotlin.math.ln

/**
 * One touch on the letter area.
 * Mini mode: [x] is the continuous slot position (0..25) along the one-row layout, [y] unused.
 * Full mode: [x], [y] are in key units (see [KeyLayout.fullX]).
 * [exact] is a letter code 0..25 when the user picked it precisely (zoom / long-press), else -1.
 */
data class Tap(val x: Float, val y: Float, val exact: Int = -1, val upper: Boolean = false)

enum class Mode { MINI, FULL }

/**
 * A suggested word: [key] is letters-only lowercase, [display] how it's shown, [score] a log-probability.
 * [tentative]: a new word the user has typed only once (see [UserModel.isConfirmed]), offered but
 * never the default pick. [edited]: a typo correction (the taps spell it only with a missing,
 * extra, swapped or far-off letter).
 */
class Candidate(
    val key: String, val display: String, val score: Float, val emoji: String? = null, val tentative: Boolean = false,
    val edited: Boolean = false,
) {
    fun plus(bonus: Float) = Candidate(key, display, score + bonus, emoji, tentative, edited)
    fun shownAs(form: String) = Candidate(key, form, score, emoji, tentative, edited)
}

/** Everything [Decoder.decode] found for the taps so far. */
class DecodeResult(
    /**
     * Best whole-word matches for exactly as many letters as taps, best first (never a tentative one,
     * if there's another). A typo correction leads instead when it wins by a clear margin.
     */
    val words: List<Candidate>,
    /** Longer words whose beginning matches the taps. */
    val completions: List<Candidate>,
    /** The nearest letter for every tap ("what you literally hit"). */
    val literal: String,
    /** [literal] is in the dictionary or was typed by the user before. */
    val literalIsWord: Boolean,
    /** Score of [literal] on the same scale as the candidates, for autocorrect decisions. */
    val literalScore: Float,
    /** Words the taps spell with a typo ([Candidate.edited]), best first, for the suggestion bar. */
    val corrections: List<Candidate> = emptyList(),
)

/**
 * Probabilistic decoder: score(word) = Σ log P(tap_i | letter_i) + log P(word) (+ user boosts).
 * This is what lets the one-row layout work: each tap only says "somewhere around here",
 * and word frequency picks the most likely intended word.
 */
class Decoder(private val dict: Dictionary, private val user: UserModel) {

    /**
     * Ranks words for [taps]. [prev] is the previous word's key (for bigrams and next-word bonuses).
     * Dictionary words are scored by brute force over the words of the right length, pruned as soon
     * as a word can't beat the current top [limit]; the user's own words are then rescored on top.
     */
    fun decode(taps: List<Tap>, mode: Mode, layout: KeyLayout, prev: String?, limit: Int = 8): DecodeResult {
        val n = taps.size
        val table = Array(n) { i -> spatialRow(taps[i], mode, layout) }

        val literal = buildString { for (row in table) append('a' + argmax(row)) }
        val literalSpatial = (0 until n).sumOf { i -> table[i][literal[i] - 'a'].toDouble() }.toFloat()

        val words = TopK(limit)
        val completions = TopK(limit)
        val anyBlocked = user.anyBlocked

        if (n <= dict.maxLength) {
            for (idx in dict.ofLength(n)) {
                val lm = dict.logFreq[idx]
                val s = spatial(dict.letters, dict.offset(idx), table, n, words.floor() - lm)
                if (s != MISS && !(anyBlocked && user.isBlocked(dict.display(idx)))) words.offer(idx, s + lm)
            }
            val maxLen = minOf(dict.maxLength, n + MAX_COMPLETION_EXTRA)
            for (len in n + 1..maxLen) {
                val penalty = COMPLETION_PENALTY - COMPLETION_PER_LETTER * (len - n)
                for (idx in dict.ofLength(len)) {
                    val lm = dict.logFreq[idx] + penalty
                    val s = spatial(dict.letters, dict.offset(idx), table, n, completions.floor() - lm)
                    if (s != MISS && !(anyBlocked && user.isBlocked(dict.display(idx)))) completions.offer(idx, s + lm)
                }
            }
        }

        val out = words.toCandidates(dict)
        val comp = completions.toCandidates(dict)

        // Re-rank with what the user actually types (including words missing from the dictionary),
        // and with the word list's next-word model for the previous word.
        val boosted = ArrayList<Candidate>(out)
        val boostedComp = ArrayList<Candidate>(comp)
        val expected = prev?.let { dict.nextFor(it) }.orEmpty()

        // 1. Dictionary candidates the user has typed get their boost (only these few are looked at).
        for (list in listOf(boosted, boostedComp)) for (i in list.indices) {
            val c = list[i]
            val count = user.count(c.key)
            if (count == 0) continue
            val extra = c.key.length - n
            val di = dict.indexOf(c.key)
            // A word the user typed is at least as likely as a new word, however rare in the corpus.
            val lift = if (di >= 0) maxOf(0f, NEW_WORD_LOG_FREQ - dict.logFreq[di]) else 0f
            var b = c.plus(userBoost(count, user.bigramCount(prev, c.key)) + lift + userPenalty(extra) - dictPenalty(extra))
            // Show the word the way the user writes it ("tero"), not the dictionary's "Tero".
            val form = user.formOf(c.key)
            if (form != null && b.display.equals(form, ignoreCase = true)) b = b.shownAs(form)
            list[i] = b
        }

        // 2. The user's other words join only if they can still reach the top [limit]: the bound is
        // checked before any letters are scored, and scoring stops as soon as a word falls below it.
        // (Scores only ever rise while merging, so a word below the current floor stays out.)
        val inLists = HashSet<String>()
        for (c in boosted) inLists += c.key
        for (c in boostedComp) inLists += c.key
        val floors = arrayOf(Floor(limit, boosted), Floor(limit, boostedComp))
        val bonus = if (expected.isNotEmpty()) NEXT_WORD_BONUS else 0f
        for ((key, count) in user.unigrams) {
            if (key.length < n || key in inLists) continue
            val extra = key.length - n
            val floor = floors[if (extra == 0) 0 else 1]
            val w = userWord(key)
            val di = w.index
            val base = if (di >= 0) maxOf(dict.logFreq[di], NEW_WORD_LOG_FREQ) else NEW_WORD_LOG_FREQ
            // Words the user has typed are completed much more eagerly than dictionary words.
            val rest = base + userBoost(count, user.bigramCount(prev, key)) + userPenalty(extra)
            if (rest + bonus < floor.value) continue
            val s = spatial(w.codes, 0, table, n, floor.value - rest - bonus)
            if (s == MISS) continue
            val form = user.formOf(key)
            val display = form ?: dict.displayFor(key)
            if (user.isBlocked(display)) continue
            val tentative = di < 0 && !user.isConfirmed(key)
            val c = Candidate(key, display, s + rest, if (di >= 0) dict.emojiFor(di) else null, tentative)
            if (extra == 0) boosted += c else boostedComp += c
            floor.offer(c.score)
        }
        if (expected.isNotEmpty()) {
            for (list in listOf(boosted, boostedComp)) for (i in list.indices) {
                if (list[i].display in expected) list[i] = list[i].plus(NEXT_WORD_BONUS)
            }
        }
        boosted.sortByDescending { it.score }
        boostedComp.sortByDescending { it.score }
        // A candidate (new word typed once) is offered but never the default: the best real word
        // goes first, so space doesn't commit what may have been a typo.
        if (boosted.firstOrNull()?.tentative == true) {
            val i = boosted.indexOfFirst { !it.tentative }
            if (i > 0) boosted.add(0, boosted.removeAt(i))
        }

        val literalIdx = dict.indexOf(literal)
        val literalCount = user.count(literal)
        val literalIsWord = (literalIdx >= 0 && !dict.isMisspelling(literalIdx)) || literalCount > 0
        val literalLm = (if (literalIdx >= 0) dict.logFreq[literalIdx] else Dictionary.UNKNOWN_LOG_FREQ) +
            userBoost(literalCount, user.bigramCount(prev, literal))

        // Typos: only corrections that come close to the best reading without one (an exact match, or
        // a word still being typed) are worth finding. Letters picked exactly (zoom / long-press) are
        // spelled on purpose, so they aren't corrected.
        var top = boosted.take(limit)
        val unfinished = boostedComp.firstOrNull()?.score ?: MISS
        val fixes = if (n < MIN_CORRECTION_TAPS || taps.any { it.exact >= 0 }) emptyList() else {
            val gate = maxOf(top.firstOrNull()?.score ?: MISS, unfinished) - CORRECTION_WINDOW
            corrections(table, n, prev, gate, boosted.mapTo(HashSet()) { it.key }, expected, minOf(limit, MAX_CORRECTIONS))
        }
        // A correction becomes the default only when it clearly beats the best exact match (even a
        // tentative one), by more still if that's one of the user's own words (and simply by beating
        // a known misspelling), and the taps don't read better as the start of a longer word. Short
        // words need a wider margin, unless full mode's typed letters aren't a word at all ("teh").
        val fix = fixes.firstOrNull { !it.tentative }
        val first = top.maxByOrNull { it.score }
        val short = if (mode == Mode.FULL && !literalIsWord) 0f else SHORT_WORD_MARGIN * maxOf(0, SHORT_WORD_TAPS - n)
        if (fix != null && fix.score - unfinished > CORRECTION_MARGIN + short &&
            (first == null || fix.score - first.score > correctionMargin(first) + short)
        ) top = listOf(fix) + top.take(limit - 1)
        return DecodeResult(
            top, boostedComp.take(limit), literal, literalIsWord, literalSpatial + literalLm, fixes,
        )
    }

    /**
     * Words within [MAX_EDITS] edits of the taps (see [edited]) that score at least [gate], best
     * first: dictionary words of nearby lengths, then the user's words, boosted as in [decode].
     * Keys in [skip] are already offered as exact matches.
     */
    private fun corrections(
        table: Array<FloatArray>, n: Int, prev: String?, gate: Float, skip: Set<String>,
        expected: List<String>, limit: Int,
    ): List<Candidate> {
        val maxEdits = if (n >= TWO_EDIT_TAPS) 2 else 1
        val scratch = EditScratch(n, maxEdits, n >= SUBSTITUTION_TAPS)
        val anyBlocked = user.anyBlocked
        val found = TopK(limit)
        // Most words fail on frequency alone: even the cheapest edits leave them under the floor, and
        // so does every rarer word after them. Lengths nearest the taps go first: they need the
        // fewest edits, so they raise the floor soonest.
        for (d in 0..2 * maxEdits) {
            val len = n + if (d % 2 == 0) d / 2 else -(d + 1) / 2
            if (len < 1 || len > dict.maxLength) continue
            val minCost = minCost(len, n)
            for (idx in dict.ofLengthByFrequency(len)) {
                val lm = dict.logFreq[idx]
                val floor = maxOf(found.floor(), gate) - lm
                if (-minCost < floor) break
                if (table[0][dict.letters[dict.offset(idx)].toInt()] - minCost < floor) continue
                val s = edited(dict.letters, dict.offset(idx), len, table, n, floor, scratch)
                if (s == MISS || dict.isMisspelling(idx) || dict.key(idx) in skip) continue
                if (anyBlocked && user.isBlocked(dict.display(idx))) continue
                found.offer(idx, s + lm)
            }
        }
        val out = ArrayList<Candidate>()
        for (c in found.toCandidates(dict)) {
            val count = user.count(c.key)
            if (count == 0) { out += c.edit(); continue }
            val lift = maxOf(0f, NEW_WORD_LOG_FREQ - dict.logFreq[dict.indexOf(c.key)])
            var b = c.plus(userBoost(count, user.bigramCount(prev, c.key)) + lift)
            val form = user.formOf(c.key)
            if (form != null && b.display.equals(form, ignoreCase = true)) b = b.shownAs(form)
            out += b.edit()
        }

        // The user's words, with the same bound-first pruning as in [decode].
        val inOut = HashSet<String>()
        for (c in out) inOut += c.key
        val floor = Floor(limit, out)
        val bonus = if (expected.isNotEmpty()) NEXT_WORD_BONUS else 0f
        for ((key, count) in user.unigrams) {
            if (key.length < n - maxEdits || key.length > n + maxEdits || key in skip || key in inOut) continue
            val w = userWord(key)
            val di = w.index
            val rest = (if (di >= 0) maxOf(dict.logFreq[di], NEW_WORD_LOG_FREQ) else NEW_WORD_LOG_FREQ) +
                userBoost(count, user.bigramCount(prev, key))
            val min = maxOf(floor.value, gate) - rest - bonus
            if (table[0][w.codes[0].toInt()] - minCost(key.length, n) < min) continue
            val s = edited(w.codes, 0, key.length, table, n, min, scratch)
            if (s == MISS) continue
            val display = user.formOf(key) ?: dict.displayFor(key)
            if (user.isBlocked(display)) continue
            val tentative = di < 0 && !user.isConfirmed(key)
            out += Candidate(key, display, s + rest, if (di >= 0) dict.emojiFor(di) else null, tentative, edited = true)
            floor.offer(s + rest)
        }
        if (expected.isNotEmpty()) for (i in out.indices) if (out[i].display in expected) out[i] = out[i].plus(NEXT_WORD_BONUS)
        out.sortByDescending { it.score }
        return out.take(limit)
    }

    /** The least a [len]-letter word can cost in edits against [n] taps. */
    private fun minCost(len: Int, n: Int) = if (len == n) MIN_EDIT_COST else kotlin.math.abs(len - n) * MIN_INDEL_COST

    private fun correctionMargin(exact: Candidate): Float = when {
        user.count(exact.key) > 0 -> LEARNED_WORD_MARGIN
        dict.indexOf(exact.key).let { it >= 0 && dict.isMisspelling(it) } -> 0f
        else -> CORRECTION_MARGIN
    }

    private fun Candidate.edit() = Candidate(key, display, score, emoji, tentative, edited = true)

    /** Three columns of the edit DP ([edited]), reused for every word of one decode. */
    private class EditScratch(val n: Int, val maxEdits: Int, val substitute: Boolean) {
        val size = (n + 1) * (maxEdits + 1)
        var older = FloatArray(size)
        var prev = FloatArray(size)
        var cur = FloatArray(size)
    }

    /**
     * Best score of the word at [codes] from [offset] ([len] letters, the first matched to the first
     * tap) against the taps with at least one edit (at most [EditScratch.maxEdits]): Σ spatial log-likelihood of the letters matched to
     * taps, minus a cost per edit. The edits are a letter with no tap before a later one
     * ([OMISSION_COST], less for a doubled letter), a tap with no letter ([INSERTION_COST], less for a repeat of the letter just
     * typed), two neighbouring letters tapped the other way round ([TRANSPOSITION_COST] plus both
     * swapped spatial terms) and a tap anywhere for a letter ([SUBSTITUTION_COST], for a letter far
     * from where it was tapped). [MISS] if the word falls below [floor] (checked after each letter,
     * as every step only lowers the score) or reads at least as well with no edit at all.
     */
    private fun edited(
        codes: ByteArray, offset: Int, len: Int, table: Array<FloatArray>, n: Int, floor: Float, x: EditScratch,
    ): Float {
        val maxEdits = x.maxEdits
        val stride = maxEdits + 1
        // Cell i * stride + e: best score having used i taps, the letters so far and e edits. Only
        // the band |i - letters| <= maxEdits is ever read or written.
        var older = x.older
        var prev = x.prev
        var cur = x.cur
        // The first letter is taken as typed (first-letter typos are rare, and allowing them lets
        // almost any word through): it must match the first tap.
        older.fill(MISS)
        prev.fill(MISS)
        prev[stride] = table[0][codes[offset].toInt()]
        var prevAlive = true
        for (j in 1 until len) {
            val lo = maxOf(0, j + 1 - maxEdits)
            val hi = minOf(n, j + 1 + maxEdits)
            cur.fill(MISS, lo * stride, (hi + 1) * stride)
            val c = codes[offset + j].toInt()
            if (prevAlive) {
                val omit = if (codes[offset + j - 1].toInt() == c) DOUBLE_OMISSION_COST else OMISSION_COST
                for (i in maxOf(0, j - maxEdits)..minOf(n, j + maxEdits)) for (e in 0..maxEdits) {
                    val v = prev[i * stride + e]
                    if (v == MISS) continue
                    val at = i * stride + e
                    if (i < n) {
                        // Letters after the last tap aren't missing, just not typed yet (completions).
                        if (e < maxEdits) cur[at + 1] = maxOf(cur[at + 1], v - omit)
                        cur[at + stride] = maxOf(cur[at + stride], v + table[i][c])
                        if (e < maxEdits && x.substitute) cur[at + stride + 1] = maxOf(cur[at + stride + 1], v - SUBSTITUTION_COST)
                    }
                }
            }
            if (j > 1) {
                val b = codes[offset + j - 1].toInt()
                if (b != c) for (i in maxOf(0, j - 1 - maxEdits)..minOf(n - 2, j - 1 + maxEdits)) for (e in 0 until maxEdits) {
                    val v = older[i * stride + e]
                    if (v == MISS) continue
                    val to = (i + 2) * stride + e + 1
                    cur[to] = maxOf(cur[to], v + table[i][c] + table[i + 1][b] - TRANSPOSITION_COST)
                }
            }
            for (i in maxOf(1, lo)..hi) {
                val extra = maxOf(-INSERTION_COST, table[i - 1][c] - REPEAT_INSERTION_COST)
                for (e in 1..maxEdits) {
                    val v = cur[(i - 1) * stride + e - 1]
                    if (v != MISS) cur[i * stride + e] = maxOf(cur[i * stride + e], v + extra)
                }
            }
            // Drop cells that can't reach the floor: the score only falls from here, a mismatch
            // between taps and letters left needs that many more insertions / omissions, and a word
            // read without any edit still needs one.
            var alive = false
            val lettersLeft = len - j - 1
            for (i in lo..hi) {
                val gap = kotlin.math.abs((n - i) - lettersLeft)
                for (e in 0..maxEdits) {
                    val k = i * stride + e
                    val v = cur[k]
                    if (v == MISS) continue
                    val need = if (gap == 0 && e == 0) MIN_EDIT_COST else gap * MIN_INDEL_COST
                    if (e + gap > maxEdits || v - need < floor) cur[k] = MISS else alive = true
                }
            }
            if (!alive && !prevAlive) return MISS
            prevAlive = alive
            val t = older; older = prev; prev = cur; cur = t
        }
        var best = MISS
        for (e in 1..maxEdits) best = maxOf(best, prev[n * stride + e])
        return if (best < floor || best <= prev[n * stride]) MISS else best
    }

    /** Next-word suggestions when nothing is being typed. */
    fun predict(prev: String?, sentenceStart: Boolean, limit: Int = 3): List<String> {
        val out = ArrayList<String>()
        for (k in user.following(prev, limit)) out += user.formOf(k) ?: dict.displayFor(k)
        val model = if (sentenceStart || prev == null) dict.startWords else dict.nextFor(prev)
        val defaults = if (sentenceStart || prev == null) START_WORDS else FOLLOW_WORDS
        val frequent = user.mostUsed(limit).map { user.formOf(it) ?: dict.displayFor(it) }
        for (d in model + defaults + frequent) if (out.size < limit && out.none { it.equals(d, ignoreCase = true) }) out += d
        out.removeAll { user.isBlocked(it) }
        return out
    }

    /** Log-likelihood of each letter a..z given one tap (a Gaussian around each letter's position). */
    private fun spatialRow(tap: Tap, mode: Mode, layout: KeyLayout): FloatArray {
        val row = FloatArray(26)
        if (tap.exact >= 0) {
            for (c in 0 until 26) row[c] = if (c == tap.exact) 0f else EXACT_MISS
            return row
        }
        when (mode) {
            Mode.MINI -> for (c in 0 until 26) {
                val d = tap.x - layout.miniSlot[c]
                row[c] = -(d * d) / (2 * MINI_SIGMA * MINI_SIGMA)
            }
            Mode.FULL -> for (c in 0 until 26) {
                val dx = (tap.x - layout.fullX[c]) / FULL_SIGMA_X
                val dy = (tap.y - layout.fullY[c]) / FULL_SIGMA_Y
                row[c] = -(dx * dx + dy * dy) / 2
            }
        }
        return row
    }

    /**
     * Sum of spatial log-likelihoods for the first [n] letter codes at [codes] from [offset], or
     * [MISS] once it drops below [floor].
     */
    private fun spatial(codes: ByteArray, offset: Int, table: Array<FloatArray>, n: Int, floor: Float): Float {
        var s = 0f
        for (i in 0 until n) {
            s += table[i][codes[offset + i].toInt()]
            if (s < floor) return MISS
        }
        return s
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        for (c in 1 until 26) if (row[c] > row[best]) best = c
        return best
    }

    private fun userBoost(count: Int, bigram: Int): Float =
        USER_WEIGHT * ln(1f + count) + BIGRAM_WEIGHT * ln(1f + bigram)

    /** Log penalty for completing a dictionary word / a user's word [extra] letters past the taps. */
    private fun dictPenalty(extra: Int) = if (extra == 0) 0f else COMPLETION_PENALTY - COMPLETION_PER_LETTER * extra
    private fun userPenalty(extra: Int) = if (extra == 0) 0f else USER_COMPLETION_PENALTY - USER_COMPLETION_PER_LETTER * extra

    /** A user's word's letter codes and dictionary entry (-1 if none), worked out once, not on every tap. */
    private class UserWord(val codes: ByteArray, val index: Int)
    private val userWords = HashMap<String, UserWord>()

    private fun userWord(key: String) = userWords.getOrPut(key) {
        UserWord(ByteArray(key.length) { (key[it] - 'a').toByte() }, dict.indexOf(key))
    }

    /** The [k]-th best score so far in a candidate list (a min-heap of the best [k]); -∞ until it holds k. */
    private class Floor(private val k: Int, initial: List<Candidate>) {
        private val heap = java.util.PriorityQueue<Float>(k + 1)

        init {
            for (c in initial) offer(c.score)
        }

        val value: Float get() = if (heap.size < k) Float.NEGATIVE_INFINITY else heap.peek()!!

        fun offer(score: Float) {
            if (heap.size < k) heap.add(score)
            else if (score > heap.peek()!!) {
                heap.poll()
                heap.add(score)
            }
        }
    }

    /** Fixed-size best-k list of dictionary indices. */
    private class TopK(val k: Int) {
        val idx = IntArray(k)
        val score = FloatArray(k)
        var size = 0

        fun floor(): Float = if (size < k) Float.NEGATIVE_INFINITY else score[size - 1]

        fun offer(i: Int, s: Float) {
            if (size == k && s <= score[size - 1]) return
            var p = if (size < k) size++ else k - 1
            while (p > 0 && score[p - 1] < s) {
                idx[p] = idx[p - 1]; score[p] = score[p - 1]; p--
            }
            idx[p] = i; score[p] = s
        }

        fun toCandidates(dict: Dictionary) = (0 until size).map {
            Candidate(dict.key(idx[it]), dict.display(idx[it]), score[it], dict.emojiFor(idx[it]))
        }
    }

    companion object {
        private const val MISS = Float.NEGATIVE_INFINITY

        /** Std-dev of a tap around a letter, in mini slots. ~1.4 slots ≈ "this letter or its neighbours". */
        private const val MINI_SIGMA = 1.4f
        /** Std-dev of a full-mode tap around a key centre, in key widths / row heights. */
        private const val FULL_SIGMA_X = 0.55f
        private const val FULL_SIGMA_Y = 0.6f
        /** Log-likelihood of any other letter for a tap picked exactly (zoom / long-press). */
        private const val EXACT_MISS = -14f

        /** Completions are words up to this many letters longer than what's been typed. */
        private const val MAX_COMPLETION_EXTRA = 8
        /** Log penalty for offering a longer word: a flat part plus a part per missing letter. */
        private const val COMPLETION_PENALTY = -2.0f
        private const val COMPLETION_PER_LETTER = 0.25f
        /** Weights of ln(1 + times typed) and ln(1 + times typed after the previous word). */
        private const val USER_WEIGHT = 1.0f
        private const val BIGRAM_WEIGHT = 1.5f
        /** Bonus for words the next-word model expects after the previous word. */
        private const val NEXT_WORD_BONUS = 1.5f
        /**
         * Starting score for words the user typed that the dictionary lacks: as likely as a common
         * word (about the 1,500th most frequent), so a word typed once on purpose comes back even
         * from short, sloppy mini-mode taps.
         */
        private const val NEW_WORD_LOG_FREQ = -7.5f
        /** Like [COMPLETION_PENALTY], but much milder for words the user has typed. */
        private const val USER_COMPLETION_PENALTY = -0.8f
        private const val USER_COMPLETION_PER_LETTER = 0.1f

        /**
         * Typo costs (log scale), in the proportions of AOSP LatinIME's (substitution 0.38,
         * omission 0.47, doubled-letter omission 0.35, transposition 0.56, insertion 0.72), with
         * one omission about as unlikely as a tap two neighbours off in full mode.
         */
        private const val SUBSTITUTION_COST = 3.3f
        private const val OMISSION_COST = 4.0f
        private const val DOUBLE_OMISSION_COST = 3.0f
        private const val TRANSPOSITION_COST = 4.8f
        private const val INSERTION_COST = 6.2f
        /** A tap with no letter that lands on the letter just typed ("helllo"). */
        private const val REPEAT_INSERTION_COST = 4.0f
        private const val MIN_EDIT_COST = 3.0f
        private const val MIN_INDEL_COST = 3.0f
        /**
         * Words this short aren't corrected; a far-off letter is only corrected from
         * [SUBSTITUTION_TAPS] taps (in a short word it leaves too few letters to go by), and from
         * [TWO_EDIT_TAPS] taps two edits are allowed.
         */
        private const val MIN_CORRECTION_TAPS = 3
        private const val SUBSTITUTION_TAPS = 6
        private const val TWO_EDIT_TAPS = 7
        /** Corrections are offered when they score at most this far below the best exact match. */
        private const val CORRECTION_WINDOW = 1.5f
        /** How much a correction must beat the best exact match (a dictionary / the user's word) to lead. */
        private const val CORRECTION_MARGIN = 2.5f
        private const val LEARNED_WORD_MARGIN = 5f
        /** Corrections kept for the bar (it shows about three at a time). */
        private const val MAX_CORRECTIONS = 4
        /** Extra margin per tap under [SHORT_WORD_TAPS]: a short word's taps fit many words. */
        private const val SHORT_WORD_MARGIN = 1.5f
        private const val SHORT_WORD_TAPS = 5

        /** Fallback predictions when the model has none (sentence start / after a word). */
        private val START_WORDS = listOf("I", "The", "In", "It", "What")
        private val FOLLOW_WORDS = listOf("the", "to", "and", "a", "is")
    }
}

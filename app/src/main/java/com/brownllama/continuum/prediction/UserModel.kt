package com.brownllama.continuum.prediction

import com.brownllama.continuum.Loading
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Words and word pairs the user has typed, persisted as a small TSV file.
 * Used to boost familiar words in the decoder and for next-word predictions.
 * Also holds words the user removed from suggestions ([blocked], by lowercase display form), and
 * the user's own spelling of each learned word ([formOf]: "tero" even if the dictionary says "Tero").
 *
 * The vocabulary stays bounded ([compact]): it keeps at most [MAX_WORDS] words and [MAX_PAIRS]
 * word pairs, dropping the least recently used first, and forgets words not used for
 * [FORGET_DAYS] days. A word typed only once is a *candidate* ([isConfirmed] false): it's offered,
 * but not as the default pick, and it fades after [CANDIDATE_DAYS] days unless used again.
 * [today] is the clock in days, replaceable for tests.
 *
 * With [background] the file is read on the writer thread (the keyboard does this, so the service
 * starts without waiting for it); the first use then waits for whatever is left of the read.
 */
class UserModel(
    private val file: File,
    private val today: () -> Int = { (System.currentTimeMillis() / DAY_MS).toInt() },
    background: Boolean = false,
) {
    /** How often each word was typed, by key. */
    private val words = HashMap<String, Int>()
    val unigrams: HashMap<String, Int>
        get() {
            loading.await()
            return words
        }
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val blocked = HashSet<String>()
    private val forms = HashMap<String, String>()
    /** Day (since 1970) each word was last typed. */
    private val lastUsed = HashMap<String, Int>()
    private var dirty = 0
    /** The last write handed to [WRITER], so [flush] can wait for it. */
    private var pending: Future<*>? = null

    /**
     * The read of [file]. Everything that touches the tables above waits for it first
     * ([Loading.await]); the private helpers it runs itself (load, prune, remove) never do.
     */
    private val loading = Loading(if (background) WRITER else null) {
        load()
        if (prune()) dirty++
    }

    fun count(key: String): Int {
        loading.await()
        return words[key] ?: 0
    }

    fun bigramCount(prev: String?, key: String): Int {
        if (prev == null) return 0
        loading.await()
        return bigrams[prev]?.get(key) ?: 0
    }

    /** How the user writes the word with this key, if they have typed it. */
    fun formOf(key: String): String? {
        loading.await()
        return forms[key]
    }

    /** Typed twice, or picked from the suggestion bar: a word of the user's own, not just a candidate. */
    fun isConfirmed(key: String): Boolean = count(key) >= CONFIRMED_COUNT

    fun learn(prev: String?, key: String, form: String? = null) {
        loading.await()
        if (form != null && Dictionary.keyOf(form) == key) forms[key] = form
        val count = (words[key] ?: 0) + 1
        words[key] = count
        lastUsed[key] = today()
        if (prev != null) {
            val m = bigrams.getOrPut(prev) { HashMap() }
            m[key] = (m[key] ?: 0) + 1
        }
        if (words.size > MAX_WORDS * COMPACT_AT || pairCount() > MAX_PAIRS * COMPACT_AT) prune()
        // A brand-new word is saved right away so it survives the keyboard being killed or reinstalled.
        if (++dirty >= 10 || count == 1) save()
    }

    /** The user picked the word from the suggestion bar: that's as good as typing it a second time. */
    fun confirm(key: String) {
        loading.await()
        val c = words[key] ?: return
        if (c < CONFIRMED_COUNT) {
            words[key] = CONFIRMED_COUNT
            dirty++
        }
    }

    fun isBlocked(display: String): Boolean {
        loading.await()
        return blocked.isNotEmpty() && display.lowercase() in blocked
    }

    /** Whether any word is blocked at all (lets the decoder skip building display forms). */
    val anyBlocked: Boolean
        get() {
            loading.await()
            return blocked.isNotEmpty()
        }

    /** Removes a word from suggestions: forgets what was learned about it and hides it from now on. */
    fun forget(key: String?, display: String) {
        loading.await()
        if (key != null) remove(setOf(key))
        blocked += display.lowercase()
        dirty++
        save()
    }

    /** The user typed [display] on purpose, so it may be suggested again. */
    fun unblock(display: String) {
        loading.await()
        if (blocked.remove(display.lowercase())) dirty++
    }

    /** Undoes one [learn] (e.g. a wrong autocorrection the user went back to fix). */
    fun unlearn(prev: String?, key: String) {
        loading.await()
        val c = words[key] ?: return
        if (c <= 1) {
            words.remove(key)
            lastUsed.remove(key)
        } else {
            words[key] = c - 1
        }
        if (prev != null) {
            val m = bigrams[prev]
            val b = m?.get(key)
            if (m != null && b != null) {
                if (b <= 1) m.remove(key) else m[key] = b - 1
                if (m.isEmpty()) bigrams.remove(prev)
            }
        }
        dirty++
    }

    /** Most frequent words the user typed after [prev]. */
    fun following(prev: String?, limit: Int): List<String> {
        loading.await()
        val m = prev?.let { bigrams[it] } ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /**
     * The [limit] words typed most often, most first. One pass with a short sorted list rather than
     * sorting every word: this runs after each committed word.
     */
    fun mostUsed(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        loading.await()
        val top = ArrayList<Map.Entry<String, Int>>(limit + 1)
        for (e in words.entries) {
            if (top.size == limit && e.value <= top[limit - 1].value) continue
            var i = top.size
            while (i > 0 && top[i - 1].value < e.value) i--
            top.add(i, e)
            if (top.size > limit) top.removeAt(limit)
        }
        return top.map { it.key }
    }

    fun clear() {
        loading.await()
        words.clear()
        bigrams.clear()
        blocked.clear()
        forms.clear()
        lastUsed.clear()
        dirty = 0
        // After any write still queued, so that can't bring the words back.
        pending = WRITER.submit { file.delete() }
    }

    /**
     * Keeps the vocabulary bounded. Forgets words unused for [FORGET_DAYS] days (candidates after
     * [CANDIDATE_DAYS]), then, over [MAX_WORDS], the least recently used words (fewest uses first
     * on the same day), and, over [MAX_PAIRS], the rarest word pairs. Returns whether anything went.
     */
    internal fun compact(): Boolean {
        loading.await()
        return prune()
    }

    /** [compact] without waiting for the file: it is also part of reading it. */
    private fun prune(): Boolean {
        val now = today()
        val gone = words.keys.filter { k ->
            val idle = now - (lastUsed[k] ?: now)
            idle > FORGET_DAYS || idle > CANDIDATE_DAYS && (words[k] ?: 0) < CONFIRMED_COUNT
        }.toMutableList()
        val left = words.size - gone.size
        if (left > MAX_WORDS) {
            val goneSet = gone.toHashSet()
            gone += words.keys.asSequence().filter { it !in goneSet }
                .sortedWith(compareBy<String>({ lastUsed[it] ?: now }, { words[it] ?: 0 }))
                .take(left - MAX_WORDS)
        }
        remove(gone.toHashSet())
        var pruned = gone.isNotEmpty()
        val pairs = pairCount()
        if (pairs > MAX_PAIRS) {
            val all = ArrayList<Triple<String, String, Int>>(pairs)
            for ((p, m) in bigrams) for ((k, c) in m) all += Triple(p, k, c)
            all.sortWith(compareBy({ it.third }, { lastUsed[it.second] ?: now }))
            for (i in 0 until pairs - MAX_PAIRS) {
                val (p, k, _) = all[i]
                val m = bigrams[p] ?: continue
                m.remove(k)
                if (m.isEmpty()) bigrams.remove(p)
            }
            pruned = true
        }
        return pruned
    }

    private fun pairCount() = bigrams.values.sumOf { it.size }

    /** Drops words and every pair they are part of (one pass over the pairs). */
    private fun remove(keys: Set<String>) {
        if (keys.isEmpty()) return
        for (k in keys) {
            words.remove(k)
            forms.remove(k)
            lastUsed.remove(k)
            bigrams.remove(k)
        }
        val empty = ArrayList<String>()
        for ((p, m) in bigrams) if (m.keys.removeAll(keys) && m.isEmpty()) empty += p
        empty.forEach(bigrams::remove)
    }

    /**
     * Saves in the background: the tables are copied here (a few ms) and written to disk on
     * [WRITER], so typing never waits for the file. Writes run in order, each via a temporary file.
     */
    fun save() {
        loading.await()
        if (dirty == 0) return
        dirty = 0
        val words = HashMap(this.words)
        val pairs = HashMap<String, HashMap<String, Int>>(bigrams.size * 2)
        for ((p, m) in bigrams) pairs[p] = HashMap(m)
        val hidden = HashSet(blocked)
        val spellings = HashMap(forms)
        val used = HashMap(lastUsed)
        val day = today()
        pending = WRITER.submit { write(words, pairs, hidden, spellings, used, day) }
    }

    /** Saves and waits for the write to finish (when the keyboard service is going away). */
    fun flush() {
        save()
        runCatching { pending?.get(FLUSH_TIMEOUT_S, TimeUnit.SECONDS) }
    }

    private fun write(
        words: Map<String, Int>, pairs: Map<String, Map<String, Int>>, hidden: Set<String>,
        spellings: Map<String, String>, used: Map<String, Int>, day: Int,
    ) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, c) in words) {
                    w.append("u\t").append(k).append('\t').append(c.toString()).append('\t')
                        .append((used[k] ?: day).toString()).append('\n')
                }
                for ((p, m) in pairs) for ((k, c) in m) {
                    w.append("b\t").append(p).append('\t').append(k).append('\t').append(c.toString()).append('\n')
                }
                for (d in hidden) w.append("x\t").append(d).append('\n')
                for ((k, f) in spellings) w.append("f\t").append(k).append('\t').append(f).append('\n')
            }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val now = today()
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 4 && p[0] == "u" -> {
                        words[p[1]] = p[2].toIntOrNull() ?: 0
                        lastUsed[p[1]] = p[3].toIntOrNull() ?: now
                    }
                    // Files from before candidates and last-used days: those words were learned in
                    // full, so they stay confirmed, and their idle time counts from today.
                    p.size == 3 && p[0] == "u" -> {
                        words[p[1]] = maxOf(p[2].toIntOrNull() ?: 0, CONFIRMED_COUNT)
                        lastUsed[p[1]] = now
                        dirty++
                    }
                    p.size == 2 && p[0] == "x" -> blocked += p[1]
                    p.size == 3 && p[0] == "f" -> forms[p[1]] = p[2]
                    p.size == 4 && p[0] == "b" -> bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 0
                }
            }
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val FLUSH_TIMEOUT_S = 2L
        /** One background thread for every save, so writes never overlap or run out of order. */
        private val WRITER = Executors.newSingleThreadExecutor { r -> Thread(r, "user-model").apply { isDaemon = true } }
        /** Uses after which a word is the user's own rather than a candidate (see [isConfirmed]). */
        const val CONFIRMED_COUNT = 2
        /** Caps, as in AOSP's keyboard; pruning starts once a table is [COMPACT_AT] times over. */
        const val MAX_WORDS = 10_000
        const val MAX_PAIRS = 30_000
        private const val COMPACT_AT = 1.2
        /** Unused words are forgotten after this many days; candidates sooner. */
        const val FORGET_DAYS = 300
        const val CANDIDATE_DAYS = 60
    }
}

package com.brownllama.continuum.prediction

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Word list with log-frequencies, bucketed by key length.
 *
 * Every entry has a letters-only lowercase key ("dont", "selfawareness") that taps are decoded
 * against, and a display form ("don't", "self-awareness", "I'm", "Toronto"). Several entries may
 * share a key ("its" / "it's"); they are all decoded and offered as suggestions.
 *
 * Two sources:
 *  - `assets/private_vocab.tsv`, an optional local word list (not in the repository): "word\tcount\tflags\tnext
 *    words", flags 1 = slang / offensive, next words "|"-separated. The row "<s>" lists words that
 *    start a sentence.
 *  - `assets/words.txt` (open FrequencyWords list) otherwise: "word count".
 *
 * Either way, each word's emoji comes from `assets/emoji_keywords.tsv` (CLDR annotations).
 *
 * With the open list, which words follow which comes from `assets/next_words.tsv` (Tatoeba's
 * everyday sentences mixed with Google Books Ngram word pairs by tools/next_words/build.py, see
 * THIRD_PARTY_NOTICES.md): for a previous word, its likeliest
 * next words with ln(lift), how many times likelier each is after it than in general, and a
 * backoff, ln(alpha) ≤ 0, for every word not listed. [context] serves it to the decoder.
 *
 * Both corpora contain common misspellings ("recieve", "definately"). Those listed in
 * `assets/misspellings.txt` (from Wikipedia) whose correction is
 * in the vocabulary are kept typeable but marked [isMisspelling] and made much rarer, so the decoder
 * corrects them instead of protecting them as real words.
 *
 * Memory: the ~111k entries live in a handful of primitive arrays rather than objects per word
 * (about a quarter of the heap). Keys are letter codes 0..25 back to back in [letters], entry i
 * spanning `offset(i) until offset(i + 1)`; a display form is stored only where it differs from
 * the key, and next words are entry indices. [load] keeps a binary copy ([toBytes]) so later
 * starts skip parsing the text.
 */
class Dictionary private constructor(
    /** Every key's letter codes (0..25), back to back. */
    val letters: ByteArray,
    private val start: IntArray,
    val logFreq: FloatArray,
    /** Display forms that differ from the key: entry indices (ascending) and their forms. */
    private val displayIdx: IntArray,
    private val displayText: Array<String>,
    /**
     * Next words: entry i's are [nextIds] `nextStart[i] until nextStart[i + 1]`, each an entry index,
     * or `-1 - j` for [nextExtra] j (punctuation like "." that isn't an entry).
     */
    private val nextStart: IntArray,
    private val nextIds: IntArray,
    private val nextExtra: Array<String>,
    /** Per next word ([nextIds]' order): ln(lift) × [LIFT_SCALE], 0..255. */
    private val nextLift: ByteArray,
    /** Entries with a next-word backoff (ascending), and that ln(backoff) for words not listed after them. */
    private val ctxIdx: IntArray,
    private val ctxBackoff: FloatArray,
    /** The same at the start of a sentence: next words (entry indices), their lifts, the backoff. */
    private val startIds: IntArray,
    private val startLift: ByteArray,
    private val startBackoff: Float,
    private val emojiIdx: IntArray,
    private val emojiText: Array<String>,
    /** Entries that are known misspellings of another entry (ascending). */
    private val misspelledIdx: IntArray,
    /** Words that start a sentence (the word list's "<s>" row). */
    val startWords: List<String>,
) {
    val size: Int get() = logFreq.size

    /** The language of the word list; English unless [load] says otherwise. */
    var language: Language = Language.EN

    private val byLength: Array<IntArray>
    /** [byLength] with each bucket most frequent first, for scans that can stop at the first rare word. */
    private val byFrequency: Array<IntArray>

    /** Open-addressing hash of keys → most frequent entry + 1 (0 = empty). */
    private val table: IntArray
    private val mask: Int
    /** Entries sharing a key with a more frequent one ("it's" with "its"), by that one. */
    private val siblings = HashMap<Int, IntArray>()
    /** Entries shown with an apostrophe (contractions): the next-word model gives them no backoff. */
    private val apostrophes: IntArray =
        displayIdx.indices.filter { '\'' in displayText[it] || '’' in displayText[it] }.map { displayIdx[it] }.toIntArray()

    init {
        var maxLen = 0
        for (i in 0 until size) maxLen = maxOf(maxLen, length(i))
        val counts = IntArray(maxLen + 1)
        for (i in 0 until size) counts[length(i)]++
        val buckets = Array(maxLen + 1) { IntArray(counts[it]) }
        val fill = IntArray(maxLen + 1)
        for (i in 0 until size) length(i).let { buckets[it][fill[it]++] = i }
        byLength = buckets
        byFrequency = Array(buckets.size) { b ->
            // Sort (−logFreq bits, index) pairs: for non-negative floats the bits order like the values.
            val packed = LongArray(buckets[b].size) {
                val i = buckets[b][it]
                (java.lang.Float.floatToIntBits(-logFreq[i] + 0f).toLong() shl 32) or i.toLong()
            }
            packed.sort()
            IntArray(packed.size) { packed[it].toInt() }
        }

        var cap = 1
        while (cap < size * 2) cap = cap shl 1
        table = IntArray(cap)
        mask = cap - 1
        for (i in 0 until size) {
            var slot = hashOf(i) and mask
            while (true) {
                val cur = table[slot] - 1
                if (cur < 0) { table[slot] = i + 1; break }
                if (sameKey(cur, i)) {
                    val (main, other) = if (logFreq[i] > logFreq[cur]) i to cur else cur to i
                    table[slot] = main + 1
                    // The other one stays reachable from the entry the table now names.
                    val list = (siblings.remove(cur) ?: IntArray(0)) + other
                    siblings[main] = list.filter { it != main }.toIntArray()
                    break
                }
                slot = (slot + 1) and mask
            }
        }
    }

    val maxLength: Int get() = byLength.size - 1

    /** The most frequent word's log-frequency: the best any word can score before its taps count. */
    val maxLogFreq: Float = if (size == 0) 0f else logFreq.max()

    fun ofLength(n: Int): IntArray = if (n in byLength.indices) byLength[n] else EMPTY

    /** Like [ofLength], most frequent first. */
    fun ofLengthByFrequency(n: Int): IntArray = if (n in byFrequency.indices) byFrequency[n] else EMPTY

    /** Where entry [i]'s letter codes start in [letters]; they run for [length] codes. */
    fun offset(i: Int) = start[i]

    fun length(i: Int) = start[i + 1] - start[i]

    fun key(i: Int): String {
        val o = start[i]
        return String(CharArray(length(i)) { 'a' + letters[o + it].toInt() })
    }

    fun display(i: Int): String = sparse(displayIdx, displayText, i) ?: key(i)

    fun emojiFor(i: Int): String? = sparse(emojiIdx, emojiText, i)

    /** Entry [i] is a common misspelling of another entry ("recieve"), not a word in its own right. */
    fun isMisspelling(i: Int): Boolean = misspelledIdx.binarySearch(i) >= 0

    /** Most frequent entry with this key, or -1. */
    fun indexOf(key: String): Int {
        var h = 0
        for (c in key) {
            if (c !in 'a'..'z') return -1
            h = h * 31 + (c - 'a')
        }
        var slot = mix(h) and mask
        while (true) {
            val i = table[slot] - 1
            if (i < 0) return -1
            if (length(i) == key.length) {
                val o = start[i]
                var same = true
                for (j in key.indices) if (letters[o + j].toInt() != key[j] - 'a') { same = false; break }
                if (same) return i
            }
            slot = (slot + 1) and mask
        }
    }

    fun contains(key: String): Boolean = indexOf(key) >= 0

    fun displayFor(key: String): String {
        val i = indexOf(key)
        return if (i >= 0) display(i) else displayOverrides(language)[key] ?: key
    }

    /** Likely next words after the word with this key (from the word list's next-word model). */
    fun nextFor(key: String): List<String> {
        val i = indexOf(key)
        if (i < 0 || nextStart[i] == nextStart[i + 1]) return emptyList()
        return (nextStart[i] until nextStart[i + 1]).map { j ->
            nextIds[j].let { if (it >= 0) display(it) else nextExtra[-1 - it] }
        }
    }

    /**
     * The next-word model's view after the word with key [prevKey], or at the start of a sentence
     * ([prevKey] null and [start]), its bonuses multiplied by [weight]; null without data.
     */
    fun context(prevKey: String?, start: Boolean, weight: Float): NextContext? {
        if (weight == 0f) return null
        val ids: IntArray
        val lifts: ByteArray
        val from: Int
        val to: Int
        val backoff: Float
        if (prevKey != null) {
            val i = indexOf(prevKey)
            if (i < 0) return null
            val b = ctxIdx.binarySearch(i)
            if (nextStart[i] == nextStart[i + 1] && b < 0) return null
            ids = nextIds; lifts = nextLift; from = nextStart[i]; to = nextStart[i + 1]
            backoff = if (b >= 0) ctxBackoff[b] else 0f
        } else if (start && startIds.isNotEmpty()) {
            ids = startIds; lifts = startLift; from = 0; to = startIds.size; backoff = startBackoff
        } else {
            return null
        }
        var cap = 4
        while (cap < 2 * (2 * (to - from) + apostrophes.size)) cap = cap shl 1
        val ctx = NextContext(cap, weight * backoff)
        for (a in apostrophes) ctx.put(a, 0f, follower = false)
        for (j in from until to) {
            val idx = ids[j]
            if (idx < 0) continue
            val v = weight * (lifts[j].toInt() and 0xFF) / LIFT_SCALE
            ctx.put(idx, v, follower = true)
            siblings[idx]?.forEach { ctx.put(it, v, follower = true) }
        }
        return ctx
    }

    /**
     * Entry [idx] right after entry [prev], unweighted: its ln(lift) if listed, else [prev]'s
     * backoff (0 for contractions, or without data).
     */
    fun nextBonus(prev: Int, idx: Int): Float {
        val pa = indexOf(key(prev))
        val pb = indexOf(key(idx))
        if (pa < 0 || pb < 0) return 0f
        for (j in nextStart[pa] until nextStart[pa + 1]) if (nextIds[j] == pb) return (nextLift[j].toInt() and 0xFF) / LIFT_SCALE
        if (apostrophes.binarySearch(idx) >= 0) return 0f
        val b = ctxIdx.binarySearch(pa)
        return if (b >= 0) ctxBackoff[b] else 0f
    }

    private fun hashOf(i: Int): Int {
        var h = 0
        for (j in start[i] until start[i + 1]) h = h * 31 + letters[j]
        return mix(h)
    }

    private fun sameKey(a: Int, b: Int): Boolean {
        val n = length(a)
        if (n != length(b)) return false
        val oa = start[a]
        val ob = start[b]
        for (j in 0 until n) if (letters[oa + j] != letters[ob + j]) return false
        return true
    }

    /** The binary form [fromBytes] reads back (big-endian, see [FORMAT]). */
    fun toBytes(): ByteArray {
        val bytes = ByteArrayOutputStream(letters.size * 4)
        DataOutputStream(bytes).use { o ->
            o.writeInt(FORMAT)
            fun ints(a: IntArray) { o.writeInt(a.size); for (v in a) o.writeInt(v) }
            fun strings(a: List<String>) {
                o.writeInt(a.size)
                for (s in a) s.toByteArray(Charsets.UTF_8).let { o.writeInt(it.size); o.write(it) }
            }
            o.writeInt(letters.size); o.write(letters)
            ints(start)
            o.writeInt(logFreq.size); for (v in logFreq) o.writeFloat(v)
            ints(displayIdx); strings(displayText.asList())
            ints(nextStart); ints(nextIds); strings(nextExtra.asList())
            o.writeInt(nextLift.size); o.write(nextLift)
            ints(ctxIdx); o.writeInt(ctxBackoff.size); for (v in ctxBackoff) o.writeFloat(v)
            ints(startIds); o.writeInt(startLift.size); o.write(startLift); o.writeFloat(startBackoff)
            ints(emojiIdx); strings(emojiText.asList())
            ints(misspelledIdx)
            strings(startWords)
        }
        return bytes.toByteArray()
    }

    /** Collects entries while loading, then turns raw counts into log-frequencies. */
    private class Builder {
        val keys = ArrayList<String>()
        val display = ArrayList<String>()
        val counts = ArrayList<Double>()
        val next = ArrayList<List<String>?>()
        var start: List<String> = emptyList()
        /** Misspelling key → the keys it is a misspelling of. */
        var misspellings: Map<String, List<String>> = emptyMap()
        /** Key → emoji. */
        var emoji: Map<String, String> = emptyMap()
        /** The next-word model by previous key ([readNext]), and for the start of a sentence. */
        var nextModel: Map<String, NextRow> = emptyMap()
        /** Two words often written as one, by their letters ([JOINED] for English). */
        var joined: Map<String, List<String>> = emptyMap()

        fun add(text: String, count: Double, next: List<String>?) {
            val key = keyOf(text) ?: return
            keys += key
            display += text
            counts += count
            this.next += next
        }

        fun build(): Dictionary {
            val n = keys.size
            val known = keys.toHashSet()
            // Also two words written as one ("thankyou"), where the list knows both of them.
            val wrong = (0 until n).filter { i ->
                misspellings[keys[i]]?.any { it in known } == true || joined[keys[i]]?.all { it in known } == true
            }
            for (i in wrong) counts[i] *= MISSPELLING_FACTOR
            val total = counts.sum()
            val starts = IntArray(n + 1)
            for (i in 0 until n) starts[i + 1] = starts[i] + keys[i].length
            val letters = ByteArray(starts[n])
            for (i in 0 until n) keys[i].forEachIndexed { j, c -> letters[starts[i] + j] = (c - 'a').toByte() }
            val logFreq = FloatArray(n) { ln(counts[it] / total).toFloat() }
            val dIdx = (0 until n).filter { display[it] != keys[it] }
            // Next words are given as text; point them at the most frequent entry shown that way.
            val byText = HashMap<String, Int>(n * 2)
            for (i in 0 until n) {
                val cur = byText[display[i]]
                if (cur == null || logFreq[i] > logFreq[cur]) byText[display[i]] = i
            }
            // The model's words are keys; point them at the most frequent entry with that key.
            val byKey = HashMap<String, Int>(n * 2)
            for (i in 0 until n) {
                val cur = byKey[keys[i]]
                if (cur == null || logFreq[i] > logFreq[cur]) byKey[keys[i]] = i
            }
            val nextStart = IntArray(n + 1)
            val ids = ArrayList<Int>()
            val lifts = ArrayList<Byte>()
            val extra = LinkedHashMap<String, Int>()
            val ctxI = ArrayList<Int>()
            val ctxB = ArrayList<Float>()
            for (i in 0 until n) {
                // A word list's own next words carry no lift: they get the old flat bonus.
                next[i]?.forEach { w ->
                    ids += byText[w] ?: (-1 - extra.getOrPut(w) { extra.size })
                    lifts += quantize(LIST_LIFT)
                }
                if (byKey[keys[i]] == i) nextModel[keys[i]]?.let { row ->
                    for ((k, l) in row.next) byKey[k]?.let { ids += it; lifts += quantize(l) }
                    ctxI += i
                    ctxB += row.backoff
                }
                nextStart[i + 1] = ids.size
            }
            val startRow = nextModel[START_ROW]
            val startIds = startRow?.next?.mapNotNull { (k, _) -> byKey[k] }.orEmpty()
            val startLift = startRow?.next?.filter { (k, _) -> k in byKey }?.map { quantize(it.second) }.orEmpty()
            val startWords = start.ifEmpty { startIds.take(START_WORDS).map { display[it] } }
            val eIdx = (0 until n).filter { keys[it] in emoji }
            return Dictionary(
                letters, starts, logFreq,
                dIdx.toIntArray(), Array(dIdx.size) { display[dIdx[it]] },
                nextStart, ids.toIntArray(), extra.keys.toTypedArray(), lifts.toByteArray(),
                ctxI.toIntArray(), ctxB.toFloatArray(),
                startIds.toIntArray(), startLift.toByteArray(), startRow?.backoff ?: 0f,
                eIdx.toIntArray(), Array(eIdx.size) { emoji.getValue(keys[eIdx[it]]) },
                wrong.toIntArray(), startWords,
            )
        }
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Version of the [toBytes] layout; bump when it changes so old copies are rebuilt. */
        private const val FORMAT = 0x4D44_0004

        /** ln(lift) is kept in a byte, in steps of 1 / [LIFT_SCALE] (up to about 16). */
        private const val LIFT_SCALE = 16f
        /** The lift of a next word from a word list that only names them (the private list). */
        private const val LIST_LIFT = 1.5f
        /** Sentence starts offered as predictions when the word list names none. */
        private const val START_WORDS = 8

        private fun quantize(lnLift: Float): Byte = (lnLift * LIFT_SCALE).roundToInt().coerceIn(0, 255).toByte()

        const val NEXT_ASSET = "next_words.tsv"

        /** Log-probability floor used for words that are not in the dictionary. */
        const val UNKNOWN_LOG_FREQ = -17f

        /** Slang / offensive words (flag 1) stay typeable but rarely pop up. */
        private const val FLAGGED_FACTOR = 0.02
        /** Known misspellings stay typeable but are rarely what the taps meant. */
        private const val MISSPELLING_FACTOR = 0.02

        const val PRIVATE_ASSET = "private_vocab.tsv"
        private const val START_ROW = "<s>"
        const val OPEN_ASSET = "words.txt"
        const val MISSPELLINGS_ASSET = "misspellings.txt"
        const val EMOJI_ASSET = "emoji_keywords.tsv"

        /**
         * Contractions for the open list, which drops their apostrophes; rough corpus counts. Each is
         * typed by its letters ("ill" → "I'll"), so every apostrophe word needs a row here.
         */
        private val CONTRACTIONS = mapOf(
            // be
            "im" to ("I'm" to 1_800_000), "youre" to ("you're" to 600_000),
            "hes" to ("he's" to 400_000), "shes" to ("she's" to 200_000),
            "its" to ("it's" to 2_000_000), "were" to ("we're" to 400_000),
            "theyre" to ("they're" to 200_000), "thats" to ("that's" to 700_000),
            "whats" to ("what's" to 400_000), "theres" to ("there's" to 250_000),
            "heres" to ("here's" to 80_000), "wheres" to ("where's" to 60_000),
            "whos" to ("who's" to 60_000), "hows" to ("how's" to 30_000),
            "whens" to ("when's" to 5_000), "whys" to ("why's" to 3_000),
            "whatre" to ("what're" to 10_000), "lets" to ("let's" to 400_000),
            // will
            "ill" to ("I'll" to 700_000), "youll" to ("you'll" to 80_000),
            "hell" to ("he'll" to 50_000), "shell" to ("she'll" to 25_000),
            "itll" to ("it'll" to 60_000), "well" to ("we'll" to 250_000),
            "theyll" to ("they'll" to 30_000), "thatll" to ("that'll" to 40_000),
            "wholl" to ("who'll" to 8_000), "whatll" to ("what'll" to 8_000),
            "therell" to ("there'll" to 8_000),
            // would / had
            "id" to ("I'd" to 300_000), "youd" to ("you'd" to 40_000),
            "hed" to ("he'd" to 60_000), "shed" to ("she'd" to 40_000),
            "itd" to ("it'd" to 15_000), "wed" to ("we'd" to 40_000),
            "theyd" to ("they'd" to 30_000), "thatd" to ("that'd" to 15_000),
            "whod" to ("who'd" to 10_000), "whatd" to ("what'd" to 15_000),
            "whered" to ("where'd" to 5_000), "howd" to ("how'd" to 8_000),
            "thered" to ("there'd" to 5_000),
            // have
            "ive" to ("I've" to 300_000), "youve" to ("you've" to 80_000),
            "weve" to ("we've" to 40_000), "theyve" to ("they've" to 30_000),
            "whove" to ("who've" to 3_000), "couldve" to ("could've" to 15_000),
            "wouldve" to ("would've" to 25_000), "shouldve" to ("should've" to 20_000),
            "mightve" to ("might've" to 5_000), "mustve" to ("must've" to 15_000),
            // not
            "dont" to ("don't" to 1_500_000), "cant" to ("can't" to 600_000),
            "didnt" to ("didn't" to 600_000), "doesnt" to ("doesn't" to 250_000),
            "wont" to ("won't" to 200_000), "isnt" to ("isn't" to 200_000),
            "wasnt" to ("wasn't" to 150_000), "havent" to ("haven't" to 150_000),
            "wouldnt" to ("wouldn't" to 120_000), "couldnt" to ("couldn't" to 100_000),
            "arent" to ("aren't" to 100_000), "shouldnt" to ("shouldn't" to 50_000),
            "werent" to ("weren't" to 40_000), "hasnt" to ("hasn't" to 40_000),
            "hadnt" to ("hadn't" to 30_000), "mustnt" to ("mustn't" to 8_000),
            "neednt" to ("needn't" to 3_000), "shant" to ("shan't" to 3_000),
            "aint" to ("ain't" to 30_000),
            // other
            "yall" to ("y'all" to 15_000), "oclock" to ("o'clock" to 40_000),
            "maam" to ("ma'am" to 60_000),
        )

        /**
         * Contraction keys that are also words in their own right ("its", "ill", "well"): those get
         * the contraction as a second entry with the same key, and both are offered.
         */
        private val ALSO_WORDS = setOf("its", "ill", "id", "were", "well", "hell", "shell", "shed", "wed", "lets")

        /**
         * Two words often written as one, which word lists made from text therefore hold as words of
         * their own. They are treated like [isMisspelling] entries (typeable, but rare and readily
         * corrected), so the decoder's two-word reading wins. Picked by hand: frequency alone can't
         * tell "ofthe" from "theme" (the + me) or "income" (in + come).
         */
        private val JOINED: Map<String, List<String>> = listOf(
            "a lot", "as well", "in fact", "in case", "of course", "each other", "at least", "thank you",
            "no one", "every time", "high school", "in front", "up to", "come on", "all right", "damn it",
            "of the", "in the", "for the", "to the", "on the", "at the", "if you", "for you", "are you",
            "do you", "of you", "it is", "it was", "to be", "i am", "and i",
        ).associate { it.replace(" ", "") to it.split(' ') }

        /** Words spelled with a capital inside them, which the keyboard keeps without shift. */
        private val MIXED_CASE = listOf(
            "iPhone", "iPad", "iPod", "eBay", "YouTube", "WhatsApp", "PowerPoint", "PlayStation", "McDonald", "McDonald's",
        ).associateBy { w -> w.lowercase().filter { it in 'a'..'z' } }

        private val ENGLISH_OVERRIDES: Map<String, String> =
            CONTRACTIONS.filterKeys { it !in ALSO_WORDS }.mapValues { it.value.first } + mapOf("i" to "I") + MIXED_CASE

        /** How words are spelled other than by their letters: the contractions and "I" in English, brand names in any. */
        private fun displayOverrides(language: Language): Map<String, String> = if (language.english) ENGLISH_OVERRIDES else MIXED_CASE

        /**
         * Prefers the local word list when it is present (see the class comment). The parsed
         * dictionary is kept in binary form under noBackupFilesDir, tagged with the app's install
         * time, so later starts read arrays instead of parsing text.
         */
        fun load(context: Context, language: Language = Language.EN): Dictionary {
            val assets = context.assets
            val asset = when {
                language.english && assets.list("")?.contains(PRIVATE_ASSET) == true -> PRIVATE_ASSET
                else -> language.wordsAsset
            }
            val installed = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val prefix = "dict-$asset-"
            val cache = File(context.noBackupFilesDir, "$prefix$installed.bin")
            runCatching { if (cache.exists()) return fromBytes(cache.readBytes()).also { it.language = language } }
            val wrong = language.misspellingsAsset?.let { a ->
                runCatching { assets.open(a).use(::readMisspellings) }.getOrNull()
            }.orEmpty()
            val emoji = language.emojiAsset?.let { a -> runCatching { assets.open(a).use(::readEmoji) }.getOrNull() }.orEmpty()
            val next = if (asset == PRIVATE_ASSET) emptyMap() else
                runCatching { assets.open(language.nextAsset).use(::readNext) }.getOrDefault(emptyMap())
            val dict = assets.open(asset).use {
                if (asset == PRIVATE_ASSET) loadPrivate(it, wrong, emoji) else loadOpen(it, wrong, emoji, next, language)
            }
            dict.language = language
            runCatching {
                // Older copies of this language's list go; the other language's stay.
                context.noBackupFilesDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
                val tmp = File(cache.path + ".tmp")
                tmp.writeBytes(dict.toBytes())
                tmp.renameTo(cache)
            }
            return dict
        }

        fun fromBytes(bytes: ByteArray): Dictionary {
            val b = ByteBuffer.wrap(bytes)
            require(b.int == FORMAT) { "unknown dictionary format" }
            fun ints() = IntArray(b.int).also { b.asIntBuffer().get(it); b.position(b.position() + it.size * 4) }
            fun strings() = Array(b.int) {
                val n = b.int
                String(bytes, b.position(), n, Charsets.UTF_8).also { b.position(b.position() + n) }
            }
            val letters = ByteArray(b.int).also { b.get(it) }
            val start = ints()
            val logFreq = FloatArray(b.int).also { b.asFloatBuffer().get(it); b.position(b.position() + it.size * 4) }
            val dIdx = ints()
            val dText = strings()
            val nextStart = ints()
            val nextIds = ints()
            val nextExtra = strings()
            val nextLift = ByteArray(b.int).also { b.get(it) }
            val ctxIdx = ints()
            val ctxBackoff = FloatArray(b.int).also { b.asFloatBuffer().get(it); b.position(b.position() + it.size * 4) }
            val startIds = ints()
            val startLift = ByteArray(b.int).also { b.get(it) }
            val startBackoff = b.float
            val eIdx = ints()
            val eText = strings()
            val wrong = ints()
            val startWords = strings().asList()
            return Dictionary(
                letters, start, logFreq, dIdx, dText, nextStart, nextIds, nextExtra, nextLift, ctxIdx, ctxBackoff,
                startIds, startLift, startBackoff, eIdx, eText, wrong, startWords,
            )
        }

        /** `assets/misspellings.txt`: "misspelling\tcorrection|correction" lines, as keys. */
        fun readMisspellings(input: InputStream): Map<String, List<String>> {
            val out = HashMap<String, List<String>>()
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab > 0) out[line.substring(0, tab)] = line.substring(tab + 1).split('|')
                }
            }
            return out
        }

        /** `assets/emoji_keywords.tsv`: "word\temoji" lines, by key. */
        fun readEmoji(input: InputStream): Map<String, String> {
            val out = HashMap<String, String>()
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab > 0) keyOf(line.substring(0, tab))?.let { out.putIfAbsent(it, line.substring(tab + 1)) }
                }
            }
            return out
        }

        fun loadPrivate(
            input: InputStream,
            misspellings: Map<String, List<String>> = emptyMap(),
            emoji: Map<String, String> = emptyMap(),
        ): Dictionary {
            val b = Builder()
            b.misspellings = misspellings
            b.emoji = emoji
            b.joined = JOINED
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 4) continue
                    val next = p[3].takeIf { it.isNotEmpty() }?.split('|')
                    if (p[0] == START_ROW) {
                        b.start = next.orEmpty()
                        continue
                    }
                    var count = p[1].toDoubleOrNull() ?: continue
                    if (p[2] == "1") count *= FLAGGED_FACTOR
                    b.add(p[0], count, next)
                }
            }
            return b.build()
        }

        /**
         * `assets/next_words.tsv`: "previous key \t ln(backoff) \t next key ln(lift) next key ln(lift) …",
         * the previous key "<s>" for the start of a sentence. A backoff above 0 is read as 0.
         */
        fun readNext(input: InputStream): Map<String, NextRow> {
            val out = HashMap<String, NextRow>()
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val p = line.split('\t')
                    if (p.size != 3) continue
                    val backoff = p[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                    val f = p[2].split(' ')
                    val next = ArrayList<Pair<String, Float>>(f.size / 2)
                    var j = 0
                    while (j + 1 < f.size) {
                        val l = f[j + 1].toFloatOrNull()
                        if (l != null && l.isFinite() && l > 0f && f[j].isNotEmpty()) next += f[j] to l
                        j += 2
                    }
                    out[p[0]] = NextRow(minOf(backoff, 0f), next)
                }
            }
            return out
        }

        fun loadOpen(
            input: InputStream,
            misspellings: Map<String, List<String>> = emptyMap(),
            emoji: Map<String, String> = emptyMap(),
            next: Map<String, NextRow> = emptyMap(),
            language: Language = Language.EN,
        ): Dictionary {
            val counts = LinkedHashMap<String, Long>(50_000)
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val sp = line.indexOf(' ')
                    if (sp <= 0) continue
                    counts[line.substring(0, sp)] = line.substring(sp + 1).trim().toLongOrNull() ?: continue
                }
            }
            if (language.english) for ((key, v) in CONTRACTIONS) {
                if (key !in ALSO_WORDS) counts[key] = maxOf(counts[key] ?: 0L, v.second.toLong())
            }
            val b = Builder()
            b.misspellings = misspellings
            b.emoji = emoji
            b.nextModel = next
            if (language.english) b.joined = JOINED
            val overrides = displayOverrides(language)
            for ((key, count) in counts) b.add(overrides[key] ?: key, count.toDouble(), null)
            if (language.english) for (key in ALSO_WORDS) CONTRACTIONS[key]?.let { (form, count) -> b.add(form, count.toDouble(), null) }
            return b.build()
        }

        /**
         * Letters-only lowercase key for a word, or null if it has nothing typeable. German letters
         * count as the letters they are typed with ("münchen" → "munchen", "straße" → "strasse"),
         * so a German word is found from the taps of its base letters (mini mode has no ü).
         */
        fun keyOf(word: String): String? {
            val k = StringBuilder(word.length)
            for (c in foldGerman(word.lowercase())) if (c in 'a'..'z') k.append(c)
            return if (k.isEmpty()) null else k.toString()
        }

        /** The same word but for capitals and German letters ("Für" and "fur", "Straße" and "strasse"). */
        fun sameWord(a: String, b: String): Boolean =
            a.equals(b, ignoreCase = true) || foldGerman(a).equals(foldGerman(b), ignoreCase = true)

        /** German letters as the letters they are typed with: ä → a, ö → o, ü → u, ß → ss. */
        private fun foldGerman(s: String): String {
            if (s.none { it in GERMAN_FOLD }) return s
            val out = StringBuilder(s.length + 2)
            for (c in s) out.append(GERMAN_FOLD[c] ?: c.toString())
            return out.toString()
        }

        private val GERMAN_FOLD = mapOf(
            'ä' to "a", 'ö' to "o", 'ü' to "u", 'Ä' to "A", 'Ö' to "O", 'Ü' to "U", 'ß' to "ss", 'ẞ' to "SS",
        )

        /** Spreads a key hash over the table's low bits. */
        private fun mix(h: Int): Int = h xor (h ushr 16) * 0x45d9f3b

        /** Binary search in a sparse (ascending index → value) column. */
        private fun sparse(idx: IntArray, values: Array<String>, i: Int): String? {
            val at = idx.binarySearch(i)
            return if (at >= 0) values[at] else null
        }
    }
}

/** One previous word in the next-word model: ln(backoff) for words not listed, and the listed words with ln(lift). */
class NextRow(val backoff: Float, val next: List<Pair<String, Float>>)

/**
 * The next-word model's view after one previous word, for scoring candidates ([Dictionary.context]):
 * [of] gives an entry's bonus, its weighted ln(lift) when it is listed after that word, or else the
 * [backoff] (≤ 0) that every other word gets; contractions get 0. [followers] are the listed
 * entries, so a scan that stops at the first word too rare to count can still take them in.
 */
class NextContext internal constructor(capacity: Int, val backoff: Float) {
    private val slots = IntArray(capacity)
    private val values = FloatArray(capacity)
    private val listed = BooleanArray(capacity)
    private val mask = capacity - 1
    private val followerList = ArrayList<Int>()
    private val entryList = ArrayList<Int>()

    /** The listed entries (and the entries sharing their keys). */
    val followers: List<Int> get() = followerList

    /** Every entry whose bonus isn't the [backoff]: the [followers] and the contractions. */
    val entries: List<Int> get() = entryList

    internal fun put(idx: Int, v: Float, follower: Boolean) {
        var s = slot(idx)
        while (slots[s] != 0 && slots[s] != idx + 1) s = (s + 1) and mask
        if (follower && !listed[s]) followerList += idx
        if (slots[s] == 0) entryList += idx
        slots[s] = idx + 1
        values[s] = v
        if (follower) listed[s] = true
    }

    /** Entry [idx]'s bonus after this context's previous word. */
    fun of(idx: Int): Float {
        var s = slot(idx)
        while (true) {
            val e = slots[s]
            if (e == 0) return backoff
            if (e == idx + 1) return values[s]
            s = (s + 1) and mask
        }
    }

    /** Whether [idx] is one of the [followers]. */
    fun isFollower(idx: Int): Boolean {
        var s = slot(idx)
        while (true) {
            val e = slots[s]
            if (e == 0) return false
            if (e == idx + 1) return listed[s]
            s = (s + 1) and mask
        }
    }

    private fun slot(idx: Int) = (idx * -0x61c88647) ushr 7 and mask

    companion object {
        /** No context: every word as it is. */
        val NONE = NextContext(2, 0f)
    }
}

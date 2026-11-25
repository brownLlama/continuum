package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * One kind of animal that visits the keyboard: a pixel sprite facing right, [W] pixels wide, and
 * how fast it walks ([speed] sprite pixels a second, a new leg frame every [frameMs]).
 *
 * The sprite is [body] over one of three [legs] row sets (standing, step, other step). [look] is
 * the whole body again with the head turned to you. Each character is a [colors] key; "." is empty.
 * Every frame gets a dark outline round its silhouette ([outlined]), so a pale animal still shows
 * on a light keyboard and each kind has a shape of its own.
 */
internal class Species(
    val key: String, val speed: Int, val frameMs: Long, private val colors: Map<Char, Int>,
    body: List<String>, look: List<String>, legs: List<List<String>>,
    /** The head, for grazing: the body's pixels from column [headFrom] on, in its rows up to [headTo]. */
    private val headFrom: Int, private val headTo: Int,
) {
    val stand = outlined(body + legs[0])
    val lookFrame = outlined(look + legs[0])
    /** Step, pass, step, pass. */
    val cycle = listOf(outlined(body + legs[1]), stand, outlined(body + legs[2]), stand)
    val height = stand.size

    /** Standing with the head lowered by 0..[MAX_DIP] + 1 rows (over the body, in front of it), to eat. */
    private val grazing = (0..MAX_DIP + 1).map { lowered(body + legs[0], it) }

    /**
     * The mouth, facing right: the lowest pixel of the head's two front columns (the frontmost if
     * both go as low). [mouthCol] is its column; [mouthLift], with the head up, how many rows it is
     * above the bottom of the frame (where the feet stand). Lowered by d rows, it is d less.
     */
    val mouthCol: Int
    val mouthLift: Int

    init {
        var front = -1
        for (r in 0..headTo) for (c in headFrom until W) if (body[r][c] != '.') front = maxOf(front, c)
        var row = -1
        var col = -1
        for (c in maxOf(headFrom, front - 1)..front) for (r in 0..headTo) {
            if (body[r][c] != '.' && r >= row) { row = r; col = c }
        }
        require(row >= 0) { "$key: no head at column $headFrom, rows 0..$headTo" }
        mouthCol = col
        // The frame has an empty row above the body (the outline's), and the mouth pixel itself.
        mouthLift = height - 1 - (row + 1)
    }

    /** The grazing frame with the head [dip] rows down (see [MAX_DIP]). */
    fun graze(dip: Int) = grazing[dip.coerceIn(0, MAX_DIP + 1)]

    /** [rows] with the head moved [dip] rows down, drawn over what is there, then outlined. */
    private fun lowered(rows: List<String>, dip: Int): List<String> {
        val out = Array(rows.size) { r -> CharArray(W) { c -> if (r <= headTo && c >= headFrom) '.' else rows[r][c] } }
        for (r in 0..headTo) for (c in headFrom until W) {
            val ch = rows[r][c]
            if (ch != '.' && r + dip < rows.size) out[r + dip][c] = ch
        }
        return outlined(out.map { String(it) })
    }

    fun color(ch: Char) = if (ch == OUTLINE_CHAR) OUTLINE else colors.getValue(ch)

    /** All frames of the sprite: what the tests check, and what a new animal has to get right. */
    internal val frames: List<List<String>> get() = listOf(stand, lookFrame) + cycle + grazing

    companion object {
        const val W = 24
        /** How far an animal lowers its head to eat a flower, at most (it bobs one row further as it chews). */
        const val MAX_DIP = 9
        private const val OUTLINE_CHAR = '#'
        private const val OUTLINE = 0xFF2B211C.toInt()

        /**
         * [rows] with an empty row above and below, and the outline: every empty cell right, left,
         * above or below a coloured one. The sprites keep their own first and last column empty.
         */
        internal fun outlined(rows: List<String>): List<String> {
            require(rows.all { it.length == W }) { "every row must be $W wide" }
            val grid = listOf(".".repeat(W)) + rows + listOf(".".repeat(W))
            fun filled(x: Int, y: Int) = grid.getOrNull(y)?.getOrNull(x)?.let { it != '.' } == true
            return grid.mapIndexed { y, row ->
                String(CharArray(W) { x ->
                    when {
                        row[x] != '.' -> row[x]
                        filled(x - 1, y) || filled(x + 1, y) || filled(x, y - 1) || filled(x, y + 1) -> OUTLINE_CHAR
                        else -> '.'
                    }
                })
            }
        }

        // Facing right. Far legs are a shade darker, so the four legs read apart. Rows are written with
        // dots for empty cells and keep one empty column on each side (the outline goes there).
        /** Alex, the golden puppy: floppy ears, a big black nose, a brown patch on his back, a brown striped tail. */
        val ALEX = Species(
            "alex", speed = 17, frameMs = 110L,
            headFrom = 12, headTo = 8,
            colors = mapOf('B' to 0xFFF3D58E.toInt(), 'L' to 0xFFFBEBC4.toInt(), 'D' to 0xFFDDB566.toInt(), 'P' to 0xFF8D5B2E.toInt(), 'K' to 0xFF1C1511.toInt()),
            body = listOf(
                "...............BBBB.....",
                "..............BBBBBBB...",
                ".............BDBBBBLBB..",
                "............BDDDBBBKBB..",
                "...PP.......DDDDBBBKBBB.",
                "..LL........DDDDBBBBLKK.",
                "..PP..BPPPBBDDDDBBBLLKK.",
                "..PPBBPPPPPBDDDDBBBLLLL.",
                "...PPBPPPPPBDDDDBBBBLL..",
                "...BBBBPPPBBBDDBBBB.....",
                "...BBBBBBBBBBBBB........",
                "...BBBBBBBBBBBBB........",
                ".....DDDDDDDDD..........",
            ),
            look = listOf(
                "...............BBBBB....",
                "..............BBBBBBB...",
                ".............DBLBBBBLD..",
                "............DDDKBBBBKDD.",
                "...PP.......DDDKBBBBKDD.",
                "..LL.......DDDDBKKKBDDD.",
                "..PP..BPPPBDDDDLKKKLDDD.",
                "..PPBBPPPPPBDDDLLLLLDDD.",
                "...PPBPPPPPBDDDLLLLLDDD.",
                "...BBBBPPPBBDDBBBLBB.DD.",
                "...BBBBBBBBBBBBB........",
                "...BBBBBBBBBBBBB........",
                ".....DDDDDDDDD..........",
            ),
            legs = listOf(
                listOf(
                    "....DD.BB...DD.BB.......",
                    "....DD.BB...DD.BB.......",
                    "....DD.LL...DD.LL.......",
                ),
                listOf(
                    "....DD.BB...DD.BB.......",
                    ".....DDBB...DD..LL......",
                    ".......LL...DD..........",
                ),
                listOf(
                    "....DD.BB...DD.BB.......",
                    "....DD..LL...DDBB.......",
                    "....DD.........LL.......",
                ),
            ),
        )
        /** Schibu, the llama: fluffy orange wool all over, a very long straight neck with a small red scarf, a tan face, tall banana ears. */
        val SCHIBU = Species(
            "schibu", speed = 13, frameMs = 140L,
            headFrom = 13, headTo = 8,
            colors = mapOf('O' to 0xFFD9822F.toInt(), 'o' to 0xFFB3621D.toInt(), 'H' to 0xFFF0AC5E.toInt(), 'T' to 0xFFD9B88E.toInt(), 't' to 0xFFBB966B.toInt(), 'F' to 0xFFEBD3AE.toInt(), 'K' to 0xFF1C1511.toInt(), 'R' to 0xFFD6303F.toInt(), 'r' to 0xFF9E1F2C.toInt()),
            body = listOf(
                "................T..T....",
                "...............TT.TT....",
                "...............TOOTFF...",
                "...............TOOTFFF..",
                "...............FOFFKFF..",
                "..............OFFFFKFFK.",
                "..............OOOFFFFtt.",
                "..............OOOOO.....",
                ".............OOOOOO.....",
                ".............RRRRRRR....",
                ".......OO....rrrrrrr....",
                "..HoOOOOOOOOO.oOORRO....",
                ".OOOOOOOOOHOOOOOORr.....",
                ".OOOOOOHooHOOoOOHRrO....",
                ".OOOoOOOOOOHOOoOHO......",
                "...OOOOOHoOOOOOOOO......",
                "....oooooooooOOO........",
            ),
            look = listOf(
                "...............T....T...",
                "..............T.OOO..T..",
                "..............TOOOOO.T..",
                "..............TFOOOF.T..",
                "...............FKFFK....",
                "..............OFKFFK.O..",
                "..............OFFFFF.O..",
                "...............FFKKF....",
                "...............OFttO....",
                "..............RRRRRRR...",
                ".......OO.....rrrrrrr...",
                "..HoOOOOOOOOO.oOORRO....",
                ".OOOOOOOOOHOOO.OORrHO...",
                ".OOOOOOHooHOOoOOHRrOO...",
                ".OOOoOOOOOOHOOoOHOOOHH..",
                "...OOOOOHoOOOOOOOOOOo...",
                "....oooooooo...OHOOO....",
            ),
            legs = listOf(
                listOf(
                    "....oo.OO...oo.OO.......",
                    "....oo.OO...oo.OO.......",
                    "....oo.OO...oo.OO.......",
                ),
                listOf(
                    "....oo.OO...oo.OO.......",
                    ".....ooOO...oo..OO......",
                    ".......OO...oo..........",
                ),
                listOf(
                    "....oo.OO...oo.OO.......",
                    "....oo..OO...ooOO.......",
                    "....oo.........OO.......",
                ),
            ),
        )
        /** Schimba, the leopard cub: golden with black spots, a white muzzle, chest and paws, a pink nose. */
        val SCHIMBA = Species(
            "schimba", speed = 16, frameMs = 120L,
            headFrom = 13, headTo = 8,
            colors = mapOf('Y' to 0xFFF2B22A.toInt(), 'y' to 0xFFD9941A.toInt(), 'S' to 0xFF2A2220.toInt(), 'W' to 0xFFFBF6EA.toInt(), 'P' to 0xFFE69A9A.toInt(), 'K' to 0xFF1C1511.toInt()),
            body = listOf(
                "..............YYY..YY...",
                "..............YYYYYYYY..",
                ".............YYYSSYYYY..",
                ".............YYYYYYKKY..",
                "............YYYYSYYKKYY.",
                "............YYYSYYYYWPP.",
                ".SS..YYYYYYY.YYYYYYWWWW.",
                ".S.YYYYYYSSYYYYYYSWWWWW.",
                ".Y.YYSSYYYSYSWYYYYYWWW..",
                ".YYYYYSYYYYYWWWYYYYY....",
                "...YSYYYSSYYWWW.........",
                "....YYYYYYYYSW..........",
                "......yyyyyy............",
            ),
            look = listOf(
                ".............YYY...YYY..",
                "............YYYYYSSYYYY.",
                ".............YYYSYYSYY..",
                ".............YKKYYYYKK..",
                "............YYKKYYYYKKY.",
                "............YSYYYYYYYYS.",
                ".SS..YYYYYYYYYYWWPPWYYY.",
                ".S.YYYYYYSSYYYYWWWWWYY..",
                ".Y.YYSSYYYSYSYYWWWWWYY..",
                ".YYYYYSYYYYYWWWYYWYY....",
                "...YSYYYSSYYWWW.........",
                "....YYYYYYYYSW..........",
                "......yyyyyy............",
            ),
            legs = listOf(
                listOf(
                    "....yy.YY...yy.YY.......",
                    "....yy.YY...yy.YY.......",
                    "....WW.WW...WW.WW.......",
                ),
                listOf(
                    "....yy.YY...yy.YY.......",
                    ".....WWYY...yy..WW......",
                    ".......WW...WW..........",
                ),
                listOf(
                    "....yy.YY...yy.YY.......",
                    "....yy..WW...WWYY.......",
                    "....WW.........WW.......",
                ),
            ),
        )
        /** Nibu, the sheep with the bell: cream wool, big floppy ears, a green ribbon and golden bell, brown boots. */
        val NIBU = Species(
            "nibu", speed = 10, frameMs = 150L,
            headFrom = 12, headTo = 9,
            colors = mapOf('C' to 0xFFFBF0D8.toInt(), 'D' to 0xFFE3D2AE.toInt(), 'F' to 0xFFFFF8EC.toInt(), 'E' to 0xFFC48A5C.toInt(), 'K' to 0xFF1C1511.toInt(), 'N' to 0xFFE8A0A0.toInt(), 'G' to 0xFF1F8A4C.toInt(), 'g' to 0xFF45BC7A.toInt(), 'Y' to 0xFFE3A82F.toInt(), 'R' to 0xFF8B5A33.toInt()),
            body = listOf(
                "..............CCCCCC....",
                ".............CCCCCCCC...",
                ".............CCCCCCCCC..",
                "............CDDCCCCCCC..",
                "............DDDDCFFFFFC.",
                ".......CCCC.DDEDFFFFKFC.",
                "...CCCCCCCCCDEEEFFFFKFF.",
                "..CCCCCCCDCCDEEEFFFFFFN.",
                "..CCCDCCCCCCDDEDFFFFFN..",
                "..CCCCCCCCCCDDDCCFFFF...",
                ".CCCCCCCCCCCCCCGg..gG...",
                "..CCDCCDCCCCCCCCGGGG....",
                "...CCCCCCCCDCC...YY.....",
                ".....CCCCCCCC....YY.....",
            ),
            look = listOf(
                "...............CCCCC....",
                "..............CCCCCCCC..",
                ".............CCCCCCCCC..",
                "............DCCCFFFCCCD.",
                "...........DDDCFFFFFCDD.",
                ".......CCCCDDEDKFFFFKED.",
                "...CCCCCCCCDEEDKFFFFKEE.",
                "..CCCCCCCDCDEEDFFFFFDEE.",
                "..CCCDCCCCCDEEDFFNNFFEE.",
                "..CCCCCCCCCDDECFFNFF.ED.",
                ".CCCCCCCCCCCDCGCC....GD.",
                "..CCDCCDCCCCCCCGGGGGG...",
                "...CCCCCCCCDCC...YY.....",
                ".....CCCCCCCC....YY.....",
            ),
            legs = listOf(
                listOf(
                    "....CC.DD...DD.CC.......",
                    "....RR.RR...RR.RR.......",
                    "....RR.RR...RR.RR.......",
                ),
                listOf(
                    "....RR.DD...DD.RR.......",
                    ".....RRRR...RR..RR......",
                    ".......RR...RR..........",
                ),
                listOf(
                    "....CC.RR...RR.CC.......",
                    "....RR..RR...RRRR.......",
                    "....RR.........RR.......",
                ),
            ),
        )
        /** Scheepu, the sheep in the green shirt: white curly wool, pink ears and feet, a pink cord on the shirt. */
        val SCHEEPU = Species(
            "scheepu", speed = 10, frameMs = 150L,
            headFrom = 13, headTo = 9,
            colors = mapOf('W' to 0xFFF6F3EC.toInt(), 'D' to 0xFFD6D1C6.toInt(), 'F' to 0xFFFFF3EC.toInt(), 'P' to 0xFFF28CAE.toInt(), 'K' to 0xFF1C1511.toInt(), 'G' to 0xFF1E9244.toInt(), 'g' to 0xFF136B2C.toInt(), 'R' to 0xFFE5509A.toInt()),
            body = listOf(
                "...............W.WW.....",
                ".............WWWWWWWW...",
                "............WWPPWWWWWWW.",
                "............WWPWWFFFFW..",
                "............WWWWFFFFFF..",
                ".....WW.WWW.WWWWFFFFKFF.",
                "...WWDWWgggggWWWFFFFKFF.",
                "...WWW.GRGGGGWWWFFFFFPP.",
                ".WWWDWGGGRGGGWGWFFFFFF..",
                ".WWWWWGGGGRGGGGWWWFFF...",
                "..WDWWGGGGGRGGGWW.......",
                "..WWWWGGGGGGRGGWD.......",
                "..WWWDgggggggggD........",
                ".....WWWWWWWWW..........",
            ),
            look = listOf(
                "...............W.WW.....",
                "..............WWWWWWW...",
                "...........WWWWWWWWWWWW.",
                "..........WWPPWWFFFWWPP.",
                "...........WWWWFFFFFWWW.",
                ".....WW.WWW.WWFKFFFFKWW.",
                "...WWDWWgggggWFKFFFFKWW.",
                "...WWW.GRGGGGWFFFFFFFW..",
                ".WWWDWGGGRGGGWFFFPPFF...",
                ".WWWWWGGGGRGGGGFFPFFW...",
                "..WDWWGGGGGRGGGWW.......",
                "..WWWWGGGGGGRGGWD.......",
                "..WWWDgggggggggD........",
                ".....WWWWWWWWW..........",
            ),
            legs = listOf(
                listOf(
                    "....WW.DD...DD.WW.......",
                    "....WW.DD...DD.WW.......",
                    "....PP.PP...PP.PP.......",
                ),
                listOf(
                    "....WW.DD...DD.WW.......",
                    ".....PPDD...DD..PP......",
                    ".......PP...PP..........",
                ),
                listOf(
                    "....WW.DD...DD.WW.......",
                    "....WW..PP...PPWW.......",
                    "....PP.........PP.......",
                ),
            ),
        )

        val ALL = listOf(SCHIBU, ALEX, SCHIMBA, NIBU, SCHEEPU)

        fun named(key: String) = ALL.firstOrNull { it.key == key }

        /**
         * The animals of the older versions, as the ones that took their place: the llama is Schibu,
         * the dog Alex, the snow leopard Schimba, the sheep Nibu and Scheepu. The cat and the turtle
         * are gone, so a choice that was only those is no choice at all.
         */
        private val OLD_KEYS = mapOf(
            "llama" to listOf("schibu"), "dog" to listOf("alex"), "snow_leopard" to listOf("schimba"),
            "sheep" to listOf("nibu", "scheepu"),
        )

        /** The picked animals of a saved choice (null: nothing saved, so all), old names carried over. */
        fun picked(saved: Set<String>?): List<Species> {
            if (saved == null) return ALL
            val keys = saved.flatMap { OLD_KEYS[it] ?: listOf(it) }.toSet()
            val kinds = ALL.filter { it.key in keys }
            // Only animals that no longer exist were picked: back to all of them.
            return if (kinds.isEmpty() && saved.isNotEmpty()) ALL else kinds
        }

    }
}

/**
 * Setting "How often" for the visiting animals: [gapMs] from one visit to when the next is due
 * (null: once each time the keyboard opens), and how soon after the keyboard opens a due one comes.
 */
internal enum class AnimalFrequency(val key: String, val gapMs: LongRange?, val openMs: LongRange) {
    ALWAYS("always", null, 1_000L..2_000L),
    SOMETIMES("sometimes", 3 * 60_000L..6 * 60_000L, 2_000L..6_000L),
    RARELY("rarely", 45 * 60_000L..75 * 60_000L, 2_000L..6_000L);

    companion object {
        fun named(key: String?) = entries.firstOrNull { it.key == key } ?: ALWAYS
    }
}

/**
 * The animal walking across the keys now and then (setting "Animal visits"), [px] screen pixels per
 * sprite pixel, drawn see-through over the keys. It only draws: touches go to the keys underneath,
 * so it keeps walking while you type.
 *
 * A visit: in from one side along the row whose bottom is at [start]'s feet, 1–3 stops at random
 * spots (standing still or turning to look at you), out the other side. [KeyboardView] decides
 * when one comes, which animal and which row.
 *
 * Walking on the grass, it eats the flowers of [meadow] it comes to: it stops with its mouth on
 * the flower, lowers its head to it ([Species.graze]) and chews, bobbing its head, while the flower
 * goes bite by bite ([Flower.bites]); then it walks on.
 */
internal class Animal(private val px: Float) {

    var visiting = false
        private set

    /** The flowers in the grass it walks on; null (or no grass under it) and it eats nothing. */
    var meadow: Meadow? = null

    private var species = Species.SCHIBU
    private var x = 0f
    private var feet = 0f
    private var dir = 1
    private var frame = 0
    private var frameMs = 0L
    private var stopsLeft = 0
    /** Where the next stop is (x), or null when there are no stops left. */
    private var stopAt: Float? = null
    /** While stopped: until when, and whether it's looking at you. */
    private var stopUntil = 0L
    private var looking = false
    private var lastAt = 0L
    private val paint = Paint()
    /** How many sprite pixels its feet are above the ground of the grass (0: not on grass). */
    private var groundRows = 0
    /** The flower it is eating, since [eatStart], with its head [dip] rows down. */
    private var meal: Flower? = null
    private var eatStart = 0L
    private var dip = 0

    /** The flower it is eating, for tests. */
    internal val eating: Flower? get() = meal

    /** The grid column (of [px]-wide columns from the left edge) of its sprite's left edge. */
    internal val gridLeft: Int get() = floor(x / px).toInt()

    /** The grid column its mouth is in. */
    internal val mouthColumn: Int get() = gridLeft + mouthOffset()

    /** Where the mouth is in the sprite as it faces now. */
    private fun mouthOffset() = if (dir > 0) species.mouthCol else W - 1 - species.mouthCol

    /**
     * Starts a visit of [kind] across [width], feet on [feetY]. [groundRows]: how many sprite pixels
     * that is above the ground of the grass, when it walks on grass (0: it doesn't).
     */
    fun start(kind: Species, width: Float, feetY: Float, now: Long, groundRows: Int = 0) {
        species = kind
        dir = if (Random.nextBoolean()) 1 else -1
        x = if (dir > 0) -W * px else width
        feet = feetY
        frame = 0
        frameMs = 0L
        stopsLeft = 1 + Random.nextInt(3)
        stopUntil = 0L
        lastAt = now
        this.groundRows = groundRows
        meal = null
        planStop(width)
        visiting = true
    }

    /** Gone at once (the keyboard changed under it). A flower it was eating grows back whole. */
    fun end() {
        visiting = false
        meal?.bites = 0
        meal = null
    }

    private fun planStop(width: Float) {
        stopAt = if (stopsLeft-- > 0) x + dir * width * (0.12f + Random.nextFloat() * 0.23f) else null
    }

    /**
     * Moves on to [now] and draws. Returns how many ms until it next looks different (when to
     * redraw), or -1 once the visit is over.
     */
    fun draw(c: Canvas, width: Float, now: Long): Long {
        if (!visiting) return -1
        val s = species
        val dt = (now - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = now
        val next: Long
        val eaten = meal
        if (eaten != null) {
            val t = now - eatStart
            if (t < EAT_MS) {
                eaten.bites = (t * Flower.BITES / EAT_MS).toInt()
                next = CHEW_MS - t % CHEW_MS
            } else {
                // All gone: walk on.
                eaten.gone = true
                meal = null
                next = 1L
            }
        } else if (now < stopUntil) {
            next = stopUntil - now
        } else {
            if (stopUntil != 0L) {
                stopUntil = 0L
                planStop(width)
            }
            val mouthBefore = mouthColumn
            x += dir * s.speed * px * dt / 1000f
            frameMs += dt
            while (frameMs >= s.frameMs) {
                frameMs -= s.frameMs
                frame = (frame + 1) % s.cycle.size
            }
            val stop = stopAt
            if (stop != null && (if (dir > 0) x >= stop else x <= stop)) {
                stopUntil = now + 700 + Random.nextLong(1500)
                looking = Random.nextBoolean()
            }
            if (if (dir > 0) x > width else x < -W * px) return (-1L).also { visiting = false }
            // Its mouth came to a flower: it stops right there, mouth on it, and eats it.
            val flower = if (groundRows > 0) meadow?.reached(mouthBefore, mouthColumn, dir) else null
            if (flower != null) {
                x = (flower.col - mouthOffset()) * px
                flower.from = dir
                meal = flower
                eatStart = now
                // Low enough that the mouth is on the flower's head (and, bobbing, still above its feet).
                dip = (s.mouthLift + groundRows - flower.row).coerceIn(1, minOf(Species.MAX_DIP, s.mouthLift - 2))
                next = 1L
            } else {
                next = if (stopUntil > now) stopUntil - now else min(s.frameMs - frameMs, 1000L / s.speed)
            }
        }
        val face = when {
            // Chewing: the head bobs one row further down and back up.
            meal != null -> s.graze(dip + ((now - eatStart) / CHEW_MS % 2).toInt())
            stopUntil <= now -> s.cycle[frame]
            looking -> s.lookFrame
            else -> s.stand
        }
        // Whole sprite pixels only, so it stays crisp. Each run of one colour is one rectangle.
        val left = floor(x / px) * px
        // Walking, the body rises a pixel as the legs pass each other, and sinks as they stride: a bounce, not a glide.
        val walking = meal == null && stopUntil <= now
        val top = feet - s.height * px - if (walking && frame % 2 == 1) px else 0f
        for (r in face.indices) {
            val row = face[r]
            fun at(col: Int) = row[if (dir > 0) col else W - 1 - col]
            var col = 0
            while (col < W) {
                val ch = at(col)
                var end = col + 1
                if (ch != '.') {
                    while (end < W && at(end) == ch) end++
                    paint.color = s.color(ch)
                    paint.alpha = ALPHA
                    c.drawRect(left + col * px, top + r * px, left + end * px, top + (r + 1) * px, paint)
                }
                col = end
            }
        }
        return max(1L, next)
    }

    internal companion object {
        const val W = Species.W
        const val MAX_STEP_MS = 100L
        /** 85%: the animal is easy to make out, and the keys under it still show a little. */
        const val ALPHA = 217
        /** How long eating a flower takes, and how often the head bobs meanwhile (a bite every other bob). */
        const val EAT_MS = 2_000L
        const val CHEW_MS = 250L
    }
}

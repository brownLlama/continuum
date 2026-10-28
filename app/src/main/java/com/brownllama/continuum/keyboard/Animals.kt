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
 * The sprite is [body] over one of three [legs] rows sets (standing, step, other step). [look] is
 * the head turned to you, put over the top of [body]. Each character is a [colors] key; "." is empty.
 */
internal class Species(
    val key: String, val speed: Int, val frameMs: Long, private val colors: Map<Char, Int>,
    body: List<String>, look: List<String>, legs: List<List<String>>,
) {
    val stand = body + legs[0]
    val lookFrame = look + body.drop(look.size) + legs[0]
    /** Step, pass, step, pass. */
    val cycle = listOf(body + legs[1], stand, body + legs[2], stand)
    val height = stand.size

    fun color(ch: Char) = colors.getValue(ch)

    companion object {
        const val W = 16

        // Facing right. Far legs are a shade darker, so the four legs read apart.
        val LLAMA = Species(
            "llama", speed = 10, frameMs = 140L,
            colors = mapOf('B' to 0xFFD98B5F.toInt(), 'D' to 0xFFA9643F.toInt(), 'E' to 0xFF2A1A12.toInt(), 'M' to 0xFFF3DCC2.toInt()),
            body = listOf(
                "..........B..B..",
                "..........B..B..",
                "..........BBBB..",
                "..........BBEBMM",
                "..........BBBBMM",
                "..........BBBB..",
                "..........BBB...",
                "..........BBB...",
                ".B........BBB...",
                ".BBBBBBBBBBBB...",
                ".BBBBBBBBBBBB...",
                "..BBBBBBBBBBB...",
                "..BBBBBBBBBBB...",
            ),
            look = listOf(
                ".........B...B..",
                ".........B...B..",
                ".........BBBBB..",
                ".........BEBEB..",
                ".........BMMMB..",
                "..........BBB...",
            ),
            legs = listOf(
                listOf(
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                ),
                listOf(
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                    "....BD....DB....",
                    ".....D......B...",
                ),
                listOf(
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                    "...B..D..D..B...",
                    "...B.....D......",
                ),
            ),
        )
        val DOG = Species(
            "dog", speed = 13, frameMs = 110L,
            colors = mapOf('B' to 0xFFC8964F.toInt(), 'D' to 0xFF8A5A2B.toInt(), 'E' to 0xFF1E1611.toInt(), 'N' to 0xFF1E1611.toInt(), 'M' to 0xFFE8C994.toInt()),
            body = listOf(
                "...........BBB..",
                "..........DBBEB.",
                "..........DBBBBN",
                "B.........DBBMM.",
                ".B........BBB...",
                "..BBBBBBBBBBB...",
                "..BBBBBBBBBBB...",
                "..BMMMMMMMMBB...",
            ),
            look = listOf(
                "..........BBBBB.",
                ".........DBEBEBD",
                ".........DBBNBBD",
                "B..........MMM..",
            ),
            legs = listOf(
                listOf(
                    "...B.D....D.B...",
                    "...B.D....D.B...",
                    "...B.D....D.B...",
                ),
                listOf(
                    "...B.D....D.B...",
                    "....BD.....DB...",
                    ".....D......B...",
                ),
                listOf(
                    "...B.D....D.B...",
                    "...B..D...D..B..",
                    "...B......D.....",
                ),
            ),
        )
        val CAT = Species(
            "cat", speed = 11, frameMs = 130L,
            colors = mapOf('B' to 0xFF8C8C99.toInt(), 'D' to 0xFF62626E.toInt(), 'E' to 0xFFC9E265.toInt(), 'N' to 0xFFE79AA8.toInt()),
            body = listOf(
                "B..........B.B..",
                "B..........BBB..",
                ".B........BBEBB.",
                ".B........BBBBN.",
                "..B.......BBBB..",
                "..BBBBBBBBBBB...",
                "..BBBBBBBBBBB...",
                "...BBBBBBBBB....",
            ),
            look = listOf(
                "B.........B...B.",
                "B.........BB.BB.",
                ".B........BEBEB.",
                ".B........BBNBB.",
                "..B........BBB..",
            ),
            legs = listOf(
                listOf(
                    "....B.D..D.B....",
                    "....B.D..D.B....",
                    "....B.D..D.B....",
                ),
                listOf(
                    "....B.D..D.B....",
                    ".....BD...DB....",
                    "......D....B....",
                ),
                listOf(
                    "....B.D..D.B....",
                    "....B..D.D..B...",
                    "....B....D......",
                ),
            ),
        )
        val TURTLE = Species(
            "turtle", speed = 4, frameMs = 260L,
            colors = mapOf('S' to 0xFF5E8C4A.toInt(), 'D' to 0xFF3F6633.toInt(), 'B' to 0xFF9DBF6B.toInt(), 'E' to 0xFF1B2414.toInt(), 'M' to 0xFFC9B87A.toInt()),
            body = listOf(
                "....SSSSS.......",
                "...SSDSSDSS.....",
                "..SSSSSSSSSS.BB.",
                "..SDSSDSSDSSBBEB",
                ".BSSSSSSSSSSBBBB",
                "..MMMMMMMMMM....",
            ),
            look = listOf(
                "....SSSSS.......",
                "...SSDSSDSS.....",
                "..SSSSSSSSSSBBBB",
                "..SDSSDSSDSSEBBE",
            ),
            legs = listOf(
                listOf(
                    "...B.D...D.B....",
                    "...B.D...D.B....",
                ),
                listOf(
                    "....BD....DB....",
                    ".....D.....B....",
                ),
                listOf(
                    "...B..D..D..B...",
                    "...B.....D......",
                ),
            ),
        )
        val SHEEP = Species(
            "sheep", speed = 8, frameMs = 150L,
            colors = mapOf('W' to 0xFFEDEAE4.toInt(), 'D' to 0xFFBDB7AD.toInt(), 'F' to 0xFF3B3434.toInt(), 'G' to 0xFF231E1E.toInt(), 'L' to 0xFFF5F1E8.toInt()),
            body = listOf(
                "...........WW...",
                "..........WFFF..",
                ".........FFFLFF.",
                "..........FFFFF.",
                "..WWWWWWWWFFF...",
                ".WWDWWWWDWWWW...",
                "WWWWWWWDWWWWWW..",
                ".WWWWDWWWWWDWW..",
                "..WWWWWWWWWWW...",
            ),
            look = listOf(
                "..........WWW...",
                ".........FFFFF..",
                ".........FLFLF..",
                "..........FFF...",
            ),
            legs = listOf(
                listOf(
                    "...F.G....G.F...",
                    "...F.G....G.F...",
                    "...F.G....G.F...",
                ),
                listOf(
                    "...F.G....G.F...",
                    "....FG.....GF...",
                    ".....G......F...",
                ),
                listOf(
                    "...F.G....G.F...",
                    "...F..G...G..F..",
                    "...F......G.....",
                ),
            ),
        )
        val SNOW_LEOPARD = Species(
            "snow_leopard", speed = 12, frameMs = 120L,
            colors = mapOf('B' to 0xFFDAD6CE.toInt(), 'S' to 0xFF5C5A57.toInt(), 'D' to 0xFFA9A59C.toInt(), 'E' to 0xFF2E3A40.toInt(), 'N' to 0xFFC98A8A.toInt()),
            body = listOf(
                "...........B.B..",
                "..........BBBBB.",
                "..........BSBEBN",
                "B.........BBBBB.",
                "S..BBSBBBBSBB...",
                "BSBBBSBBBBSBBB..",
                "..BBSBBBBSBBBB..",
            ),
            look = listOf(
                "..........B...B.",
                "..........BBBBB.",
                "..........BEBEB.",
                "B.........BBNBB.",
            ),
            legs = listOf(
                listOf(
                    "....B.D...D.B...",
                    "....B.D...D.B...",
                    "....B.D...D.B...",
                ),
                listOf(
                    "....B.D...D.B...",
                    ".....BD....DB...",
                    "......D.....B...",
                ),
                listOf(
                    "....B.D...D.B...",
                    "....B..D..D..B..",
                    "....B.....D.....",
                ),
            ),
        )
        val ALL = listOf(LLAMA, DOG, CAT, TURTLE, SHEEP, SNOW_LEOPARD)

        fun named(key: String) = ALL.firstOrNull { it.key == key }
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
 */
internal class Animal(private val px: Float) {

    var visiting = false
        private set

    private var species = Species.LLAMA
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

    fun start(kind: Species, width: Float, feetY: Float, now: Long) {
        species = kind
        dir = if (Random.nextBoolean()) 1 else -1
        x = if (dir > 0) -W * px else width
        feet = feetY
        frame = 0
        frameMs = 0L
        stopsLeft = 1 + Random.nextInt(3)
        stopUntil = 0L
        lastAt = now
        planStop(width)
        visiting = true
    }

    /** Gone at once (the keyboard changed under it). */
    fun end() {
        visiting = false
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
        if (now < stopUntil) {
            next = stopUntil - now
        } else {
            if (stopUntil != 0L) {
                stopUntil = 0L
                planStop(width)
            }
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
            next = if (stopUntil > now) stopUntil - now else min(s.frameMs - frameMs, 1000L / s.speed)
        }
        val face = when {
            stopUntil <= now -> s.cycle[frame]
            looking -> s.lookFrame
            else -> s.stand
        }
        // Whole sprite pixels only, so it stays crisp.
        val left = floor(x / px) * px
        val top = feet - s.height * px
        paint.color = SHADOW
        paint.alpha = SHADOW_ALPHA
        c.drawRect(left + 2 * px, feet - px / 2, left + 13 * px, feet, paint)
        for (r in face.indices) {
            val row = face[r]
            for (col in 0 until W) {
                val ch = row[if (dir > 0) col else W - 1 - col]
                if (ch == '.') continue
                paint.color = s.color(ch)
                paint.alpha = ALPHA
                c.drawRect(left + col * px, top + r * px, left + (col + 1) * px, top + (r + 1) * px, paint)
            }
        }
        return max(1L, next)
    }

    private companion object {
        const val W = Species.W
        const val MAX_STEP_MS = 100L
        /** 75%: the keys under it stay readable. */
        const val ALPHA = 191
        const val SHADOW_ALPHA = 41
        const val SHADOW = 0xFF000000.toInt()
    }
}

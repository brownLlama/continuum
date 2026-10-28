package com.brownllama.continuum.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The llama that now and then walks across the keys (setting "Llama visits"): a 16 × 17 pixel
 * sprite, [px] screen pixels per sprite pixel, drawn see-through over the keys. It only draws:
 * touches go to the keys underneath, so it can keep walking while you type.
 *
 * A visit: in from one side along the row whose bottom is at [start]'s feet, 1–3 stops at random
 * spots (standing still or turning to look at you), out the other side. [KeyboardView] decides
 * when it may come and which row it walks on.
 */
internal class Llama(private val px: Float) {

    var visiting = false
        private set

    private var x = 0f
    private var feet = 0f
    private var dir = 1
    private var frame = 0
    private var frameMs = 0L
    private var stopsLeft = 0
    /** Where the next stop is (x), or null when there are no stops left. */
    private var stopAt: Float? = null
    /** While stopped: until when, and which face it shows ([STAND] or [LOOK]). */
    private var stopUntil = 0L
    private var stopFace = STAND
    private var lastAt = 0L
    private val paint = Paint()

    fun start(width: Float, feetY: Float, now: Long) {
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
            x += dir * SPEED * px * dt / 1000f
            frameMs += dt
            while (frameMs >= FRAME_MS) {
                frameMs -= FRAME_MS
                frame = (frame + 1) % CYCLE.size
            }
            val stop = stopAt
            if (stop != null && (if (dir > 0) x >= stop else x <= stop)) {
                stopUntil = now + 700 + Random.nextLong(1500)
                stopFace = if (Random.nextBoolean()) LOOK else STAND
            }
            if (if (dir > 0) x > width else x < -W * px) return (-1L).also { visiting = false }
            next = if (stopUntil > now) stopUntil - now else min(FRAME_MS - frameMs, 1000L / SPEED)
        }
        val face = if (stopUntil > now) stopFace else CYCLE[frame]
        // Whole sprite pixels only, so it stays crisp.
        val left = floor(x / px) * px
        val top = feet - H * px
        paint.color = SHADOW
        paint.alpha = SHADOW_ALPHA
        c.drawRect(left + 2 * px, feet - px / 2, left + 13 * px, feet, paint)
        for (r in face.indices) {
            val row = face[r]
            for (col in 0 until W) {
                val ch = row[if (dir > 0) col else W - 1 - col]
                if (ch == '.') continue
                paint.color = color(ch)
                paint.alpha = ALPHA
                c.drawRect(left + col * px, top + r * px, left + (col + 1) * px, top + (r + 1) * px, paint)
            }
        }
        return max(1L, next)
    }

    private fun color(ch: Char) = when (ch) {
        'D' -> FAR_LEGS
        'E' -> EYES
        'M' -> MUZZLE
        else -> BODY
    }

    private companion object {
        const val W = 16
        const val H = 17
        /** Sprite pixels per second (2 dp each: about 20 dp/s). */
        const val SPEED = 10
        const val FRAME_MS = 140L
        const val MAX_STEP_MS = 100L
        /** 75%: the keys under it stay readable. */
        const val ALPHA = 191
        const val SHADOW_ALPHA = 41

        const val BODY = 0xFFD98B5F.toInt()
        const val FAR_LEGS = 0xFFA9643F.toInt()
        const val EYES = 0xFF2A1A12.toInt()
        const val MUZZLE = 0xFFF3DCC2.toInt()
        const val SHADOW = 0xFF000000.toInt()

        // B body, D far legs (a shade darker, so the four legs read apart), E eye, M muzzle. Facing right.
        val SIDE = listOf(
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
        )
        /** The head turned to look at you; the body stays as it is. */
        val LOOK_HEAD = listOf(
            ".........B...B..",
            ".........B...B..",
            ".........BBBBB..",
            ".........BEBEB..",
            ".........BMMMB..",
            "..........BBB...",
        )
        val LEGS_STAND = List(4) { "...B.D...D.B...." }
        val LEGS_STEP_1 = listOf("...B.D...D.B....", "...B.D...D.B....", "....BD....DB....", ".....D......B...")
        val LEGS_STEP_2 = listOf("...B.D...D.B....", "...B.D...D.B....", "...B..D..D..B...", "...B.....D......")

        val STAND = SIDE + LEGS_STAND
        val LOOK = LOOK_HEAD + SIDE.drop(LOOK_HEAD.size) + LEGS_STAND
        /** Step, pass, step, pass. */
        val CYCLE = listOf(SIDE + LEGS_STEP_1, STAND, SIDE + LEGS_STEP_2, STAND)
    }
}

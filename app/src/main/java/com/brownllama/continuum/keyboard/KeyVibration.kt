package com.brownllama.continuum.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short tick on each key press (settings "Vibrate on keys" and its strength).
 *
 * Each tick is a single pulse of [TICK_MS] ms or less, about what a phone spends on one touch
 * feedback, so the battery cost is negligible. Phones that can set the motor's strength get it
 * from [strength]; the others get a shorter or longer pulse instead. Nothing runs between taps.
 */
class KeyVibration(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }

    /** 0: off; 1..100: how strong each tick is. */
    var strength = 0
        set(value) {
            if (field == value) return
            field = value
            tick = null
        }

    /** The pulse for [strength], built once rather than on every key. */
    private var tick: VibrationEffect? = null

    /** One tick, if vibration is on. */
    fun tap() {
        val v = vibrator ?: return
        if (strength <= 0) return
        val effect = tick ?: build(v).also { tick = it }
        runCatching { v.vibrate(effect) }
    }

    private fun build(v: Vibrator): VibrationEffect {
        val s = strength.coerceIn(1, 100) / 100f
        return if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(TICK_MS, (s * 255).toInt().coerceIn(1, 255))
        } else {
            // No strength control: a stronger setting is a slightly longer pulse.
            VibrationEffect.createOneShot((MIN_MS + (TICK_MS - MIN_MS) * s).toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private companion object {
        const val TICK_MS = 14L
        const val MIN_MS = 4f
    }
}

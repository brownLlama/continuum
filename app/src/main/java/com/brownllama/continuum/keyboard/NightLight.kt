// The light the tree gives at night: a warm glow round its apples, and a pool of light on the grass and the animal near it.
package com.brownllama.continuum.keyboard

import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The paints of the cursor mode's night, which treats the tree as a lamp beside the road: its apples
 * glow ([halo], a warm haze round the crown), and the grass and the animal are only lit where the lamp
 * reaches. They are drawn into a layer that is slightly darkened ([layer]), warmed near the tree
 * ([tint]) and then cut away with [mask]. The light reaches all the way to the far end of the keyboard
 * and dims slowly with distance: bright at the tree, a little less a hand's width away, fading
 * gradually, and gone just at the end. The gradients are rebuilt only when the tree moves or the
 * keyboard changes size (they are immutable shaders).
 */
internal class NightLight {
    /** The layer the lit grass and animal are drawn into: a touch darker and cooler than by day. */
    val layer = Paint().apply { colorFilter = LightingColorFilter(0xFFC4CCC4.toInt(), 0) }
    /** Keeps only what the lamp reaches: full near the tree, dimming slowly, none at the far end. */
    val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    /** Warm light on the grass near the tree. */
    val tint = Paint()
    /** A warm haze round the crown, in the air. */
    val halo = Paint()
    /** How far the light reaches, in px: to the far end of the keyboard. */
    var reach = 0f
        private set
    private var key = 0L

    /**
     * The lamp's light, for a tree whose crown is at ([cx], [crownY]) and foot at ([cx], [baseY]), in a
     * keyboard [width] px wide: the light reaches the far end; the warm haze round the crown reaches [hazeReach] px.
     */
    fun fit(cx: Float, baseY: Float, crownY: Float, width: Float, hazeReach: Float) {
        val far = maxOf(cx, width - cx).coerceAtLeast(1f)
        val k = java.lang.Float.floatToIntBits(cx).toLong() * 31 + java.lang.Float.floatToIntBits(baseY) * 17L +
            java.lang.Float.floatToIntBits(crownY) * 7L + java.lang.Float.floatToIntBits(far) + java.lang.Float.floatToIntBits(hazeReach) * 13L
        if (k == key && mask.shader != null) return
        key = k
        reach = far
        // Fully lit by the tree, then a slow, slightly curved fade to nothing at the far end.
        mask.shader = RadialGradient(
            cx, baseY, far, intArrayOf(0xFF000000.toInt(), 0xF0000000.toInt(), 0x78000000, 0x30000000, 0x00000000),
            floatArrayOf(0f, 0.08f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        tint.shader = RadialGradient(cx, baseY, hazeReach * 0.9f, intArrayOf(WARM_STRONG, WARM_NONE), null, Shader.TileMode.CLAMP)
        halo.shader = RadialGradient(cx, crownY, hazeReach * 0.7f, intArrayOf(WARM_SOFT, WARM_NONE), null, Shader.TileMode.CLAMP)
    }

    private companion object {
        const val WARM_STRONG = 0x5CFFD36A
        const val WARM_SOFT = 0x4CFFD36A
        const val WARM_NONE = 0x00FFD36A
    }
}

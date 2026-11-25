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
 * ([tint]) and then cut away with [mask], so they fade out with distance and are dark beyond the pool of
 * light, like the keys. The gradients are rebuilt only when the tree moves (they are immutable shaders).
 */
internal class NightLight {
    /** The layer the lit grass and animal are drawn into: a touch darker and cooler than by day. */
    val layer = Paint().apply { colorFilter = LightingColorFilter(0xFFC4CCC4.toInt(), 0) }
    /** Keeps only what the lamp reaches: full near the tree, none at the edge of its reach. */
    val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    /** Warm light on the grass near the tree. */
    val tint = Paint()
    /** A warm haze round the crown, in the air. */
    val halo = Paint()
    private var key = 0L

    /** The lamp's light, for a tree whose crown is at ([cx], [crownY]), its foot at ([cx], [baseY]), reaching [reach] px. */
    fun fit(cx: Float, baseY: Float, crownY: Float, reach: Float) {
        val k = java.lang.Float.floatToIntBits(cx).toLong() * 31 + java.lang.Float.floatToIntBits(baseY) * 17L +
            java.lang.Float.floatToIntBits(crownY) * 7L + java.lang.Float.floatToIntBits(reach)
        if (k == key && mask.shader != null) return
        key = k
        mask.shader = RadialGradient(
            cx, baseY, reach, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
        )
        tint.shader = RadialGradient(cx, baseY, reach * 0.8f, intArrayOf(WARM_STRONG, WARM_NONE), null, Shader.TileMode.CLAMP)
        halo.shader = RadialGradient(cx, crownY, reach * 0.7f, intArrayOf(WARM_SOFT, WARM_NONE), null, Shader.TileMode.CLAMP)
    }

    private companion object {
        const val WARM_STRONG = 0x5CFFD36A
        const val WARM_SOFT = 0x4CFFD36A
        const val WARM_NONE = 0x00FFD36A
    }
}

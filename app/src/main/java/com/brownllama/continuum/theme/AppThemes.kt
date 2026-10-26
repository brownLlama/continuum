package com.brownllama.continuum.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.sqrt

/**
 * Picks the keyboard theme for the app being typed into: a calm, dark palette built from the app's
 * own brand colour (an app without one gets a colour derived from its package name). No two apps
 * share a colour: an app whose keyboard would look too close to one already given out is nudged to
 * the nearest free shade, and that choice is saved ([STORE] preferences) so each app keeps its
 * colour as new apps are installed. Nord Dark is used only when there's no app to go by.
 */
class AppThemes(private val context: Context) {

    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Brand colour per package (null = none found); computing one draws the app's icon. */
    private val brandCache = HashMap<String, Int?>()

    fun themeFor(pkg: String?): KeyboardTheme {
        val color = pkg?.let { colorFor(it) } ?: return KeyboardTheme.NORD_DARK
        return KeyboardTheme.fromColor(color)
    }

    /** The colour this app's keyboard is built from (see [KeyboardTheme.fromColor]). */
    fun colorFor(pkg: String): Int = synchronized(LOCK) {
        if (store.contains(pkg)) return store.getInt(pkg, 0)
        val brand = brandColor(pkg)?.takeIf { saturation(it) >= MIN_BRAND_SATURATION } ?: fallbackColor(pkg)
        val taken = store.all.values.filterIsInstance<Int>().map { signature(KeyboardTheme.fromColor(it)) } +
            listOf(signature(KeyboardTheme.NORD_DARK))
        val color = distinct(brand, taken)
        store.edit { putInt(pkg, color) }
        color
    }

    /** Whether the app's icon (or theme) has a colour of its own, rather than one made up for it. */
    fun hasBrandColor(pkg: String): Boolean = brandColor(pkg)?.let { saturation(it) >= MIN_BRAND_SATURATION } == true

    fun brandColor(pkg: String): Int? = brandCache.getOrPut(pkg) {
        runCatching { iconColor(pkg) }.getOrNull() ?: runCatching { themeColor(pkg) }.getOrNull()
    }

    /** Dominant saturated colour of the app's launcher icon (most recognisable brand cue). */
    private fun iconColor(pkg: String): Int? {
        val icon = context.packageManager.getApplicationIcon(pkg)
        val size = 48
        val bmp = createBitmap(size, size)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))

        // Histogram of saturated pixels by hue; average the colours of the winning hue bucket.
        val buckets = 24
        val weight = FloatArray(buckets)
        val r = FloatArray(buckets)
        val g = FloatArray(buckets)
        val b = FloatArray(buckets)
        val hsl = FloatArray(3)
        var colored = 0
        for (y in 0 until size) for (x in 0 until size) {
            val p = bmp[x, y]
            if (Color.alpha(p) < 200) continue
            ColorUtils.colorToHSL(p, hsl)
            if (hsl[1] < 0.25f || hsl[2] < 0.15f || hsl[2] > 0.85f) continue
            colored++
            val i = (hsl[0] / 360f * buckets).toInt().coerceIn(0, buckets - 1)
            val w = hsl[1]
            weight[i] += w
            r[i] += Color.red(p) * w
            g[i] += Color.green(p) * w
            b[i] += Color.blue(p) * w
        }
        bmp.recycle()
        if (colored < size * size / 20) return null
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        val w = weight[best]
        return Color.rgb((r[best] / w).toInt(), (g[best] / w).toInt(), (b[best] / w).toInt())
    }

    /** The app theme's colorPrimary, skipping the stock Material defaults most apps never change. */
    private fun themeColor(pkg: String): Int? {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        if (info.theme == 0) return null
        val pctx = context.createPackageContext(pkg, 0)
        pctx.setTheme(info.theme)
        val a = pctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary))
        val color = a.getColor(0, 0)
        a.recycle()
        if (color == 0 || color or 0xFF000000.toInt() in STOCK_PRIMARIES) return null
        return color
    }

    companion object {
        /** Saved colours; "_v2" since the calm dark palettes, so older bright picks are redone. */
        private const val STORE = "app_colors_v2"
        private val LOCK = Any()

        /**
         * Keyboards closer than this (see [distance]) count as the same colour. Calm dark palettes leave
         * little room, and about 6 still looks different side by side while fitting ~100 apps.
         */
        private const val MIN_DISTANCE = 6.0
        /** Greyer brand colours than this have no hue worth keeping; the app gets [fallbackColor]. */
        private const val MIN_BRAND_SATURATION = 0.12f

        private fun saturation(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[1]

        /** A steady, made-up colour for an app without one: the same package always gets the same hue. */
        fun fallbackColor(pkg: String): Int {
            val hue = ((pkg.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
            return ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))
        }

        /**
         * What a keyboard looks like at a glance: its background and its home-row letters, both in
         * CIE Lab (where distance follows how different colours look).
         */
        fun signature(theme: KeyboardTheme): DoubleArray = lab(theme.background) + lab(theme.homeRow)

        /**
         * How different two keyboards look. The background counts fully; the home-row letters, which
         * are small but bright and carry most of the hue, count half.
         */
        fun distance(a: DoubleArray, b: DoubleArray): Double {
            var sum = 0.0
            for (i in 0 until 3) sum += (a[i] - b[i]) * (a[i] - b[i])
            for (i in 3 until 6) sum += 0.25 * (a[i] - b[i]) * (a[i] - b[i])
            return sqrt(sum)
        }

        /**
         * [brand] if its keyboard is at least [MIN_DISTANCE] from every [taken] one (see [signature]),
         * else the closest shade (hue, lightness and saturation shifted) that is. When the colour
         * space is full, the shade farthest from its nearest neighbour.
         */
        fun distinct(brand: Int, taken: List<DoubleArray>): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(brand, hsl)
            val origin = signature(KeyboardTheme.fromColor(brand))
            var best = brand
            var bestScore = Double.NEGATIVE_INFINITY
            val candidates = buildList {
                add(brand)
                for (dh in HUE_STEPS) for (dl in LIGHT_STEPS) for (ds in SAT_STEPS) {
                    add(ColorUtils.HSLToColor(floatArrayOf(
                        (hsl[0] + dh + 360f) % 360f, (hsl[1] + ds).coerceIn(0f, 1f), (hsl[2] + dl).coerceIn(0f, 1f),
                    )))
                }
            }.distinct()
            for (c in candidates) {
                val look = signature(KeyboardTheme.fromColor(c))
                val nearest = taken.minOfOrNull { distance(it, look) } ?: Double.MAX_VALUE
                // Free shades are ranked by how little they move from the brand colour; while
                // none is free, by how far they get from the nearest taken one.
                val score = if (nearest >= MIN_DISTANCE) 1000.0 - distance(origin, look) else nearest
                if (score > bestScore) { bestScore = score; best = c }
            }
            return best
        }

        /** Shifts tried on a brand colour. fromColor compresses lightness, so its steps are wide. */
        private val HUE_STEPS = listOf(0f) + (1..8).flatMap { listOf(-6f * it, 6f * it) }
        private val LIGHT_STEPS = listOf(0f, -0.15f, 0.15f, -0.3f, 0.3f, -0.45f, 0.45f)
        private val SAT_STEPS = listOf(0f, -0.25f, 0.25f, -0.5f)

        private fun lab(color: Int) = DoubleArray(3).also { ColorUtils.colorToLAB(color, it) }

        private val STOCK_PRIMARIES = setOf(
            0xFF6200EE.toInt(), 0xFF6750A4.toInt(), 0xFF3F51B5.toInt(), 0xFF212121.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF607D8B.toInt(), 0xFF009688.toInt(),
        )
    }
}

package com.brownllama.continuum.keyboard

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.brownllama.continuum.R

/**
 * The keyboard's typeface, Maple Mono (res/font, licence in THIRD_PARTY_NOTICES.md), in the
 * weights it uses. Characters it lacks (emoji, other scripts) fall back to the system fonts.
 */
class KeyFonts(context: Context) {
    val regular = load(context, R.font.maple_mono_regular, Typeface.NORMAL)
    val medium = load(context, R.font.maple_mono_medium, Typeface.NORMAL)
    val bold = load(context, R.font.maple_mono_bold, Typeface.BOLD)

    private fun load(context: Context, id: Int, style: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.create(Typeface.MONOSPACE, style)
}

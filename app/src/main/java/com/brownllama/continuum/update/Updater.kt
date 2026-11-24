package com.brownllama.continuum.update

import android.content.Context

/** What the update check needs to know about the installed app. */
object Updater {
    /** The installed app's version name, e.g. "0.0.37". */
    fun installedVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
}

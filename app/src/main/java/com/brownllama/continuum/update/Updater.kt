package com.brownllama.continuum.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.brownllama.continuum.gif.GifFeed
import java.io.File

/** Hands a downloaded APK to Android's installer. */
object Updater {
    /** The installed app's version name, e.g. "0.0.37". */
    fun installedVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    /** Whether Android lets Continuum hand over an APK ("Install unknown apps" is allowed for it). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's page where "Install unknown apps" is switched on for Continuum. */
    fun openInstallSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens Android's installer on [apk]. It shows what is being updated and asks the user to
     * confirm; it refuses an APK not signed with the same key as the installed app.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + GifFeed.AUTHORITY_SUFFIX, apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

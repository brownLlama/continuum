package com.brownllama.continuum.update

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.brownllama.continuum.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/**
 * Settings → Check for updates: looks for a newer release on GitHub and says so. "Download" opens
 * the release's APK link in the browser, which downloads it; installing is then Android's own
 * business (the app itself never downloads or installs anything). Runs only when the user taps the
 * row, and can be cancelled.
 */
class UpdateFlow(
    private val activity: AppCompatActivity,
    private val client: UpdateClient = UpdateClient(),
    private val io: Executor = Executor { Thread(it, "update").apply { isDaemon = true }.start() },
    private val installed: String? = Updater.installedVersion(activity),
    private val open: (String) -> Unit = { url -> activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    @Volatile private var cancelled = false

    fun start() {
        cancelled = false
        checking()
        io.execute {
            val result = runCatching { client.latest() }
            main.post { if (usable()) result.fold(::onLatest) { fail() } }
        }
    }

    private fun usable() = !cancelled && !activity.isFinishing && !activity.isDestroyed

    private fun onLatest(release: Release) {
        dismiss()
        if (!Versions.isNewer(installed, release.version)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_latest_title)
                .setMessage(activity.getString(R.string.update_latest_message, installed ?: "?"))
                .setPositiveButton(android.R.string.ok, null)
                .show().also { dialog = it }
            return
        }
        val size = if (release.apkBytes > 0) Formatter.formatShortFileSize(activity, release.apkBytes) else "?"
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, release.version))
            .setMessage(activity.getString(R.string.update_available_message, installed ?: "?", size))
            .setPositiveButton(R.string.update_download) { _, _ -> runCatching { open(release.apkUrl) } }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_whats_new) { _, _ -> runCatching { open(release.pageUrl) } }
            .show().also { dialog = it }
    }

    private fun fail() {
        dismiss()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(R.string.update_check_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show().also { dialog = it }
    }

    /** A dialog with a message and a sliding progress bar, and Cancel. */
    private fun checking() {
        dismiss()
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val message = TextView(activity).apply { setText(R.string.update_checking) }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message, LinearLayout.LayoutParams(-1, -2))
            addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }
            .show()
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}

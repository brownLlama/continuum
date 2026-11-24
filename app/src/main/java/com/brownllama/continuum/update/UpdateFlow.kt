package com.brownllama.continuum.update

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.brownllama.continuum.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executor

/**
 * Settings → Check for updates: looks for a newer release on GitHub, asks, downloads it with a
 * progress bar and hands it to Android's installer. Runs only when the user taps the row; each
 * step can be cancelled.
 */
class UpdateFlow(
    private val activity: AppCompatActivity,
    private val client: UpdateClient = UpdateClient(),
    private val io: Executor = Executor { Thread(it, "update").apply { isDaemon = true }.start() },
    private val installed: String? = Updater.installedVersion(activity),
    private val install: (File) -> Unit = { Updater.install(activity, it) },
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var bar: ProgressBar? = null
    private var label: TextView? = null
    @Volatile private var cancelled = false

    private val dir get() = File(activity.cacheDir, "updates")

    fun start() {
        cancelled = false
        working(R.string.update_checking, determinate = false)
        io.execute {
            dir.deleteRecursively() // an earlier download, if any
            val result = runCatching { client.latest() }
            main.post { if (usable()) result.fold(::onLatest) { fail(R.string.update_check_failed) } }
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
            .setPositiveButton(R.string.update_now) { _, _ -> download(release) }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_whats_new) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri())) }
            }
            .show().also { dialog = it }
    }

    private fun download(release: Release) {
        cancelled = false
        working(R.string.update_downloading, determinate = true)
        io.execute {
            val result = runCatching {
                client.download(release, dir) { done, total ->
                    if (cancelled) throw UpdateException("cancelled")
                    main.post { progress(done, total) }
                }
            }
            main.post {
                if (!usable()) return@post
                result.fold(::onDownloaded) {
                    fail(if (it.message?.contains("checksum") == true) R.string.update_damaged else R.string.update_download_failed)
                }
            }
        }
    }

    private fun onDownloaded(apk: File) {
        dismiss()
        runCatching { install(apk) }
            .onSuccess { Toast.makeText(activity, R.string.update_installing, Toast.LENGTH_LONG).show() }
            .onFailure { fail(R.string.update_install_failed) }
    }

    // ---- dialogs --------------------------------------------------------------------------------

    private fun fail(message: Int) {
        dismiss()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show().also { dialog = it }
    }

    /** A dialog with a message and a progress bar (a sliding one until the size is known), and Cancel. */
    private fun working(text: Int, determinate: Boolean) {
        dismiss()
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val message = TextView(activity).apply { setText(text) }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = !determinate
            max = 100
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message, LinearLayout.LayoutParams(-1, -2))
            addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        }
        label = message
        bar = progress
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }
            .show()
    }

    private fun progress(done: Long, total: Long) {
        val b = bar ?: return
        if (total > 0) {
            b.isIndeterminate = false
            b.progress = (done * 100 / total).toInt()
            label?.text = activity.getString(
                R.string.update_progress, Formatter.formatShortFileSize(activity, done), Formatter.formatShortFileSize(activity, total),
            )
        } else {
            b.visibility = View.VISIBLE
        }
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}

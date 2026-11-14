package com.brownllama.continuum

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Small files (the clipboard history, emoji counts) are read and written off the main thread, so
 * opening the keyboard and typing never wait for the disk.
 */
internal object DiskIo {
    /**
     * One background thread for the whole app, so reads and writes of a file never overlap or run
     * out of order (also across a restart of the keyboard service). It ends when idle.
     */
    val executor: Executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "disk-io").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /**
     * Writes [text] to [file] through a temporary file, so a crash mid-write never leaves half a
     * file. On [io] when given, else at once.
     */
    fun write(file: File, text: String, io: Executor?) {
        val task = Runnable {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
        run(task, io)
    }

    /** Runs [task] on [io], or here and now without one (or if it no longer takes work). */
    fun run(task: Runnable, io: Executor?) {
        if (io == null || runCatching { io.execute(task) }.isFailure) task.run()
    }
}

/**
 * A file being read into its owner's fields: at once without an [io] executor, in the background
 * with one. The owner calls [await] before touching those fields, which waits (only then, and only
 * for what is left) until the read has finished, so nothing ever sees half-loaded data.
 */
internal class Loading(io: Executor?, load: () -> Unit) {
    @Volatile private var done = false
    /** The thread doing the read: it may use the owner while it loads. */
    @Volatile private var loader: Thread? = null
    private val latch = CountDownLatch(1)

    init {
        DiskIo.run(
            Runnable {
                loader = Thread.currentThread()
                try {
                    load()
                } finally {
                    loader = null
                    done = true
                    latch.countDown()
                }
            },
            io,
        )
    }

    fun await() {
        if (done || Thread.currentThread() === loader) return
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

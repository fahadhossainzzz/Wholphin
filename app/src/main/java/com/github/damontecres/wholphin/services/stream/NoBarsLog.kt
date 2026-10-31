package com.github.damontecres.wholphin.services.stream

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * NoBars: a log file kept on the TV, so a whole evening of playback survives (the TV itself keeps
 * only its last few hundred error lines). Warnings and errors from the app, all NoBars diagnostics
 * and every per-film report go here. Two files of [MAX_BYTES] each, the older one replaced when the
 * newer is full. "Send app logs" includes the most recent part of it.
 */
object NoBarsLog {
    private const val MAX_BYTES = 3L * 1024 * 1024
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "nobars-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null

    private fun current() = dir?.let { File(it, "nobars-log.txt") }

    private fun previous() = dir?.let { File(it, "nobars-log.1.txt") }

    fun tree(context: Context): Timber.Tree {
        dir = File(context.filesDir, "nobars").apply { mkdirs() }
        return object : Timber.Tree() {
            override fun isLoggable(
                tag: String?,
                priority: Int,
            ): Boolean = priority >= Log.WARN

            override fun log(
                priority: Int,
                tag: String?,
                message: String,
                t: Throwable?,
            ) {
                val level =
                    when (priority) {
                        Log.ERROR -> "E"
                        Log.WARN -> "W"
                        Log.ASSERT -> "A"
                        else -> "I"
                    }
                val line =
                    buildString {
                        append(synchronized(stamp) { stamp.format(Date()) })
                        append(' ').append(level).append(' ')
                        if (tag != null) append(tag).append(": ")
                        append(message)
                        if (t != null && !message.contains(t.javaClass.simpleName)) {
                            append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
                        }
                        append('\n')
                    }
                write(line)
            }
        }
    }

    /** Writes a block of text (a per-film report) to the log as it is. */
    fun writeBlock(text: String) = write(text.trimEnd() + "\n")

    private fun write(text: String) {
        writer.execute {
            try {
                val file = current() ?: return@execute
                if (file.length() > MAX_BYTES) {
                    previous()?.let { old ->
                        old.delete()
                        file.renameTo(old)
                    }
                }
                current()?.appendText(text)
            } catch (e: Exception) {
                // Logging must never break playback
            }
        }
    }

    /** The most recent [maxBytes] of the log, oldest first. */
    fun tail(maxBytes: Long): String {
        val parts = listOfNotNull(previous(), current()).filter { it.exists() }
        val total = parts.sumOf { it.length() }
        var skip = (total - maxBytes).coerceAtLeast(0)
        val sb = StringBuilder()
        for (f in parts) {
            val len = f.length()
            if (skip >= len) {
                skip -= len
                continue
            }
            try {
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(skip)
                    val bytes = ByteArray((len - skip).toInt())
                    raf.readFully(bytes)
                    sb.append(String(bytes))
                }
            } catch (e: Exception) {
                sb.append("[could not read ${f.name}: ${e.message}]\n")
            }
            skip = 0
        }
        return sb.toString()
    }
}

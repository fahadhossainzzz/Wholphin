@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * NoBars: live numbers about the video stream, shared by the loader, the buffer, the stall guard,
 * the health log, the per-film report and the debug overlay.
 */
object StreamHealth {
    // ---------------------------------------------------------------- data arriving

    /** Bytes handed to the player. */
    val bytesDelivered = AtomicLong(0)

    /** elapsedRealtime of the last byte handed to the player. */
    val lastByteAt = AtomicLong(SystemClock.elapsedRealtime())

    /** Time spent waiting inside network reads, to tell a slow network from a player that is not asking. */
    val readNanos = AtomicLong(0)

    /** How many times the player (re)opened the stream. */
    val opens = AtomicLong(0)

    /** How long the server took to answer the latest request (until the response headers). */
    @Volatile var lastOpenMs: Long = 0

    fun onBytes(count: Int) {
        if (count > 0) {
            bytesDelivered.addAndGet(count.toLong())
            lastByteAt.set(SystemClock.elapsedRealtime())
        }
    }

    fun msSinceLastByte(): Long = SystemClock.elapsedRealtime() - lastByteAt.get()

    fun markActivity() = lastByteAt.set(SystemClock.elapsedRealtime())

    // ---------------------------------------------------------------- memory

    fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    fun heapMax(): Long = Runtime.getRuntime().maxMemory()

    fun heapRatio(): Float = heapUsed().toFloat() / heapMax().toFloat()

    /** TV-wide RAM left above Android's low-memory line, in MB (updated every second). */
    @Volatile var systemSpareMb: Long = Long.MAX_VALUE

    @Volatile var systemAvailMb: Long = 0

    /** The read-ahead buffer's own size and its current budget, in bytes (exact, from the allocator). */
    @Volatile var bufferBytes: Long = 0

    @Volatile var bufferBudget: Long = 0

    @Volatile var bufferState: String = ""

    // ---------------------------------------------------------------- live figures for the overlay

    @Volatile var aheadMs: Long = 0

    /** Rate of data into the player over the last 2 s. */
    @Volatile var netMbps: Float = 0f

    /** Speed of the network while a read is in progress (what the connection can do). */
    @Volatile var readMbps: Float = 0f

    /** Share of the time the player waited on the network. */
    @Volatile var readBusy: Int = 0

    @Volatile var opensPerMin: Int = 0

    @Volatile var wifiRssi: Int = 0

    @Volatile var wifiLinkMbps: Int = 0

    @Volatile var wifiFreqMhz: Int = 0

    @Volatile var reconnects: Int = 0

    @Volatile var reloads: Int = 0

    @Volatile var rebuilds: Int = 0

    @Volatile var recentWaits: String = "none"

    @Volatile var recentDrops: Int = 0

    @Volatile var lastEvent: String = ""

    @Volatile var reportStatus: String = "not sent yet"

    fun resetCounters() {
        reconnects = 0
        reloads = 0
        rebuilds = 0
        recentWaits = "none"
        recentDrops = 0
        lastEvent = ""
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun now(): String = synchronized(clock) { clock.format(Date()) }

    /** Something worth knowing happened: shown on the overlay and written to the log. */
    fun event(message: String) {
        lastEvent = "${now()} $message"
        Timber.e("NoBars event: %s", message)
    }

    // ---------------------------------------------------------------- connections

    private val recentCalls = ConcurrentLinkedDeque<Call>()

    fun trackingCallFactory(client: OkHttpClient): Call.Factory =
        Call.Factory { request: Request ->
            client.newCall(request).also { call ->
                recentCalls.addLast(call)
                while (recentCalls.size > 8) recentCalls.pollFirst()
            }
        }

    /** Cuts the player's open connections; the loader retries at once from where it stopped. */
    fun cancelPlayerCalls(): Int {
        var n = 0
        recentCalls.forEach {
            if (!it.isCanceled()) {
                it.cancel()
                n++
            }
        }
        recentCalls.clear()
        return n
    }

    /** Wraps the player's data source so every byte, open and wait is counted. */
    fun countingFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { CountingDataSource(upstream.createDataSource()) }

    private class CountingDataSource(
        private val inner: DataSource,
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            markActivity()
            val t = SystemClock.elapsedRealtime()
            try {
                return inner.open(dataSpec)
            } finally {
                val ms = SystemClock.elapsedRealtime() - t
                lastOpenMs = ms
                if (ms >= 1_500) Timber.e("NoBars slow server answer: %d ms for byte %d", ms, dataSpec.position)
                markActivity()
            }
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val t = System.nanoTime()
            val n = inner.read(buffer, offset, length)
            readNanos.addAndGet(System.nanoTime() - t)
            if (n > 0) onBytes(n)
            return n
        }

        override fun getUri(): Uri? = inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

        override fun close() = inner.close()
    }
}

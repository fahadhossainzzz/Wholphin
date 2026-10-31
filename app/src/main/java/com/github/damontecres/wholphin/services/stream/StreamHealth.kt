package com.github.damontecres.wholphin.services.stream

import android.os.SystemClock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * NoBars: live numbers about the video stream, shared by the loader, the stall guard, the health
 * log and the debug overlay.
 */
object StreamHealth {
    /** Bytes handed to the player (from the network or the USB drive). */
    val bytesDelivered = AtomicLong(0)

    /** elapsedRealtime of the last byte handed to the player. */
    val lastByteAt = AtomicLong(SystemClock.elapsedRealtime())

    fun onBytes(count: Int) {
        if (count > 0) {
            bytesDelivered.addAndGet(count.toLong())
            lastByteAt.set(SystemClock.elapsedRealtime())
        }
    }

    fun msSinceLastByte(): Long = SystemClock.elapsedRealtime() - lastByteAt.get()

    fun markActivity() = lastByteAt.set(SystemClock.elapsedRealtime())

    // Heap of this app: used against the real limit (not the currently claimed size)
    fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    fun heapMax(): Long = Runtime.getRuntime().maxMemory()

    fun heapRatio(): Float = heapUsed().toFloat() / heapMax().toFloat()

    /** TV-wide RAM left above Android's low-memory line, in MB (updated every second by the stall guard). */
    @Volatile var systemSpareMb: Long = Long.MAX_VALUE

    @Volatile var systemAvailMb: Long = 0

    /** Bytes and time spent inside reads, per source, to see which one is the bottleneck. */
    class Meter {
        val bytes = AtomicLong(0)
        val nanos = AtomicLong(0)

        fun add(
            count: Int,
            elapsedNanos: Long,
        ) {
            if (count > 0) bytes.addAndGet(count.toLong())
            nanos.addAndGet(elapsedNanos)
        }
    }

    val usbReads = Meter()
    val netReads = Meter()
    val prefetchReads = Meter()

    /** How many times the player (re)opened the stream. Many reopens per minute = slow. */
    val opens = AtomicLong(0)

    // Updated by the stall guard every 2 s
    @Volatile var usbMbps: Float = 0f

    @Volatile var usbBusy: Int = 0

    @Volatile var netOnlyMbps: Float = 0f

    @Volatile var netBusy: Int = 0

    @Volatile var prefetchMbps: Float = 0f

    @Volatile var opensPerMin: Int = 0

    @Volatile var bufferState: String = ""

    // Updated by the stall guard, shown by the overlay
    @Volatile var aheadMs: Long = 0

    @Volatile var netMbps: Float = 0f

    @Volatile var usbStatus: String = "off"

    @Volatile var usbAheadSec: Long = 0

    @Volatile var reconnects: Int = 0

    @Volatile var reloads: Int = 0

    @Volatile var rebuilds: Int = 0

    fun resetCounters() {
        reconnects = 0
        reloads = 0
        rebuilds = 0
    }

    /** Calls currently or recently used by the video player, so a stuck one can be cut. */
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
}

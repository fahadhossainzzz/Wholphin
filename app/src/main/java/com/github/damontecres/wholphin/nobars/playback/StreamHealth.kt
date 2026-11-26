@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.nobars.playback

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

    /** elapsedRealtime of the last real byte; unlike [lastByteAt] never moved by [markActivity]. */
    val lastRealByteAt = AtomicLong(SystemClock.elapsedRealtime())

    /** Time spent waiting inside network reads, to tell a slow network from a player that is not asking. */
    val readNanos = AtomicLong(0)

    /** How many times the player (re)opened the stream. */
    val opens = AtomicLong(0)

    /** How long the server took to answer the latest request (until the response headers). */
    @Volatile var lastOpenMs: Long = 0

    fun onBytes(count: Int) {
        if (count > 0) {
            bytesDelivered.addAndGet(count.toLong())
            val now = SystemClock.elapsedRealtime()
            lastByteAt.set(now)
            lastRealByteAt.set(now)
        }
    }

    fun msSinceLastByte(): Long = SystemClock.elapsedRealtime() - lastByteAt.get()

    fun msSinceRealByte(): Long = SystemClock.elapsedRealtime() - lastRealByteAt.get()

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

    /** Speed of the last piece the player downloaded (Mbps) */
    @Volatile var lastChunkMbps: Float = 0f

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

    // ---------------------------------------------------------------- v114: screen, heat, Wi-Fi node, subtitles

    /** The screen's refresh rate now (Hz) and the rates the TV offers apps at this resolution */
    @Volatile var screenHz: Float = 0f

    @Volatile var offeredHz: List<Float> = emptyList()

    /** The film's frame rate (fps), 0 when unknown */
    @Volatile var filmFps: Float = 0f

    /** "Dolby Vision ✓", "HDR10 (Dolby Vision not used)", "SDR" */
    @Volatile var pictureOut: String = ""

    /** Android's thermal status: 0 none … 6 shutdown */
    @Volatile var thermal: Int = 0

    /** The Wi-Fi access point (Deco unit) the TV is joined to, when Android lets the app see it */
    @Volatile var wifiNode: String = ""

    @Volatile var wifiSwitches: Int = 0

    @Volatile var subtitleDelayMs: Long = 0

    /** How long the current film took to start playing (ms), 0 until it has */
    @Volatile var startMs: Long = 0

    /** What happened during this film, with times, for its report */
    val timeline = ConcurrentLinkedDeque<String>()

    /** Screen rate vs film rate: "60 Hz · film 23.976 fps ⚠" and whether it's even. */
    fun displayLine(): Pair<String, Boolean> {
        val hz = screenHz
        val fps = filmFps
        if (hz <= 0f) return "?" to true
        val screen = hzText(hz) + " Hz"
        if (fps <= 0f) return screen to true
        val even = matches(hz, fps)
        return "$screen · film ${fpsText(fps)} fps ${if (even) "✓" else "⚠"}" to even
    }

    /** A screen rate shows every frame for the same time when it is a whole multiple of the film's rate. */
    fun matches(
        hz: Float,
        fps: Float,
    ): Boolean {
        if (hz <= 0f || fps <= 0f) return true
        val r = hz / fps
        return kotlin.math.abs(r - kotlin.math.round(r)) < 0.02f && r >= 0.98f
    }

    fun hzText(hz: Float): String = String.format(Locale.US, "%.3f", hz).trimEnd('0').trimEnd('.')

    fun fpsText(fps: Float): String = String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.')

    fun heatText(status: Int = thermal): String =
        when (status) {
            0 -> "normal"
            1 -> "warm"
            2 -> "warm (slowing slightly)"
            3 -> "hot – slowing down"
            4, 5, 6 -> "very hot – throttling"
            else -> "?"
        }

    /** Megabits a second as megabytes a second: "2.0 MB/s", "35 MB/s". */
    fun mbs(mbps: Float): String {
        val v = mbps / 8f
        return when {
            v <= 0f -> "0 MB/s"
            v < 0.095f -> String.format(Locale.US, "%.2f MB/s", v)
            v < 10f -> String.format(Locale.US, "%.1f MB/s", v)
            else -> String.format(Locale.US, "%.0f MB/s", v)
        }
    }

    /** 146800 ms → "2 min 27 s"; 6500 → "6.5 s" */
    fun span(ms: Long): String =
        if (ms >= 60_000) "${ms / 60_000} min ${ms / 1000 % 60} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** Bitrate of the film's own data just loaded (Mbps): bytes in ÷ seconds of film they cover, ~10 s windows */
    @Volatile var sceneMbps: Float = 0f

    /** Highest [sceneMbps] over the last minute or so of loaded film */
    @Volatile var scenePeakMbps: Float = 0f

    /** What reaches the TV's audio output, e.g. "PCM 2.0 (decoded)" or "E-AC-3 passthrough" */
    @Volatile var audioOut: String = ""

    /** The audio decoder in use (empty when the sound is passed through untouched) */
    @Volatile var audioDecoder: String = ""

    /** "decoded by the TV's chip (OMX.MTK…) → PCM 2.0 (decoded)", "app's FFmpeg decoder → …", "passed through untouched → …" */
    fun audioPath(
        decoder: String,
        out: String,
    ): String {
        val who =
            when {
                decoder.isBlank() -> if (out.contains("passthrough")) "not decoded" else "decoder not reported"
                decoder.contains("ffmpeg", ignoreCase = true) -> "app's FFmpeg decoder"
                decoder.startsWith("c2.android") || decoder.startsWith("OMX.google") -> "Android's own decoder ($decoder)"
                else -> "TV's chip ($decoder)"
            }
        return "$who → ${out.ifBlank { "output not reported yet" }}"
    }

    fun resetCounters() {
        sceneMbps = 0f
        scenePeakMbps = 0f
        audioOut = ""
        audioDecoder = ""
        reconnects = 0
        reloads = 0
        rebuilds = 0
        recentWaits = "none"
        recentDrops = 0
        lastEvent = ""
        wifiSwitches = 0
        startMs = 0
        timeline.clear()
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun now(): String = synchronized(clock) { clock.format(Date()) }

    /** Something worth knowing happened: shown on the overlay and written to the log. */
    fun event(message: String) {
        lastEvent = "${now()} $message"
        timeline.addLast("${now()} $message")
        while (timeline.size > 60) timeline.pollFirst()
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

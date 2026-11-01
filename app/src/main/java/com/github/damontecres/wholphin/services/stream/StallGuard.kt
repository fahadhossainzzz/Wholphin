@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import coil3.imageLoader
import com.github.damontecres.wholphin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NoBars: keeps playback moving instead of waiting for it to freeze, and records what happened.
 *
 * Rescue ladder (direct play only, and only once the film has started playing: while a film opens
 * the connection's own 5 s timeouts apply, since cutting a connection mid-open broke the opening),
 * four checks a second:
 * 1. No data while loading: cut the connection, the loader reconnects at once (invisible). Waits
 *    2 s, then 4, 8 and 15 s if reconnecting does not help (a busy disk needs time, and cutting
 *    every 2 s would keep it from ever answering); at least 5 s right after a skip.
 * 2. Under 8 s ahead and still nothing: reload from the current spot, keeping decoder and tracks.
 * 3. Under 2 s ahead / stalled and still nothing, or memory critically full: rebuild the player at
 *    the same spot (1-2 s pause instead of a freeze). At most 3 times in 10 minutes.
 *
 * Memory: above 60 % (or TV RAM short) cached images are dropped; the buffer manages itself
 * ([ElasticLoadControl]); above 92 % after a collection, step 3 frees everything the player holds.
 *
 * Diagnostics: live figures for the overlay ([StreamHealth]), a health line every 30 s, an event
 * line for every wait, skip, rescue and frame-drop burst, and at the end of each film a report
 * handed to [onReport] (sent to the server and kept in the TV's log file).
 *
 * Must run on the player's (main) thread.
 */
class StallGuard(
    private val context: Context,
    private val player: ExoPlayer,
    private val isDirectPlay: () -> Boolean,
    private val describe: () -> String,
    private val onReport: (String) -> Unit,
    source: () -> MediaSourceInfo? = { null },
    title: () -> String = { "?" },
    playMethod: () -> String = { "?" },
    transcodeReason: () -> String? = { null },
    private val otherPlayback: suspend () -> Pair<List<String>, Boolean>? = { null },
) {
    private val diag = DiagCollector(context, player, source, title, playMethod, transcodeReason)
    private var lastDiag = 0L

    @Volatile private var serverBase: String? = null

    private var lastReconnect = 0L
    private var reconnectStreak = 0
    private var lastReload = 0L
    private var lastRebuild = 0L
    private var lastImageTrim = 0L
    private var lastGcHint = 0L
    private var lastHealthLog = 0L
    private var lastSystemCheck = 0L
    private var lastWifiCheck = 0L
    private var starvingSince = 0L
    private var rateAt = 0L
    private var rateBytes = 0L
    private var rateNanos = 0L
    private var rateOpens = 0L
    private var gaveUp = false
    private val rebuildTimes = ArrayDeque<Long>()
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val memInfo = ActivityManager.MemoryInfo()

    private var session = Session()

    /** Everything recorded about one film, for its report. */
    private class Session {
        val startedAt = System.currentTimeMillis()
        val reportId = "R-" + Integer.toHexString((System.nanoTime() and 0xFFFF).toInt()).uppercase().padStart(4, '0')
        var thermalMax = 0
        var cpuMax = 0
        var judder = false
        var judderText = ""
        var videoText = ""
        var audioText = ""
        var fileText = ""
        val otherPlayback = linkedSetOf<String>()
        var macBusy = false
        var description = ""
        var firstReady = false
        var bufferingSince = 0L
        var pendingSeek: Seek? = null
        val waits = mutableListOf<Wait>()
        val seeks = mutableListOf<Seek>()
        val dropBursts = mutableListOf<Pair<Long, Int>>()
        var dropped = 0
        var aheadMin = Long.MAX_VALUE
        var aheadSum = 0L
        var aheadSamples = 0L
        var bufferPeak = 0L
        var budgetAtPeak = 0L
        var holds = 0
        var lowMemoryHolds = 0
        var lastBufferState = ""
        var heapPeak = 0f
        var spareMin = Long.MAX_VALUE
        var readMbpsSum = 0.0
        var readMbpsSamples = 0
        var readMbpsMin = Float.MAX_VALUE
        var rssiSum = 0L
        var rssiSamples = 0
        var rssiMin = 0
        var linkMbps = 0
        var freqMhz = 0
        var lastPositionMs = 0L
        var bytesAtStart = StreamHealth.bytesDelivered.get()
        var playingMs = 0L
        var reconnects = 0
        var reloads = 0
        var rebuilds = 0
    }

    private data class Wait(
        val positionMs: Long,
        val ms: Long,
        val cause: String,
        val at: Long,
    )

    private data class Seek(
        val fromMs: Long,
        val toMs: Long,
        val at: Long,
        var readyMs: Long = -1,
    )

    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    StreamHealth.event("Android asked to free memory (level $level), dropped cached images")
                    trimImages()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = trimImages()
        }

    private val listener =
        object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val seek = Seek(oldPosition.positionMs, newPosition.positionMs, SystemClock.elapsedRealtime())
                    session.pendingSeek = seek
                    session.seeks.add(seek)
                    if (session.seeks.size > 200) session.seeks.removeAt(0)
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                // A new film (next episode, playlist): close the previous one's report
                if (session.firstReady) finishSession("stream restarted (track change, resume or next item)")
            }
        }

    private val analytics =
        object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                session.dropped += droppedFrames
                recentDrops.addLast(SystemClock.elapsedRealtime() to droppedFrames)
                if (droppedFrames >= 10) {
                    session.dropBursts.add(player.currentPosition to droppedFrames)
                    StreamHealth.event("$droppedFrames frames dropped at ${clock(player.currentPosition)}")
                }
            }
        }

    private val recentDrops = ArrayDeque<Pair<Long, Int>>()

    companion object {
        /** Writes why the app's previous runs ended (killed for memory, crashed, ...) to the log. */
        fun logPreviousExits(context: Context) {
            try {
                val am = context.getSystemService(ActivityManager::class.java) ?: return
                val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                Timber.e(
                    "NoBars device: %s, RAM %d MB total, %d MB free, low-memory line %d MB, app limit %d MB, lowRam=%b, app %s",
                    Build.MODEL,
                    info.totalMem shr 20,
                    info.availMem shr 20,
                    info.threshold shr 20,
                    Runtime.getRuntime().maxMemory() shr 20,
                    am.isLowRamDevice,
                    BuildConfig.VERSION_NAME,
                )
                if (Build.VERSION.SDK_INT >= 30) {
                    am.getHistoricalProcessExitReasons(null, 0, 3).forEach {
                        Timber.e(
                            "NoBars previous exit: %s at %tF %<tT, reason=%d (%s), importance=%d, pss=%d MB, rss=%d MB",
                            it.processName,
                            Date(it.timestamp),
                            it.reason,
                            it.description ?: "",
                            it.importance,
                            it.pss shr 10,
                            it.rss shr 10,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not read exit reasons")
            }
        }

        /** How long a stream may go without data before reconnecting: 1st, 2nd, 3rd, later tries. */
        private val RECONNECT_AFTER_MS = longArrayOf(2_000, 4_000, 8_000, 15_000)

        /** 1:02:03 or 5:16 */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) {
                String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            } else {
                String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            }
        }

        private val wallClock = SimpleDateFormat("HH:mm:ss", Locale.US)

        private fun wall(ms: Long): String = synchronized(wallClock) { wallClock.format(Date(ms)) }
    }

    suspend fun run() {
        StreamHealth.resetCounters()
        context.registerComponentCallbacks(trimCallbacks)
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.addAnalyticsListener(diag.analytics)
        try {
            coroutineScope {
                launch(Dispatchers.IO) { pingLoop() }
                launch(Dispatchers.IO) { otherPlaybackLoop() }
                while (isActive) {
                    try {
                        tick()
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars stall guard tick failed")
                    }
                    delay(250)
                }
            }
        } finally {
            context.unregisterComponentCallbacks(trimCallbacks)
            try {
                player.removeListener(listener)
                player.removeAnalyticsListener(analytics)
                player.removeAnalyticsListener(diag.analytics)
            } catch (e: Exception) {
                // player already released
            }
            Diag.snapshot = null
            if (session.firstReady) finishSession(endReason())
        }
    }

    /** Round trip to the server every 10 s (a tiny request Jellyfin answers without touching the disks). */
    private suspend fun pingLoop() {
        while (currentCoroutineContext().isActive) {
            val base = serverBase
            if (base != null) {
                try {
                    val t = SystemClock.elapsedRealtime()
                    val conn = URL("$base/System/Ping").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 3_000
                    conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    val ms = SystemClock.elapsedRealtime() - t
                    StreamHealth.pingMs = ms
                    if (ms > StreamHealth.pingMaxMs) StreamHealth.pingMaxMs = ms
                } catch (e: Exception) {
                    StreamHealth.pingMs = 9_999
                }
            }
            delay(10_000)
        }
    }

    /** Other playback and conversions on the server, every 15 s. */
    private suspend fun otherPlaybackLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                otherPlayback()?.let { (lines, busy) ->
                    diag.macLines = lines
                    diag.macBusy = busy
                }
            } catch (e: Exception) {
                diag.macLines = listOf("could not ask the server")
            }
            delay(15_000)
        }
    }

    private fun endReason(): String {
        val error = player.playerError
        return when {
            error != null -> "playback error: ${error.errorCodeName} ${error.message.orEmpty()}"
            player.playbackState == Player.STATE_ENDED -> "film finished"
            else -> "left the player at ${clock(session.lastPositionMs)}"
        }
    }

    private fun trimImages() {
        try {
            context.imageLoader.memoryCache?.clear()
        } catch (e: Exception) {
            Timber.w(e, "Could not clear image cache")
        }
        lastImageTrim = SystemClock.elapsedRealtime()
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val s = session
        val state = player.playbackState
        val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        StreamHealth.aheadMs = ahead
        if (state != Player.STATE_IDLE) s.lastPositionMs = player.currentPosition

        // TV-wide memory every second, Wi-Fi every 5 s
        if (now - lastSystemCheck >= 1_000) {
            lastSystemCheck = now
            activityManager?.getMemoryInfo(memInfo)
            StreamHealth.systemAvailMb = memInfo.availMem shr 20
            StreamHealth.systemSpareMb = (memInfo.availMem - memInfo.threshold) shr 20
        }
        if (now - lastWifiCheck >= 5_000) {
            lastWifiCheck = now
            readWifi()
        }

        // Rates every 2 s
        if (now - rateAt >= 2_000) {
            val bytes = StreamHealth.bytesDelivered.get()
            val nanos = StreamHealth.readNanos.get()
            val opens = StreamHealth.opens.get()
            if (rateAt > 0) {
                val ms = (now - rateAt).toFloat()
                val dBytes = bytes - rateBytes
                val dNanos = nanos - rateNanos
                StreamHealth.netMbps = dBytes * 8f / 1000f / ms
                StreamHealth.readMbps = if (dNanos > 0) dBytes * 8f * 1000f / dNanos else 0f
                StreamHealth.readBusy = (dNanos / 1e4f / ms).toInt().coerceIn(0, 100)
                StreamHealth.opensPerMin = ((opens - rateOpens) * 60_000f / ms).toInt()
                // The connection's speed, measured only while the player was really reading
                if (StreamHealth.readBusy >= 30 && dBytes > 256 * 1024) {
                    s.readMbpsSum += StreamHealth.readMbps
                    s.readMbpsSamples++
                    s.readMbpsMin = minOf(s.readMbpsMin, StreamHealth.readMbps)
                }
            }
            rateBytes = bytes
            rateNanos = nanos
            rateOpens = opens
            rateAt = now
            updateRecent(now)
        }

        var ratio = StreamHealth.heapRatio()
        s.heapPeak = maxOf(s.heapPeak, ratio)
        if (StreamHealth.systemSpareMb != Long.MAX_VALUE) s.spareMin = minOf(s.spareMin, StreamHealth.systemSpareMb)
        if (StreamHealth.bufferBytes > s.bufferPeak) {
            s.bufferPeak = StreamHealth.bufferBytes
            s.budgetAtPeak = StreamHealth.bufferBudget
        }
        val bufferState = StreamHealth.bufferState
        if (bufferState != s.lastBufferState) {
            if (bufferState.startsWith("holding")) {
                s.holds++
                s.lowMemoryHolds++
            } else if (bufferState.startsWith("full (") && bufferState.contains("budget")) {
                s.holds++
            }
            s.lastBufferState = bufferState
        }

        if ((ratio >= 0.60f || StreamHealth.systemSpareMb < 250) && now - lastImageTrim >= 30_000) {
            Timber.e("NoBars memory: app %d%%, TV spare %d MB, dropping cached images", (ratio * 100).toInt(), StreamHealth.systemSpareMb)
            trimImages()
        }
        if (now - lastHealthLog >= 30_000) {
            lastHealthLog = now
            logHealth(ratio, ahead)
        }

        trackWaits(now, state, ahead)

        if (now - lastDiag >= 1_000) {
            lastDiag = now
            updateDiagnostics()
        }

        if (!player.playWhenReady || state == Player.STATE_IDLE || state == Player.STATE_ENDED || player.playerError != null) {
            starvingSince = 0
            return
        }
        if (state == Player.STATE_READY) {
            s.aheadMin = minOf(s.aheadMin, ahead)
            s.aheadSum += ahead
            s.aheadSamples++
            s.playingMs += 250
        }

        // Memory at the edge even after a collection: free everything the player holds
        if (ratio >= 0.92f) {
            if (now - lastGcHint >= 10_000) {
                lastGcHint = now
                Runtime.getRuntime().gc()
                ratio = StreamHealth.heapRatio()
            }
            if (ratio >= 0.92f && now - lastRebuild >= 60_000) {
                rebuild("memory ${(ratio * 100).toInt()}% full")
                return
            }
        }

        // Server-side streams (transcodes) legitimately pause; the ladder is for direct play only
        if (!isDirectPlay()) return

        // Not while the film is opening (or reopening after a track change)
        if (!s.firstReady) return
        // Nothing left to load: the whole film up to its end is in the buffer
        val duration = player.duration
        if (duration != C.TIME_UNSET && player.bufferedPosition >= duration - 1_000) {
            starvingSince = 0
            return
        }

        val loading = player.isLoading
        val idle = StreamHealth.msSinceLastByte()
        if (idle < 1_000) reconnectStreak = 0

        // Step 1: data stopped arriving while the player wants it: reconnect, waiting longer each time
        val afterSeek = s.pendingSeek?.let { now - it.at < 10_000 } == true
        val patience =
            RECONNECT_AFTER_MS[minOf(reconnectStreak, RECONNECT_AFTER_MS.size - 1)]
                .let { if (afterSeek) maxOf(it, 5_000L) else it }
        if (loading && idle >= patience && now - lastReconnect >= patience) {
            val cut = StreamHealth.cancelPlayerCalls()
            StreamHealth.reconnects++
            s.reconnects++
            reconnectStreak++
            lastReconnect = now
            StreamHealth.event(
                "reconnect #$reconnectStreak: no data for ${idle / 1000.0} s, ${ahead / 1000} s ahead ($cut connections cut; " +
                    "server's last answer took ${StreamHealth.lastOpenMs} ms)",
            )
        }

        // The buffer refusing to load while the player starves (should never happen): override it
        if (!loading && ahead < 5_000) {
            if (starvingSince == 0L) starvingSince = now
            if (now - starvingSince >= 1_000) {
                ElasticLoadControl.current?.emergencyUntil = System.currentTimeMillis() + 20_000
            }
        } else {
            starvingSince = 0
        }
        val starvedFor = if (starvingSince == 0L) 0L else now - starvingSince

        // Step 2: still stuck with under 8 s left: reload from here, keeping decoder and tracks
        val stuck2 = ahead < 8_000 && ((loading && idle >= 4_000) || starvedFor >= 4_000)
        if (stuck2 && now - lastReload >= 10_000 && now - lastReconnect >= 1_500) {
            StreamHealth.reloads++
            s.reloads++
            lastReload = now
            StreamHealth.event("reload: still no data after ${idle / 1000.0} s, ${ahead / 1000} s ahead")
            StreamHealth.markActivity()
            player.seekTo(player.currentPosition)
            return
        }

        // Step 3: about to run dry (or already stalled) and step 2 did not help: rebuild
        val stuck3 =
            (ahead < 2_000 || state == Player.STATE_BUFFERING) &&
                ((loading && idle >= 6_000) || starvedFor >= 6_000) &&
                lastReload != 0L &&
                now - lastReload in 2_000L..30_000L
        if (stuck3 && now - lastRebuild >= 20_000) {
            rebuild("no data for $idle ms")
        }
    }

    private fun episodeStats(): EpisodeStats {
        val s = session
        val ready = s.seeks.filter { it.readyMs >= 0 }
        return EpisodeStats(
            reportId = s.reportId,
            playingMs = s.playingMs,
            waits = s.waits.size,
            stalls = s.waits.count { it.cause == "stall" },
            waitSeconds = s.waits.sumOf { it.ms } / 1000.0,
            skips = s.seeks.size,
            skipAvgMs = if (ready.isEmpty()) null else ready.sumOf { it.readyMs } / ready.size,
            reconnects = s.reconnects,
            reloads = s.reloads,
            rebuilds = s.rebuilds,
            readMbpsAvg = if (s.readMbpsSamples > 0) s.readMbpsSum / s.readMbpsSamples else 0.0,
            lastTrimAt = if (lastImageTrim == 0L) "never" else wall(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - lastImageTrim)),
        )
    }

    /** Refreshes the overlay's figures and keeps the worst values of the episode for its report. */
    private fun updateDiagnostics() {
        player.currentMediaItem?.localConfiguration?.uri?.let { uri ->
            if (uri.scheme?.startsWith("http") == true && uri.host != null) {
                serverBase = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            }
        }
        val snapshot = diag.build(episodeStats())
        Diag.snapshot = snapshot
        val s = session
        s.thermalMax = maxOf(s.thermalMax, diag.thermal)
        s.cpuMax = maxOf(s.cpuMax, diag.cpuPercent)
        snapshot.sections.firstOrNull { it.title == "VIDEO" }?.let { v ->
            s.videoText = v.lines.filter { !it.startsWith("shown") }.joinToString(" · ")
            if (diag.judder && !s.judder) {
                s.judder = true
                s.judderText = v.lines.firstOrNull { it.contains("JUDDER") } ?: "frame rate does not match the screen"
                StreamHealth.event("judder: ${s.judderText} (turn on Refresh rate switching)")
            }
        }
        snapshot.sections.firstOrNull { it.title == "AUDIO" }?.let { a -> s.audioText = a.lines.joinToString(" · ") }
        snapshot.sections.firstOrNull { it.title == "FILE" }?.let { f -> s.fileText = f.lines.drop(1).filter { !it.startsWith("read at") }.joinToString(" · ") }
        diag.macLines?.let { lines -> lines.filter { it != "no other playback" }.forEach { s.otherPlayback.add(it) } }
        if (diag.macBusy) s.macBusy = true
    }

    /** Every wait for data: at the start, after a skip, or a stall in the middle of playback. */
    private fun trackWaits(
        now: Long,
        state: Int,
        ahead: Long,
    ) {
        val s = session
        if (state == Player.STATE_BUFFERING) {
            if (s.bufferingSince == 0L) s.bufferingSince = now
            return
        }
        if (state != Player.STATE_READY) return
        val seek = s.pendingSeek
        if (seek != null && seek.readyMs < 0) {
            seek.readyMs = now - seek.at
            s.pendingSeek = null
        }
        val firstOfSession = !s.firstReady
        if (!s.firstReady) {
            s.firstReady = true
            s.description =
                try {
                    describe()
                } catch (e: Exception) {
                    "?"
                }
            s.bytesAtStart = StreamHealth.bytesDelivered.get()
        }
        if (s.bufferingSince != 0L) {
            val waited = now - s.bufferingSince
            s.bufferingSince = 0L
            val cause =
                when {
                    // The first wait of a stream is its start: a new film, a resume, or a restart
                    // after changing the audio/subtitle track
                    firstOfSession -> "start"
                    seek != null && now - seek.at < 60_000 -> "skip"
                    else -> "stall"
                }
            if (waited >= 300) {
                s.waits.add(Wait(player.currentPosition, waited, cause, System.currentTimeMillis()))
                if (s.waits.size > 300) s.waits.removeAt(0)
            }
            if (waited >= 1_500 || cause == "stall") {
                StreamHealth.event("waited ${waited / 1000.0} s for data ($cause at ${clock(player.currentPosition)}, ${ahead / 1000} s now ahead)")
            }
        }
    }

    private fun updateRecent(now: Long) {
        val tenMin = 10 * 60_000L
        val wall = System.currentTimeMillis()
        val recent = session.waits.filter { wall - it.at <= tenMin }
        StreamHealth.recentWaits =
            if (recent.isEmpty()) {
                "none in last 10 min"
            } else {
                val last = recent.last()
                String.format(
                    Locale.US,
                    "%d in last 10 min (%.1f s) · last %.1f s %s at %s",
                    recent.size,
                    recent.sumOf { it.ms } / 1000.0,
                    last.ms / 1000.0,
                    last.cause,
                    wall(last.at),
                )
            }
        while (recentDrops.isNotEmpty() && now - recentDrops.first().first > tenMin) recentDrops.removeFirst()
        StreamHealth.recentDrops = recentDrops.sumOf { it.second }
    }

    @Suppress("DEPRECATION")
    private fun readWifi() {
        try {
            val info = wifiManager?.connectionInfo ?: return
            val rssi = info.rssi
            if (rssi <= -127 || rssi >= 0) {
                StreamHealth.wifiRssi = 0
                return
            }
            StreamHealth.wifiRssi = rssi
            StreamHealth.wifiLinkMbps = info.linkSpeed
            StreamHealth.wifiFreqMhz = info.frequency
            if (Build.VERSION.SDK_INT >= 29) {
                StreamHealth.wifiTxMbps = info.txLinkSpeedMbps
                StreamHealth.wifiRxMbps = info.rxLinkSpeedMbps
            }
            val s = session
            s.rssiSum += rssi
            s.rssiSamples++
            s.rssiMin = if (s.rssiMin == 0) rssi else minOf(s.rssiMin, rssi)
            s.linkMbps = info.linkSpeed
            s.freqMhz = info.frequency
        } catch (e: Exception) {
            // no permission or no Wi-Fi
        }
    }

    private fun logHealth(
        ratio: Float,
        ahead: Long,
    ) {
        Timber.e(
            "NoBars health: pos %s, state %d, ahead %.1f s, buffer %d/%d MB (%s), app memory %d/%d MB (%d%%), TV free %d MB (spare %d), in %.1f Mbps, network %.0f Mbps (busy %d%%), opens/min %d, Wi-Fi %d dBm %d Mbps %d MHz, fixes %d/%d/%d, dropped %d",
            clock(player.currentPosition),
            player.playbackState,
            ahead / 1000f,
            StreamHealth.bufferBytes shr 20,
            StreamHealth.bufferBudget shr 20,
            StreamHealth.bufferState,
            StreamHealth.heapUsed() shr 20,
            StreamHealth.heapMax() shr 20,
            (ratio * 100).toInt(),
            StreamHealth.systemAvailMb,
            StreamHealth.systemSpareMb,
            StreamHealth.netMbps,
            StreamHealth.readMbps,
            StreamHealth.readBusy,
            StreamHealth.opensPerMin,
            StreamHealth.wifiRssi,
            StreamHealth.wifiLinkMbps,
            StreamHealth.wifiFreqMhz,
            StreamHealth.reconnects,
            StreamHealth.reloads,
            StreamHealth.rebuilds,
            session.dropped,
        )
    }

    private fun finishSession(reason: String) {
        val report =
            try {
                buildReport(session, reason)
            } catch (e: Exception) {
                "NoBars playback report could not be built: $e"
            }
        session = Session()
        diag.reset()
        StreamHealth.maxOpenMs = 0
        StreamHealth.pingMaxMs = 0
        Timber.e("NoBars report ready (%s)", reason)
        try {
            onReport(report)
        } catch (e: Exception) {
            Timber.e(e, "NoBars report could not be handed over")
        }
    }

    private fun buildReport(
        s: Session,
        reason: String,
    ): String {
        val end = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val minutes = (end - s.startedAt) / 60_000
        val starts = s.waits.filter { it.cause == "start" }
        val skips = s.waits.filter { it.cause == "skip" }
        val stalls = s.waits.filter { it.cause == "stall" }
        val bytes = StreamHealth.bytesDelivered.get() - s.bytesAtStart
        val avgIn = if (s.playingMs > 0) bytes * 8.0 / 1000.0 / s.playingMs else 0.0
        return buildString {
            appendLine("=== NoBars playback report ${s.reportId} ===")
            appendLine("When: ${stamp.format(Date(s.startedAt))} to ${wall(end)} ($minutes min, ${clock(s.playingMs)} playing)")
            appendLine("App: ${BuildConfig.VERSION_NAME} on ${Build.MODEL}")
            appendLine("Film: ${s.description}")
            appendLine("Ended: $reason")
            if (s.fileText.isNotEmpty()) appendLine("File: ${s.fileText}")
            if (s.videoText.isNotEmpty()) appendLine("Video: ${s.videoText}")
            if (s.audioText.isNotEmpty()) appendLine("Audio: ${s.audioText}")
            appendLine(
                String.format(
                    Locale.US,
                    "Waits: %d, total %.1f s (start %.1f s, %d after skips, %d stalls)",
                    s.waits.size,
                    s.waits.sumOf { it.ms } / 1000.0,
                    starts.sumOf { it.ms } / 1000.0,
                    skips.size,
                    stalls.size,
                ),
            )
            s.waits.sortedByDescending { it.ms }.take(5).forEach {
                appendLine(String.format(Locale.US, "  %.1f s %s at %s (%s)", it.ms / 1000.0, it.cause, clock(it.positionMs), wall(it.at)))
            }
            appendLine("Skips: ${s.seeks.size}")
            s.seeks.takeLast(10).forEach {
                val ready = if (it.readyMs >= 0) String.format(Locale.US, "%.1f s", it.readyMs / 1000.0) else "not ready"
                appendLine("  ${clock(it.fromMs)} -> ${clock(it.toMs)}, playing again after $ready")
            }
            appendLine(
                String.format(
                    Locale.US,
                    "Buffer: ahead lowest %.1f s, average %.1f s; size peak %d MB of %d MB budget; held back %d times (%d for low memory)",
                    if (s.aheadMin == Long.MAX_VALUE) 0.0 else s.aheadMin / 1000.0,
                    if (s.aheadSamples > 0) s.aheadSum / s.aheadSamples / 1000.0 else 0.0,
                    s.bufferPeak shr 20,
                    s.budgetAtPeak shr 20,
                    s.holds,
                    s.lowMemoryHolds,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Memory: app peak %d%% of %d MB; TV spare RAM lowest %s MB",
                    (s.heapPeak * 100).toInt(),
                    StreamHealth.heapMax() shr 20,
                    if (s.spareMin == Long.MAX_VALUE) "?" else s.spareMin.toString(),
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Network: into player avg %.1f Mbps; connection speed while reading avg %.0f Mbps, lowest %.0f Mbps; reconnects %d, reloads %d, rebuilds %d",
                    avgIn,
                    if (s.readMbpsSamples > 0) s.readMbpsSum / s.readMbpsSamples else 0.0,
                    if (s.readMbpsMin == Float.MAX_VALUE) 0f else s.readMbpsMin,
                    s.reconnects,
                    s.reloads,
                    s.rebuilds,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "Server: ping max %s ms; slowest answer %d ms; other playback %s%s",
                    if (StreamHealth.pingMaxMs > 0) StreamHealth.pingMaxMs.toString() else "?",
                    StreamHealth.maxOpenMs,
                    if (s.otherPlayback.isEmpty()) "none" else s.otherPlayback.take(5).joinToString("; "),
                    if (s.macBusy) " (server was busy)" else "",
                ),
            )
            appendLine("TV: hottest ${DiagCollector.thermalName(s.thermalMax)}, app CPU peak ${s.cpuMax}%")
            if (s.judder) appendLine("WARNING judder: ${s.judderText} (turn on Refresh rate switching)")
            if (s.rssiSamples > 0) {
                appendLine("Wi-Fi: signal avg ${s.rssiSum / s.rssiSamples} dBm (worst ${s.rssiMin}), link ${s.linkMbps} Mbps, ${s.freqMhz} MHz")
            } else {
                appendLine("Wi-Fi: no reading (Ethernet or no permission)")
            }
            append("Picture: dropped frames ${s.dropped}")
            if (s.dropBursts.isNotEmpty()) {
                append(" (bursts: ")
                append(s.dropBursts.take(10).joinToString { "${it.second} at ${clock(it.first)}" })
                append(")")
            }
            appendLine()
            appendLine("==============================")
        }
    }

    private fun rebuild(reason: String) {
        val now = SystemClock.elapsedRealtime()
        while (rebuildTimes.isNotEmpty() && now - rebuildTimes.first() > 10 * 60_000) rebuildTimes.removeFirst()
        if (rebuildTimes.size >= 3) {
            if (!gaveUp) {
                gaveUp = true
                StreamHealth.event("rebuilt 3 times in 10 minutes, not trying again ($reason)")
                Toast
                    .makeText(
                        context,
                        "Playback keeps stalling. The network or the server may be down.",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            return
        }
        rebuildTimes.addLast(now)
        lastRebuild = now
        StreamHealth.rebuilds++
        session.rebuilds++
        val position = player.currentPosition
        StreamHealth.event("rebuild at ${clock(position)} ($reason)")
        trimImages()
        StreamHealth.markActivity()
        player.stop()
        player.prepare()
        player.seekTo(position)
    }
}

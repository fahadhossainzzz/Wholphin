package com.github.damontecres.wholphin.services.stream

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import timber.log.Timber

/**
 * NoBars: keeps playback moving instead of waiting for it to freeze.
 *
 * Four times a second it looks at how much video is buffered ahead and whether data is arriving.
 * Your Wi-Fi is several times faster than the film, so the buffer should only grow; when it stops
 * growing there is still plenty of video in hand to fix things without anyone noticing:
 *
 * 1. No data for 2 s while loading: cut the connection, the loader reconnects at once (invisible).
 * 2. Under 8 s ahead and still nothing: drop the read-ahead and reload from the current spot,
 *    keeping decoder and chosen audio/subtitles (a hitch at most).
 * 3. Under 2 s ahead / stalled and still nothing, or memory critically full: rebuild the player at
 *    the same spot (a 1-2 s pause instead of a freeze). At most 3 times in 10 minutes.
 *
 * Memory: above 70 % cached images are dropped; the buffer itself gives way on its own
 * ([ElasticLoadControl]); above 92 % after a collection, step 3 frees everything the player holds.
 *
 * Must run on the player's (main) thread.
 */
class StallGuard(
    private val context: Context,
    private val player: ExoPlayer,
    private val isDirectPlay: () -> Boolean,
) {
    private var lastReconnect = 0L
    private var lastReload = 0L
    private var lastRebuild = 0L
    private var lastImageTrim = 0L
    private var lastGcHint = 0L
    private var lastHealthLog = 0L
    private var starvingSince = 0L
    private var lastSystemCheck = 0L
    private var bufferingSince = 0L
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val memInfo = ActivityManager.MemoryInfo()
    private var rateBytes = 0L
    private var lastUsb = 0L to 0L
    private var lastNet = 0L to 0L
    private var lastPre = 0L
    private var lastOpens = 0L
        private var rateAt = 0L
    private var gaveUp = false
    private val rebuildTimes = ArrayDeque<Long>()

    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    Timber.e("NoBars memory: system asked to trim (level %d), dropping cached images", level)
                    trimImages()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = trimImages()
        }

    companion object {
        /** Writes why the app's previous runs ended (killed for memory, crashed, ...) to the log. */
        fun logPreviousExits(context: Context) {
            try {
                val am = context.getSystemService(ActivityManager::class.java) ?: return
                val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                Timber.e(
                    "NoBars device: RAM %d MB total, %d MB free, low-memory line %d MB, app limit %d MB (large %d MB), lowRam=%b",
                    info.totalMem shr 20,
                    info.availMem shr 20,
                    info.threshold shr 20,
                    Runtime.getRuntime().maxMemory() shr 20,
                    am.largeMemoryClass,
                    am.isLowRamDevice,
                )
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    am.getHistoricalProcessExitReasons(null, 0, 3).forEach {
                        Timber.e(
                            "NoBars previous exit: %s at %tF %<tT, reason=%d (%s), importance=%d, pss=%d MB, rss=%d MB",
                            it.processName,
                            java.util.Date(it.timestamp),
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
    }

    suspend fun run() {
        StreamHealth.resetCounters()
        context.registerComponentCallbacks(trimCallbacks)
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Timber.w(e, "NoBars stall guard tick failed")
                }
                delay(250)
            }
        } finally {
            context.unregisterComponentCallbacks(trimCallbacks)
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
        val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        StreamHealth.aheadMs = ahead

        if (now - rateAt >= 2_000) {
            val bytes = StreamHealth.bytesDelivered.get()
            val usb = StreamHealth.usbReads.bytes.get() to StreamHealth.usbReads.nanos.get()
            val net = StreamHealth.netReads.bytes.get() to StreamHealth.netReads.nanos.get()
            val pre = StreamHealth.prefetchReads.bytes.get()
            val opens = StreamHealth.opens.get()
            if (rateAt > 0) {
                val ms = (now - rateAt).toFloat()
                StreamHealth.netMbps = (bytes - rateBytes) * 8f / 1000f / ms
                // Speed of each source while it is being read, and how much of the time the player waits on it
                StreamHealth.usbMbps = speed(usb.first - lastUsb.first, usb.second - lastUsb.second)
                StreamHealth.usbBusy = ((usb.second - lastUsb.second) / 1e4f / ms).toInt()
                StreamHealth.netOnlyMbps = speed(net.first - lastNet.first, net.second - lastNet.second)
                StreamHealth.netBusy = ((net.second - lastNet.second) / 1e4f / ms).toInt()
                StreamHealth.prefetchMbps = (pre - lastPre) * 8f / 1000f / ms
                StreamHealth.opensPerMin = ((opens - lastOpens) * 60_000f / ms).toInt()
            }
            rateBytes = bytes
            lastUsb = usb
            lastNet = net
            lastPre = pre
            lastOpens = opens
            rateAt = now
        }

        if (now - lastSystemCheck >= 1_000) {
            lastSystemCheck = now
            activityManager?.getMemoryInfo(memInfo)
            StreamHealth.systemAvailMb = memInfo.availMem shr 20
            StreamHealth.systemSpareMb = (memInfo.availMem - memInfo.threshold) shr 20
        }

        var ratio = StreamHealth.heapRatio()
        if ((ratio >= 0.60f || StreamHealth.systemSpareMb < 250) && now - lastImageTrim >= 30_000) {
            Timber.e("NoBars memory: app %d%%, TV spare %d MB, dropping cached images", (ratio * 100).toInt(), StreamHealth.systemSpareMb)
            trimImages()
        }
        if (now - lastHealthLog >= 30_000) {
            lastHealthLog = now
            Timber.e(
                "NoBars health: memory %d/%d MB (%d%%), TV free %d MB (spare %d), drive-fed=%b, ahead %.1fs, loading=%b (%s), state=%d, in %.1f Mbps, usb read %.0f Mbps busy %d%%, net read %.0f Mbps busy %d%%, read-ahead %.1f Mbps, opens/min %d, USB %s (%ds ahead), fixes %d/%d/%d",
                StreamHealth.heapUsed() shr 20,
                StreamHealth.heapMax() shr 20,
                (ratio * 100).toInt(),
                StreamHealth.systemAvailMb,
                StreamHealth.systemSpareMb,
                StreamHealth.driveFed,
                ahead / 1000f,
                player.isLoading,
                StreamHealth.bufferState,
                player.playbackState,
                StreamHealth.netMbps,
                StreamHealth.usbMbps,
                StreamHealth.usbBusy,
                StreamHealth.netOnlyMbps,
                StreamHealth.netBusy,
                StreamHealth.prefetchMbps,
                StreamHealth.opensPerMin,
                StreamHealth.usbStatus,
                StreamHealth.usbAheadSec,
                StreamHealth.reconnects,
                StreamHealth.reloads,
                StreamHealth.rebuilds,
            )
        }

        val state = player.playbackState
        // Log every wait for data (start, seek or stall) that takes more than 1.5 s
        if (state == Player.STATE_BUFFERING) {
            if (bufferingSince == 0L) bufferingSince = now
        } else if (bufferingSince != 0L) {
            val waited = now - bufferingSince
            bufferingSince = 0L
            if (waited >= 1_500) {
                Timber.e(
                    "NoBars: waited %d ms for data (drive-fed=%b, USB %s)",
                    waited,
                    StreamHealth.driveFed,
                    StreamHealth.usbStatus,
                )
            }
        }
        if (!player.playWhenReady || state == Player.STATE_IDLE || state == Player.STATE_ENDED || player.playerError != null) {
            starvingSince = 0
            return
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

        val loading = player.isLoading
        val idle = StreamHealth.msSinceLastByte()

        // Step 1: data stopped arriving while the player wants it: reconnect at once
        if (loading && idle >= 2_000 && now - lastReconnect >= 3_000) {
            val cut = StreamHealth.cancelPlayerCalls()
            UsbReadAhead.cancelDownload()
            StreamHealth.reconnects++
            lastReconnect = now
            Timber.e("NoBars rescue 1: no data for %d ms (%.1fs ahead), reconnecting (%d connections cut)", idle, ahead / 1000f, cut)
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
            lastReload = now
            Timber.e("NoBars rescue 2: still no data (%d ms, %.1fs ahead), reloading from the current position", idle, ahead / 1000f)
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

    private fun speed(
        bytes: Long,
        nanos: Long,
    ): Float = if (nanos <= 0) 0f else bytes * 8f * 1000f / nanos

    private fun rebuild(reason: String) {
        val now = SystemClock.elapsedRealtime()
        while (rebuildTimes.isNotEmpty() && now - rebuildTimes.first() > 10 * 60_000) rebuildTimes.removeFirst()
        if (rebuildTimes.size >= 3) {
            if (!gaveUp) {
                gaveUp = true
                Timber.e("NoBars rescue 3: rebuilt 3 times in 10 minutes, not trying again (%s)", reason)
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
        val position = player.currentPosition
        Timber.e("NoBars rescue 3: rebuilding the player at %d ms (%s)", position, reason)
        trimImages()
        StreamHealth.markActivity()
        player.stop()
        player.prepare()
        player.seekTo(position)
    }
}

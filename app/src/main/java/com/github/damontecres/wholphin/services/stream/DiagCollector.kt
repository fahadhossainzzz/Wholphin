@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.github.damontecres.wholphin.BuildConfig
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Level { OK, WARN, BAD }

data class DiagSection(
    val title: String,
    val level: Level,
    val lines: List<String>,
)

data class DiagSnapshot(
    val sections: List<DiagSection>,
    val footer: List<String>,
    val compact: String,
    val compactLevel: Level,
)

/** NoBars: the latest diagnostics, refreshed once a second, read by the overlay. */
object Diag {
    @Volatile var snapshot: DiagSnapshot? = null
}

/** Everything about the current episode that the stall guard counts, handed to [DiagCollector.build]. */
data class EpisodeStats(
    val reportId: String,
    val playingMs: Long,
    val waits: Int,
    val stalls: Int,
    val waitSeconds: Double,
    val skips: Int,
    val skipAvgMs: Long?,
    val reconnects: Int,
    val reloads: Int,
    val rebuilds: Int,
    val readMbpsAvg: Double,
    val lastTrimAt: String,
)

/**
 * NoBars: gathers what the full-screen debug overlay and the per-film report show, from the player,
 * the TV and the server. Must be called on the player's thread.
 */
class DiagCollector(
    private val context: Context,
    private val player: ExoPlayer,
    private val source: () -> MediaSourceInfo?,
    private val title: () -> String,
    private val playMethod: () -> String,
    private val transcodeReason: () -> String?,
) {
    @Volatile var videoDecoder: String? = null
        private set

    @Volatile var audioDecoder: String? = null
        private set

    @Volatile private var audioTrack: AudioSink.AudioTrackConfig? = null

    /** Lines about other playback on the server (set by a background poll). */
    @Volatile var macLines: List<String>? = null

    @Volatile var macBusy = false

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val display: Display? =
        try {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (e: Exception) {
            null
        }

    private var cpuAt = 0L
    private var cpuMs = 0L
    var cpuPercent = 0
        private set
    var thermal = 0
        private set
    var judder = false
        private set

    val analytics =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                audioTrack = audioTrackConfig
            }
        }

    /** A new film: forget the previous one's decoders and audio output. */
    fun reset() {
        audioTrack = null
    }

    fun build(ep: EpisodeStats): DiagSnapshot {
        sampleCpuAndThermal()
        val src = source()
        val sections =
            listOf(
                safe("FILE") { file(src) },
                safe("VIDEO") { video() },
                safe("AUDIO") { audio() },
                safe("NETWORK") { network(src, ep) },
                safe("BUFFER") { buffer() },
                safe("MEMORY") { memory(ep) },
                safe("TV") { tv() },
                safe("MAC") { mac() },
            )
        val episode =
            buildList {
                add(
                    String.format(
                        Locale.US,
                        "Episode: playing %s · waits %d (%.1f s, %d stalls) · skips %d%s · fixes %d/%d/%d",
                        StallGuard.clock(ep.playingMs),
                        ep.waits,
                        ep.waitSeconds,
                        ep.stalls,
                        ep.skips,
                        ep.skipAvgMs?.let { String.format(Locale.US, " (avg %.1f s)", it / 1000.0) } ?: "",
                        ep.reconnects,
                        ep.reloads,
                        ep.rebuilds,
                    ),
                )
                add("Last: ${StreamHealth.lastEvent.ifEmpty { "nothing yet" }}")
                add("Report ${ep.reportId} · ${StreamHealth.reportStatus.substringBefore(" (")} · ${StreamHealth.now()}")
            }
        val worst = sections.maxOf { it.level }
        val hz = display?.mode?.refreshRate ?: 0f
        val fps = player.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()
        val headroom = headroom(src, ep)
        val compact =
            buildString {
                append(
                    when (worst) {
                        Level.OK -> "OK"
                        Level.WARN -> "CHECK " + sections.filter { it.level == Level.WARN }.joinToString(",") { it.title }
                        Level.BAD -> "PROBLEM " + sections.filter { it.level == Level.BAD }.joinToString(",") { it.title }
                    },
                )
                append(String.format(Locale.US, " · buffer %.0f s", StreamHealth.aheadMs / 1000f))
                headroom?.let { append(String.format(Locale.US, " · headroom %.0f×", it)) }
                append(" · waits ${ep.waits}")
                if (fps > 0 && hz > 0) append(String.format(Locale.US, " · %.3f fps on %.0f Hz%s", fps, hz, if (judder) " JUDDER" else " ✓"))
                append(" · ${thermalName(thermal)}")
                append(" · ${ep.reportId}")
            }
        return DiagSnapshot(sections, episode, compact, worst)
    }

    private inline fun safe(
        title: String,
        block: () -> DiagSection,
    ): DiagSection =
        try {
            block()
        } catch (e: Exception) {
            DiagSection(title, Level.WARN, listOf("could not read: ${e.javaClass.simpleName}"))
        }

    // ------------------------------------------------------------------ sections

    private fun file(src: MediaSourceInfo?): DiagSection {
        val lines = mutableListOf<String>()
        var level = Level.OK
        lines += title().take(34)
        val size = src?.size ?: 0L
        val durationMs = (src?.runTimeTicks ?: 0L) / 10_000
        lines += "${src?.container?.uppercase() ?: "?"} ${gb(size)} · ${StallGuard.clock(durationMs)}"
        val avgMbps = (src?.bitrate ?: 0) / 1e6
        val drive = src?.path?.let { Regex("/Volumes/([^/]+)/").find(it)?.groupValues?.get(1) } ?: "?"
        lines += String.format(Locale.US, "avg %.1f Mbps · %s", avgMbps, drive)
        val pos = StreamHealth.readPosition
        if (size > 0 && pos > 0) {
            lines += String.format(Locale.US, "read at %s (%d%%)", gb(pos), (pos * 100 / size).toInt())
        }
        val method = playMethod()
        lines += "Play: $method"
        if (method != "DirectPlay") {
            level = Level.WARN
            transcodeReason()?.let { lines += "why: ${it.take(60)}" }
        }
        return DiagSection("FILE", level, lines)
    }

    private fun video(): DiagSection {
        val f = player.videoFormat ?: return DiagSection("VIDEO", Level.OK, listOf("no video"))
        val lines = mutableListOf<String>()
        var level = Level.OK
        lines += "${codecName(f)} ${profile(f)}".trim()
        val mode = display?.mode
        val panelH = mode?.physicalHeight ?: 0
        val res = if (f.height > 0) "${f.width}x${f.height}" else "?"
        lines += if (panelH > 0 && f.height in 1 until panelH - 100) "$res → upscaled to ${panelH}p" else res
        lines += hdrName(f)
        val hz = mode?.refreshRate ?: 0f
        val fps = f.frameRate
        judder = false
        if (fps > 0 && hz > 0) {
            val ratio = hz / fps
            judder = abs(ratio - ratio.roundToInt()) > 0.02f
            lines += String.format(Locale.US, "%.3f fps on %.2f Hz %s", fps, hz, if (judder) "JUDDER" else "✓")
            if (judder) level = Level.WARN
        } else if (hz > 0) {
            lines += String.format(Locale.US, "screen %.2f Hz", hz)
        }
        lines += "dec ${videoDecoder ?: "?"}${if (videoDecoder?.contains("sw", ignoreCase = true) == true || videoDecoder?.startsWith("c2.android") == true) " (SW!)" else ""}"
        player.videoDecoderCounters?.let {
            it.ensureUpdated()
            lines += "shown ${it.renderedOutputBufferCount} · dropped ${it.droppedBufferCount}"
        }
        val recentDrops = StreamHealth.recentDrops
        if (recentDrops > 200) {
            level = Level.BAD
        } else if (recentDrops > 30 && level == Level.OK) {
            level = Level.WARN
        }
        return DiagSection("VIDEO", level, lines)
    }

    private fun audio(): DiagSection {
        val f = player.audioFormat ?: return DiagSection("AUDIO", Level.OK, listOf("no audio"))
        val lines = mutableListOf<String>()
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val selected = audioGroups.indexOfFirst { it.isSelected } + 1
        val lang = f.language?.let { " $it" } ?: ""
        lines += "${codecName(f)} ${channels(f.channelCount)}$lang"
        lines += "track $selected of ${audioGroups.size}"
        val track = audioTrack
        val passthrough = track != null && track.encoding != C.ENCODING_INVALID && !isPcm(track.encoding)
        lines +=
            when {
                passthrough -> "bitstream → device decodes"
                audioDecoder != null -> "dec ${audioDecoder}"
                else -> "dec ?"
            }
        val outCh = track?.let { Integer.bitCount(it.channelConfig) } ?: 0
        val device = outputDevice()
        lines +=
            if (track == null) {
                "→ $device"
            } else if (passthrough) {
                "→ $device (${encodingName(track.encoding)})"
            } else {
                String.format(Locale.US, "→ %s %s %d kHz", device, channels(outCh), track.sampleRate / 1000)
            }
        if (!passthrough && outCh in 1 until f.channelCount) lines += "downmixed ${channels(f.channelCount)} → ${channels(outCh)}"
        val text = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        lines +=
            text?.let { g ->
                val tf = g.getTrackFormat(0)
                "subs ${subName(tf)}${tf.language?.let { " $it" } ?: ""}"
            } ?: "subs off"
        return DiagSection("AUDIO", Level.OK, lines)
    }

    private fun network(
        src: MediaSourceInfo?,
        ep: EpisodeStats,
    ): DiagSection {
        val lines = mutableListOf<String>()
        var level = Level.OK
        val rssi = StreamHealth.wifiRssi
        if (rssi != 0) {
            val f = StreamHealth.wifiFreqMhz
            val ch = if (f >= 4900) (f - 5000) / 5 else (f - 2407) / 5
            lines += "Wi-Fi $rssi dBm · ch $ch ${if (f >= 4900) "5G" else "2.4G"}"
            lines += "link ${StreamHealth.wifiLinkMbps} (tx ${StreamHealth.wifiTxMbps}/rx ${StreamHealth.wifiRxMbps})"
            if (rssi < -70) level = Level.WARN
        } else {
            lines += "Wi-Fi: no reading"
        }
        val ping = StreamHealth.pingMs
        lines += if (ping >= 0) "ping Mac $ping ms (max ${StreamHealth.pingMaxMs})" else "ping Mac ?"
        if (ping > 50) level = maxOf(level, Level.WARN)
        lines += "server answer ${StreamHealth.lastOpenMs} ms (max ${StreamHealth.maxOpenMs})"
        if (StreamHealth.maxOpenMs > 2_000) level = maxOf(level, Level.WARN)
        lines += String.format(Locale.US, "read now %.0f · avg %.0f Mbps", StreamHealth.netMbps, ep.readMbpsAvg)
        val headroom = headroom(src, ep)
        if (headroom != null) {
            lines += String.format(Locale.US, "headroom %.0f×", headroom)
            if (headroom < 3) {
                level = Level.BAD
            } else if (headroom < 6) {
                level = maxOf(level, Level.WARN)
            }
        }
        return DiagSection("NETWORK", level, lines)
    }

    private fun buffer(): DiagSection {
        val ahead = StreamHealth.aheadMs
        val bytes = StreamHealth.bufferBytes
        val lines = mutableListOf<String>()
        lines += String.format(Locale.US, "ahead %.0f s · %d MB", ahead / 1000f, bytes shr 20)
        lines += "budget ${StreamHealth.bufferBudget shr 20} MB"
        if (ahead > 1_000) lines += String.format(Locale.US, "%.2f MB per second", bytes / 1048576.0 / (ahead / 1000.0))
        lines += StreamHealth.bufferState.ifEmpty { "-" }
        if (StreamHealth.lastRefillMs > 0) lines += String.format(Locale.US, "last refill %.0f s", StreamHealth.lastRefillMs / 1000.0)
        val playing = player.isPlaying
        val level =
            when {
                playing && ahead < 5_000 -> Level.BAD
                playing && ahead < 15_000 -> Level.WARN
                else -> Level.OK
            }
        return DiagSection("BUFFER", level, lines)
    }

    private fun memory(ep: EpisodeStats): DiagSection {
        val ratio = StreamHealth.heapRatio()
        val lines = mutableListOf<String>()
        lines += String.format(Locale.US, "app %d/%d MB (%d%%)", StreamHealth.heapUsed() shr 20, StreamHealth.heapMax() shr 20, (ratio * 100).toInt())
        lines += "native ${Debug.getNativeHeapAllocatedSize() shr 20} MB"
        lines += "TV free ${StreamHealth.systemAvailMb} (spare ${StreamHealth.systemSpareMb})"
        lines += "images freed ${ep.lastTrimAt}"
        val level =
            when {
                ratio > 0.9f || StreamHealth.systemSpareMb < 150 -> Level.BAD
                ratio > 0.8f || StreamHealth.systemSpareMb < 200 -> Level.WARN
                else -> Level.OK
            }
        return DiagSection("MEMORY", level, lines)
    }

    private fun tv(): DiagSection {
        val lines = mutableListOf<String>()
        lines += "temp ${thermalName(thermal)}"
        lines += "app CPU $cpuPercent%"
        val up = SystemClock.elapsedRealtime() / 60_000
        lines += "up ${up / 1440} d ${up / 60 % 24} h"
        lines += "Android ${Build.VERSION.RELEASE}"
        lines += BuildConfig.VERSION_NAME.substringAfterLast('-').let { "app $it" }
        val level =
            when {
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Level.BAD
                thermal >= PowerManager.THERMAL_STATUS_MODERATE || cpuPercent > 85 -> Level.WARN
                else -> Level.OK
            }
        return DiagSection("TV", level, lines)
    }

    private fun mac(): DiagSection {
        val lines = macLines ?: return DiagSection("MAC", Level.OK, listOf("checking…"))
        val shown = if (lines.isEmpty()) listOf("no other playback") else lines.take(5)
        return DiagSection("MAC", if (macBusy) Level.WARN else Level.OK, shown)
    }

    // ------------------------------------------------------------------ helpers

    private fun headroom(
        src: MediaSourceInfo?,
        ep: EpisodeStats,
    ): Double? {
        val bitrate = (src?.bitrate ?: 0) / 1e6
        val read = if (ep.readMbpsAvg > 0) ep.readMbpsAvg else StreamHealth.readMbps.toDouble()
        return if (bitrate > 0.5 && read > 0) read / bitrate else null
    }

    private fun sampleCpuAndThermal() {
        val now = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        if (cpuAt > 0 && now > cpuAt) {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            cpuPercent = ((cpu - cpuMs) * 100 / ((now - cpuAt) * cores)).toInt().coerceIn(0, 100)
        }
        cpuAt = now
        cpuMs = cpu
        thermal =
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    powerManager?.currentThermalStatus ?: 0
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }
    }

    private fun outputDevice(): String {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val types = devices.map { it.type }.toSet()
        return when {
            Build.VERSION.SDK_INT >= 31 && AudioDeviceInfo.TYPE_HDMI_EARC in types -> "HDMI eARC"
            AudioDeviceInfo.TYPE_HDMI_ARC in types -> "HDMI ARC"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE in types || AudioDeviceInfo.TYPE_USB_HEADSET in types -> "USB audio"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types -> "headphones"
            else -> "TV speakers"
        }
    }

    companion object {
        fun thermalName(status: Int): String =
            when (status) {
                0 -> "normal"
                1 -> "warm"
                2 -> "hot (slowing)"
                3 -> "very hot"
                4, 5, 6 -> "critical"
                else -> "?"
            }

        fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1e9)

        fun channels(n: Int): String =
            when (n) {
                1 -> "mono"
                2 -> "2.0"
                6 -> "5.1"
                8 -> "7.1"
                else -> if (n > 0) "${n}ch" else "?"
            }

        private fun isPcm(encoding: Int) =
            encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_FLOAT ||
                encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT ||
                encoding == C.ENCODING_PCM_8BIT

        fun encodingName(encoding: Int): String =
            when (encoding) {
                C.ENCODING_AC3 -> "Dolby Digital"
                C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> "Dolby Digital+"
                C.ENCODING_AC4 -> "AC-4"
                C.ENCODING_DTS -> "DTS"
                C.ENCODING_DTS_HD -> "DTS-HD"
                C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
                else -> "encoded"
            }

        fun codecName(f: Format): String =
            when (f.sampleMimeType) {
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_H264 -> "H.264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
                MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                else -> f.sampleMimeType?.substringAfter('/') ?: "?"
            }

        /** "High 4.0" or "Main10" from the codecs string. */
        fun profile(f: Format): String {
            val c = f.codecs ?: return ""
            return try {
                when {
                    c.startsWith("avc1.") && c.length >= 11 -> {
                        val p = c.substring(5, 7).toInt(16)
                        val lvl = c.substring(9, 11).toInt(16)
                        val name =
                            when (p) {
                                66 -> "Baseline"
                                77 -> "Main"
                                100 -> "High"
                                110 -> "High10"
                                else -> "p$p"
                            }
                        "$name ${lvl / 10}.${lvl % 10}"
                    }
                    c.startsWith("hvc1.") || c.startsWith("hev1.") -> {
                        val p = c.split('.').getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()
                        when (p) {
                            1 -> "Main 8-bit"
                            2 -> "Main10 10-bit"
                            else -> ""
                        }
                    }
                    c.startsWith("dvh") -> "profile ${c.split('.').getOrNull(1)?.toIntOrNull() ?: "?"}"
                    else -> ""
                }
            } catch (e: Exception) {
                ""
            }
        }

        fun hdrName(f: Format): String {
            if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true) return "Dolby Vision"
            return when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
        }

        fun subName(f: Format): String =
            when (f.sampleMimeType) {
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                MimeTypes.TEXT_SSA -> "ASS"
                MimeTypes.APPLICATION_PGS -> "PGS"
                MimeTypes.TEXT_VTT -> "VTT"
                MimeTypes.APPLICATION_MEDIA3_CUES -> f.codecs?.substringAfter('/')?.uppercase() ?: "text"
                else -> f.sampleMimeType?.substringAfter('/') ?: "?"
            }
    }
}

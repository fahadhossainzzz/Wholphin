package com.github.damontecres.wholphin.nobars.playback

import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.Locale

/**
 * NoBars: plain facts about the file being played, for the debug panel and the playback reports:
 * "MKV · 41.1 GB · 1:48:06 · HDD3", "HEVC Main 10 · Level 5.1 · 3840×2160 · 23.976 fps · Dolby Vision 8.1 (HDR10 base) · 45.0 Mbps".
 */
object PlaybackFacts {
    fun file(cp: CurrentPlayback?): String {
        val src = cp?.mediaSourceInfo ?: return "?"
        val parts = mutableListOf<String>()
        src.container?.let { parts += it.uppercase(Locale.US) }
        src.size?.let { parts += String.format(Locale.US, "%.1f GB", it / 1e9) }
        src.runTimeTicks?.let { parts += clock(it / 10_000) }
        src.path?.let { p -> Regex("/Volumes/([^/]+)/").find(p)?.groupValues?.get(1)?.let { parts += it.substringBefore("-") } }
        return parts.joinToString(" · ")
    }

    fun overall(cp: CurrentPlayback?): String =
        cp?.mediaSourceInfo?.bitrate?.let { "${rate(it)} average" } ?: "?"

    /** Bits a second as MB/s: 16_300_000 → "2.0 MB/s", 576_000 → "0.07 MB/s". */
    fun rate(bitsPerSecond: Int): String = StreamHealth.mbs(bitsPerSecond / 1e6f)

    /** The film's frame rate, 0 when unknown */
    fun fps(cp: CurrentPlayback?): Float =
        cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.let { it.realFrameRate ?: it.averageFrameRate } ?: 0f

    /** True when the file carries Dolby Vision */
    fun isDolbyVision(cp: CurrentPlayback?): Boolean =
        (cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.dvProfile ?: 0) > 0

    /** "HDR10", "HDR10+", "HLG", "SDR" for the file's base picture */
    fun range(cp: CurrentPlayback?): String {
        val v = cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return "?"
        val r = v.videoRangeType?.serialName ?: v.videoRange?.serialName ?: ""
        return when {
            r.contains("HDR10Plus", true) -> "HDR10+"
            r.contains("HDR10", true) || r.contains("DOVIWithHDR10", true) -> "HDR10"
            r.contains("HLG", true) -> "HLG"
            else -> "SDR"
        }
    }

    /** The panel's shorter video line: "HEVC 10-bit · 2880×2160 · 23.976 fps · DV 8.1 · 1.8 MB/s" */
    fun videoShort(cp: CurrentPlayback?): String {
        val v = cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return "?"
        val parts = mutableListOf<String>()
        parts += (v.codec?.uppercase(Locale.US) ?: "?") + if ((v.bitDepth ?: 8) >= 10 || v.profile?.contains("10") == true) " 10-bit" else ""
        if (v.width != null && v.height != null) parts += "${v.width}×${v.height}"
        (v.realFrameRate ?: v.averageFrameRate)?.let { parts += StreamHealth.fpsText(it) + " fps" }
        parts += picture(v).replace("Dolby Vision ", "DV ").substringBefore(" (")
        v.bitRate?.let { parts += rate(it) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** The panel's shorter audio line: "eng · DD+ Atmos 5.1 · 0.07 MB/s" */
    fun audioShort(cp: CurrentPlayback?): String = audio(cp).replace(Regex(" · \\d+ tracks in the file$"), "")

    /** "eng · SRT · separate file · delay −5.0 s" (the delay only when one is set) */
    fun subtitleWithDelay(cp: CurrentPlayback?): String {
        val base = subtitle(cp)
        if (base == "off") return base
        val ms = cp?.subtitleDelay?.inWholeMilliseconds ?: 0
        return if (ms != 0L) "$base · delay ${signed(ms)}" else base
    }

    /** −5000 → "−5.0 s", 1500 → "+1.5 s" */
    fun signed(ms: Long): String = (if (ms < 0) "−" else "+") + String.format(Locale.US, "%.1f s", kotlin.math.abs(ms) / 1000.0)

    fun video(cp: CurrentPlayback?): String {
        val v = cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return "?"
        val parts = mutableListOf<String>()
        parts += listOfNotNull(v.codec?.uppercase(Locale.US), v.profile).joinToString(" ")
        level(v)?.let { parts += "Level $it" }
        if (v.width != null && v.height != null) parts += "${v.width}×${v.height}"
        (v.realFrameRate ?: v.averageFrameRate)?.let { parts += String.format(Locale.US, "%.3f fps", it).replace(".000", "") }
        parts += picture(v)
        v.bitRate?.let { parts += rate(it) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun audio(cp: CurrentPlayback?): String {
        val streams = cp?.mediaSourceInfo?.mediaStreams.orEmpty()
        val all = streams.filter { it.type == MediaStreamType.AUDIO }
        val a = all.firstOrNull { it.index == cp?.audioIndex } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull() ?: return "none"
        val parts = mutableListOf<String>()
        a.language?.let { parts += it }
        parts += listOfNotNull(codecName(a), a.channelLayout ?: a.channels?.let { "$it ch" }).joinToString(" ")
        a.bitRate?.let { parts += rate(it) }
        if (all.size > 1) parts += "${all.size} tracks in the file"
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun subtitle(cp: CurrentPlayback?): String {
        val s =
            cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == cp.subtitleIndex }
                ?: return "off"
        val codec =
            when (s.codec?.lowercase(Locale.US)) {
                "subrip", "srt" -> "SRT"
                "mov_text" -> "MOV text"
                "ass", "ssa" -> "ASS"
                "pgssub", "hdmv_pgs_subtitle" -> "PGS"
                null -> null
                else -> s.codec?.uppercase(Locale.US)
            }
        return listOfNotNull(s.language, codec, if (s.isForced) "forced" else null, if (s.isExternal) "separate file" else "in the film file")
            .joinToString(" · ")
    }

    /** One line for the playback report. */
    fun reportLines(cp: CurrentPlayback?): List<String> =
        listOf(
            "File: ${file(cp)} · ${overall(cp)}",
            "Video: ${video(cp)}",
            "Audio: ${audio(cp)}",
            "Subtitle: ${subtitle(cp)}",
        )

    private fun level(v: MediaStream): String? {
        val l = v.level?.toDouble()?.takeIf { it > 0 } ?: return null
        return when (v.codec?.lowercase(Locale.US)) {
            "hevc", "h265" -> String.format(Locale.US, "%.1f", l / 30.0)
            "h264", "avc" -> String.format(Locale.US, "%.1f", l / 10.0)
            else -> l.toInt().toString()
        }
    }

    private fun picture(v: MediaStream): String {
        val range = v.videoRangeType?.serialName ?: v.videoRange?.serialName ?: ""
        val dv = v.dvProfile
        if (dv != null && dv > 0) {
            val base =
                when (v.dvBlSignalCompatibilityId) {
                    1, 6 -> "HDR10 base"
                    2 -> "SDR base"
                    4 -> "HLG base"
                    0 -> "no fallback"
                    else -> null
                }
            val compat = v.dvBlSignalCompatibilityId?.let { ".$it" } ?: ""
            return "Dolby Vision $dv$compat" + (base?.let { " ($it)" } ?: "") + if (range.contains("Invalid", true)) " · flagged invalid" else ""
        }
        return when {
            range.contains("HDR10Plus", true) -> "HDR10+"
            range.contains("HDR10", true) -> "HDR10"
            range.contains("HLG", true) -> "HLG"
            range.isBlank() || range == "SDR" -> "SDR"
            else -> range
        }
    }

    private fun codecName(a: MediaStream): String? {
        val p = a.profile.orEmpty()
        return when (a.codec?.lowercase(Locale.US)) {
            "truehd" -> if (p.contains("Atmos", true)) "TrueHD Atmos" else "TrueHD"
            "eac3" -> if (p.contains("Atmos", true)) "DD+ Atmos" else "Dolby Digital Plus"
            "ac3" -> "Dolby Digital"
            "dts" ->
                when {
                    p.contains("MA", true) -> "DTS-HD MA"
                    p.contains("X", true) -> "DTS:X"
                    p.contains("HD", true) -> "DTS-HD"
                    else -> "DTS"
                }
            "aac" -> "AAC"
            "flac" -> "FLAC"
            "opus" -> "Opus"
            null -> null
            else -> a.codec?.uppercase(Locale.US)
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}

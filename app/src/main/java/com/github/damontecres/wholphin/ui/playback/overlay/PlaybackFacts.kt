package com.github.damontecres.wholphin.ui.playback.overlay

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
        cp?.mediaSourceInfo?.bitrate?.let { String.format(Locale.US, "%.1f Mbps average", it / 1e6) } ?: "?"

    fun video(cp: CurrentPlayback?): String {
        val v = cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return "?"
        val parts = mutableListOf<String>()
        parts += listOfNotNull(v.codec?.uppercase(Locale.US), v.profile).joinToString(" ")
        level(v)?.let { parts += "Level $it" }
        if (v.width != null && v.height != null) parts += "${v.width}×${v.height}"
        (v.realFrameRate ?: v.averageFrameRate)?.let { parts += String.format(Locale.US, "%.3f fps", it).replace(".000", "") }
        parts += picture(v)
        v.bitRate?.let { parts += String.format(Locale.US, "%.1f Mbps", it / 1e6) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun audio(cp: CurrentPlayback?): String {
        val streams = cp?.mediaSourceInfo?.mediaStreams.orEmpty()
        val all = streams.filter { it.type == MediaStreamType.AUDIO }
        val a = all.firstOrNull { it.index == cp?.audioIndex } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull() ?: return "none"
        val parts = mutableListOf<String>()
        a.language?.let { parts += it }
        parts += listOfNotNull(codecName(a), a.channelLayout ?: a.channels?.let { "$it ch" }).joinToString(" ")
        a.bitRate?.let { parts += if (it >= 1_000_000) String.format(Locale.US, "%.1f Mbps", it / 1e6) else "${it / 1000} kbps" }
        if (all.size > 1) parts += "${all.size} tracks in the file"
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun subtitle(cp: CurrentPlayback?): String {
        val s =
            cp?.mediaSourceInfo?.mediaStreams?.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == cp.subtitleIndex }
                ?: return "off"
        return listOfNotNull(s.language, s.codec?.uppercase(Locale.US), if (s.isForced) "forced" else null, if (s.isExternal) "external" else null)
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

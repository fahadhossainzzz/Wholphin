package com.github.damontecres.wholphin.ui.playback

import androidx.annotation.OptIn
import androidx.core.text.HtmlCompat
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.github.damontecres.wholphin.services.ServerAccess
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber

/**
 * NoBars: subtitle delay for ExoPlayer, which has no way to move subtitles in time (only MPV has).
 *
 * For a plain-text subtitle (SRT / WebVTT), the app fetches the whole file from Jellyfin once and shows its lines
 * itself at (player position - delay), in the same SubtitleView, so the subtitle style settings still apply. Works
 * both ways (earlier and later), live, because it only follows the player's position. With no delay, or for picture
 * (PGS/DVD) and styled ASS subtitles, the player's own subtitles are shown exactly as before.
 */
@OptIn(UnstableApi::class)
class ShiftableSubtitles private constructor(
    private val cues: List<Timed>,
) {
    private class Timed(
        val startMs: Long,
        val endMs: Long,
        val cue: Cue,
    )

    private val starts = LongArray(cues.size) { cues[it].startMs }
    private val longest = cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    val size: Int get() = cues.size

    /** The lines on screen at this position of the subtitle's own timeline. */
    fun at(positionMs: Long): List<Cue> {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) lo = mid + 1 else hi = mid
        }
        var i = lo - 1
        var out: ArrayList<Cue>? = null
        while (i >= 0 && cues[i].startMs >= positionMs - longest) {
            if (cues[i].endMs > positionMs) {
                if (out == null) out = ArrayList(2)
                out.add(0, cues[i].cue)
            }
            i--
        }
        return out ?: emptyList()
    }

    companion object {
        private val TIME =
            Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")
        private val STYLE_CODES = Regex("""\{\\[^}]*\}""")
        private val TEXT_CODECS = setOf("subrip", "srt", "webvtt", "vtt", "mov_text", "text")

        private fun ms(
            h: String,
            m: String,
            s: String,
            frac: String,
        ): Long = h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + frac.padEnd(3, '0').take(3).toLong()

        fun parse(text: String): ShiftableSubtitles {
            val out = ArrayList<Timed>()
            text
                .replace("﻿", "")
                .replace("\r\n", "\n")
                .split(Regex("""\n\s*\n"""))
                .forEach { block ->
                    val lines = block.trim('\n').split('\n')
                    val at = lines.indexOfFirst { it.contains("-->") }
                    if (at < 0) return@forEach
                    val m = TIME.find(lines[at]) ?: return@forEach
                    val g = m.groupValues
                    val start = ms(g[1], g[2], g[3], g[4])
                    val end = ms(g[5], g[6], g[7], g[8])
                    val body =
                        lines
                            .drop(at + 1)
                            .joinToString("\n")
                            .replace(STYLE_CODES, "")
                            .trim()
                    if (end <= start || body.isEmpty()) return@forEach
                    val shown =
                        HtmlCompat
                            .fromHtml(body.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY)
                            .trimEnd()
                    out.add(Timed(start, end, Cue.Builder().setText(shown).build()))
                }
            out.sortBy { it.startMs }
            return ShiftableSubtitles(out)
        }

        /**
         * The selected subtitle as a whole text file, or null when it can't be moved this way (picture or styled
         * subtitles, or subtitles drawn into the picture by the server).
         */
        suspend fun load(playback: CurrentPlayback): ShiftableSubtitles? {
            if (!playback.subtitleIndexEnabled) return null
            val source = playback.mediaSourceInfo
            val stream =
                source.mediaStreams?.firstOrNull {
                    it.type == MediaStreamType.SUBTITLE && it.index == playback.subtitleIndex
                } ?: return null
            if ((stream.codec ?: "").lowercase() !in TEXT_CODECS) return null
            if (stream.deliveryMethod == SubtitleDeliveryMethod.ENCODE) return null
            val sourceId = source.id ?: return null
            val path = "/Videos/${playback.itemId}/$sourceId/Subtitles/${stream.index}/0/Stream.srt"
            return withContext(WholphinDispatchers.IO) {
                try {
                    ServerAccess.get(path)?.let { parse(it) }?.takeIf { it.size > 0 }
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: subtitle for delay unavailable")
                    null
                }
            }.also { Timber.i("NoBars: subtitle delay ready for track %s: %s lines", stream.index, it?.size) }
        }
    }
}

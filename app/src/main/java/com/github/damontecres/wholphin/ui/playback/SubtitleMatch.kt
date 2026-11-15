package com.github.damontecres.wholphin.ui.playback

import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import kotlin.math.abs
import kotlin.math.log10

/**
 * NoBars: how well a subtitle found by "Search subtitles" (subbuzz: SubDL, OpenSubtitles, YIFY) matches the file
 * being played, from what subbuzz returns: the fingerprint match, the subtitle's frame rate and score (in its Comment,
 * e.g. "[opensubtitles.com] ...\nRelease.Name\n2021-01-01 | author | 23.976 | Score: 88.33 %") and its release name,
 * compared with the download's own release name (from the server, Plugins/ImdbRatings/Origin/{id}).
 */
data class SubtitleMarks(
    val good: List<String>,
    val warn: List<String>,
    val site: String?,
    val scorePct: Int?,
    val rank: Double,
)

object SubtitleMatch {
    private val SOURCES =
        mapOf(
            "bluray" to listOf("bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "uhd"),
            "web" to listOf("web-dl", "webdl", "webrip", "web", "amzn", "nf", "dsnp", "hmax", "atvp", "itunes"),
            "hdtv" to listOf("hdtv", "pdtv", "tvrip"),
            "dvd" to listOf("dvdrip", "dvd"),
        )
    private val CUTS =
        mapOf(
            "Extended" to Regex("""(?i)(?<![a-z])extended(?![a-z])"""),
            "Unrated" to Regex("""(?i)(?<![a-z])(unrated|uncut)(?![a-z])"""),
            "Director's" to Regex("""(?i)director'?s[ ._-]?cut"""),
            "Theatrical" to Regex("""(?i)(?<![a-z])theatrical(?![a-z])"""),
        )
    private val SITE = Regex("""^\s*\[([^\]]+)]""")
    private val SCORE = Regex("""(?i)score:\s*([\d.]+)\s*%""")
    private val FPS = Regex("""(?<![\d.])(23\.97\d?|24(?:\.0+)?|25(?:\.0+)?|29\.97|30(?:\.0+)?|50(?:\.0+)?|59\.94|60(?:\.0+)?)(?![\d.])""")
    private val GROUP = Regex("""-([a-z0-9]{2,12})(?:\[[^\]]*])?(?:\.(?:mkv|mp4|avi|srt|zip))?$""")
    private val BAD_SOURCE = Regex("""(?i)(?<![a-z])(cam|hdcam|ts|hdts|telesync|tc|hd-tc)(?![a-z])""")

    private fun sources(name: String): Set<String> {
        val low = name.lowercase().replace('_', '.')
        return SOURCES
            .filter { (_, words) ->
                words.any { w -> Regex("""(?<![a-z])${Regex.escape(w)}(?![a-z])""").containsMatchIn(low) }
            }.keys
    }

    private fun group(name: String): String? =
        GROUP
            .find(
                name
                    .lowercase()
                    .replace('_', '.')
                    .trim(),
            )?.groupValues
            ?.get(1)

    private fun cuts(name: String): Set<String> = CUTS.filter { it.value.containsMatchIn(name) }.keys

    /** The frame rate of the video being played, if known. */
    fun videoFps(playback: CurrentPlayback?): Double? =
        playback
            ?.mediaSourceInfo
            ?.mediaStreams
            ?.firstOrNull { it.type == MediaStreamType.VIDEO }
            ?.let { (it.realFrameRate ?: it.averageFrameRate)?.toDouble() }

    /** The file being played's own name, used when the server has no record of the original download. */
    fun fileName(playback: CurrentPlayback?): String? =
        playback
            ?.mediaSourceInfo
            ?.path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')

    fun marks(
        s: RemoteSubtitleInfo,
        videoFps: Double?,
        origin: String?,
        fileName: String?,
    ): SubtitleMarks {
        val comment = s.comment.orEmpty()
        val lines = comment.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val release =
            listOfNotNull(s.name, lines.getOrNull(1)?.takeIf { !it.contains('|') })
                .joinToString(" ")
        val site = SITE.find(comment)?.groupValues?.get(1)?.removeSuffix(".com")?.removeSuffix(".org")
        val score = SCORE.find(comment)?.groupValues?.get(1)?.toDoubleOrNull()
        val subFps =
            s.frameRate?.toDouble()?.takeIf { it > 1 }
                ?: comment
                    .split('|')
                    .firstNotNullOfOrNull { part -> FPS.matchEntire(part.trim())?.value?.toDoubleOrNull() }
        val good = mutableListOf<String>()
        val warn = mutableListOf<String>()
        var rank = 0.0

        if (s.isHashMatch == true) {
            good += "✓ Exact file"
            rank += 1000
        }
        if (videoFps != null && subFps != null) {
            if (abs(videoFps - subFps) < 0.05) {
                good += "fps ✓"
                rank += 8
            } else {
                warn += "⚠ fps mismatch (${fmtFps(subFps)} vs ${fmtFps(videoFps)})"
                rank -= 20
            }
        }
        val reference = origin ?: fileName
        if (reference != null) {
            val og = group(reference)
            val cg = group(release) ?: s.name?.let { group(it) }
            val os = sources(reference)
            val cs = sources(release)
            if (og != null && cg == og) {
                good += "Same release"
                rank += 30
            } else if (os.isNotEmpty() && cs.intersect(os).isNotEmpty()) {
                good += "Same source"
                rank += 15
            } else if (os.isNotEmpty() && cs.isNotEmpty()) {
                rank -= 5
            }
            val oc = cuts(reference)
            val cc = cuts(release)
            val extra = (cc - oc) + (oc - cc)
            if (extra.isNotEmpty()) {
                warn += "⚠ " + extra.joinToString("/") + " cut"
                rank -= 15
            }
        }
        if (BAD_SOURCE.containsMatchIn(release)) {
            warn += "⚠ Cinema copy"
            rank -= 40
        }
        if (s.machineTranslated == true || s.aiTranslated == true) {
            warn += "⚠ Machine-translated"
            rank -= 25
        }
        if (s.hearingImpaired == true) {
            good += "SDH"
        }
        if (score != null) rank += score * 0.3
        rank += log10((s.downloadCount ?: 0).toDouble() + 1) * 3
        rank += (s.communityRating ?: 0f).toDouble()
        return SubtitleMarks(good, warn, site, score?.let { Math.round(it).toInt() }, rank)
    }

    private fun fmtFps(v: Double): String = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}

@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

/**
 * NoBars: which audio and subtitle tracks of an MP4 are loaded at all.
 *
 * An MP4 makes the player build an index entry for every sample of every track before playback
 * starts; with 8 audio tracks and 46 subtitle tracks that is tens of MB held for the whole film.
 * [androidx.media3.extractor.mp4.LeanMp4Extractor] asks this object, track by track in file order,
 * whether to build that index. Kept: English, Hindi, Bangla, tracks without a language, and always
 * the first audio track (the film's original audio). The files themselves are never changed.
 *
 * Dropped tracks no longer exist for the player, so [playerIndexFor] translates Jellyfin's track
 * numbers to the kept ones. Choosing a dropped language returns -1, which makes Wholphin fall back
 * to letting the server deliver it.
 */
object LanguageKeep {
    private val KEEP = setOf("en", "hi", "bn")

    data class Result(
        val audioTotal: Int,
        val audioKept: List<Int>,
        val textTotal: Int,
        val textKept: List<Int>,
    ) {
        val dropped: Int get() = (audioTotal - audioKept.size) + (textTotal - textKept.size)
    }

    /** What the most recently parsed MP4 kept. */
    @Volatile
    var last: Result? = null
        private set

    fun normalize(language: String?): String? =
        language
            ?.takeIf { it.isNotBlank() }
            ?.let { Util.normalizeLanguageCode(it) }
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf { it !in setOf("und", "zxx", "mis", "mul", "") }

    private fun wanted(language: String?): Boolean = normalize(language)?.let { it in KEEP } ?: true

    fun newSession() = Session()

    class Session {
        private var audioCount = 0
        private var textCount = 0
        private val audioKept = mutableListOf<Int>()
        private val textKept = mutableListOf<Int>()

        fun keep(
            type: Int,
            language: String?,
        ): Boolean =
            when (type) {
                C.TRACK_TYPE_AUDIO -> {
                    val ordinal = audioCount++
                    (ordinal == 0 || wanted(language)).also { if (it) audioKept.add(ordinal) }
                }

                C.TRACK_TYPE_TEXT -> {
                    val ordinal = textCount++
                    wanted(language).also { if (it) textKept.add(ordinal) }
                }

                else -> {
                    true
                }
            }

        fun finish() {
            val result = Result(audioCount, audioKept.toList(), textCount, textKept.toList())
            last = result
            if (result.dropped > 0) {
                Timber.w(
                    "NoBars: MP4 tracks kept: audio %d of %d, subtitles %d of %d",
                    result.audioKept.size,
                    result.audioTotal,
                    result.textKept.size,
                    result.textTotal,
                )
            }
        }
    }

    /**
     * Maps a Jellyfin stream index to the position among the player's (kept) tracks of that type.
     * Returns null when no filtering applies to this source (use the normal mapping), or -1 when the
     * requested track was dropped.
     */
    fun playerIndexFor(
        serverIndex: Int,
        source: MediaSourceInfo,
        type: MediaStreamType,
        playerGroupLanguages: List<String?>,
    ): Int? {
        val result = last ?: return null
        if (result.dropped == 0) return null
        val container = source.container?.lowercase().orEmpty()
        if (!container.contains("mp4") && !container.contains("mov")) return null
        val (total, kept) =
            when (type) {
                MediaStreamType.AUDIO -> result.audioTotal to result.audioKept
                MediaStreamType.SUBTITLE -> result.textTotal to result.textKept
                else -> return null
            }
        val streams =
            source.mediaStreams
                .orEmpty()
                .filter { it.type == type && !it.isExternal }
        val ordinal = streams.indexOfFirst { it.index == serverIndex }
        if (ordinal < 0) return null
        if (streams.size == total && playerGroupLanguages.size == kept.size) {
            // Exact: same tracks on both sides, in file order
            return kept.indexOf(ordinal)
        }
        // Counts differ (the server sees a track the player does not, or the reverse): match by
        // language instead, the n-th track of that language on both sides
        val language = normalize(streams[ordinal].language)
        val occurrence = streams.take(ordinal).count { normalize(it.language) == language }
        val matches = playerGroupLanguages.withIndex().filter { normalize(it.value) == language }
        Timber.w(
            "NoBars: track counts differ (server %d, file %d, player %d); matching %s #%d by language",
            streams.size,
            total,
            playerGroupLanguages.size,
            language,
            occurrence,
        )
        return matches.getOrNull(occurrence)?.index ?: -1
    }
}

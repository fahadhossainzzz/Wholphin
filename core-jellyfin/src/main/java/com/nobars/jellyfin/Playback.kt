package com.nobars.jellyfin

import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/**
 * NoBars: starting a film or episode on the phone. Always plays the file as it is (direct play, like the TVs); the
 * phone's player reads it straight from Jellyfin with the login in the request header ([authHeader]). Subtitles kept
 * in separate files are offered to the player as extra tracks. Progress goes back to Jellyfin, so Continue Watching
 * matches on every device.
 */
class Playback internal constructor(
    private val nb: NbJellyfin,
) {
    data class SubtitleFile(
        val index: Int,
        val url: String,
        val mimeType: String,
        val language: String?,
        val label: String,
    )

    data class Prepared(
        val item: BaseItemDto,
        val source: MediaSourceInfo,
        val playSessionId: String?,
        val url: String,
        val subtitles: List<SubtitleFile>,
        val startMs: Long,
    )

    private val api get() = nb.api

    /** "MediaBrowser Token=…" for the player's requests (stream and subtitle files). */
    fun authHeader(): Pair<String, String> = "Authorization" to "MediaBrowser Token=\"${nb.session.value?.token.orEmpty()}\""

    suspend fun prepare(
        itemId: UUID,
        fromStart: Boolean,
    ): Prepared {
        val uid = nb.session.value?.userId
        val item = api.userLibraryApi.getItem(itemId = itemId, userId = uid).content
        val info =
            api.mediaInfoApi
                .getPostedPlaybackInfo(
                    itemId,
                    PlaybackInfoDto(userId = uid, enableDirectPlay = true, enableDirectStream = false, enableTranscoding = false, autoOpenLiveStream = false),
                ).content
        val source = info.mediaSources.firstOrNull() ?: error("Jellyfin has no file for this title")
        val url =
            api.videosApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
            )
        val base = nb.session.value?.serverUrl?.trimEnd('/').orEmpty()
        val subs =
            source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && it.isExternal && it.isTextSubtitleStream }.mapNotNull { s ->
                val (ext, mime) =
                    when (s.codec?.lowercase(Locale.US)) {
                        "subrip", "srt" -> "srt" to "application/x-subrip"
                        "ass", "ssa" -> "ass" to "text/x-ssa"
                        "webvtt", "vtt" -> "vtt" to "text/vtt"
                        else -> return@mapNotNull null
                    }
                SubtitleFile(
                    index = s.index,
                    url = "$base/Videos/$itemId/${source.id}/Subtitles/${s.index}/0/Stream.$ext",
                    mimeType = mime,
                    language = s.language,
                    label = s.displayTitle ?: s.language ?: "Subtitles",
                )
            }
        val resume = item.userData?.playbackPositionTicks ?: 0L
        return Prepared(item, source, info.playSessionId, url, subs, if (fromStart) 0L else resume / 10_000)
    }

    suspend fun reportStart(
        p: Prepared,
        positionMs: Long,
    ) = report("start") {
        api.playStateApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = p.item.id,
                mediaSourceId = p.source.id,
                isPaused = false,
                isMuted = false,
                positionTicks = positionMs * 10_000,
                playMethod = PlayMethod.DIRECT_PLAY,
                playSessionId = p.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    suspend fun reportProgress(
        p: Prepared,
        positionMs: Long,
        paused: Boolean,
    ) = report("progress") {
        api.playStateApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true,
                itemId = p.item.id,
                mediaSourceId = p.source.id,
                isPaused = paused,
                isMuted = false,
                positionTicks = positionMs * 10_000,
                playMethod = PlayMethod.DIRECT_PLAY,
                playSessionId = p.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    suspend fun reportStop(
        p: Prepared,
        positionMs: Long,
    ) = report("stop") {
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = p.item.id,
                mediaSourceId = p.source.id,
                positionTicks = positionMs * 10_000,
                playSessionId = p.playSessionId,
                failed = false,
            ),
        )
    }

    private suspend fun report(
        what: String,
        call: suspend () -> Unit,
    ) {
        try {
            call()
        } catch (e: Exception) {
            Timber.w(e, "NoBars: playback %s report failed", what)
        }
    }
}

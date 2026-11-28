package com.github.damontecres.wholphin.nobars.playback

import org.jellyfin.sdk.model.api.MediaSourceInfo
import kotlin.time.Duration

/**
 * NoBars: what the playback engine needs to know about the film being played. Each app (TV, phone) passes in its own
 * playback state through this; the TV app's CurrentPlayback implements it.
 */
interface PlaybackInfo {
    val mediaSourceInfo: MediaSourceInfo
    val audioIndex: Int
    val subtitleIndex: Int
    val subtitleDelay: Duration
    val videoDecoder: String?
    val subtitleIndexEnabled: Boolean
}

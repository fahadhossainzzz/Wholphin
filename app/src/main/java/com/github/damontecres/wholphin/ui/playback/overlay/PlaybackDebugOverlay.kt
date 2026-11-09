package com.github.damontecres.wholphin.ui.playback.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.services.stream.StreamHealth
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.TrackSupport
import com.github.damontecres.wholphin.util.TrackSupportReason
import com.github.damontecres.wholphin.util.TrackType
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.HardwareAccelerationType
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodeReason
import org.jellyfin.sdk.model.api.TranscodingInfo
import timber.log.Timber
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
fun PlaybackDebugOverlay(
    analyticsState: AnalyticsState,
    currentPlayback: CurrentPlayback?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val display =
        remember(context) {
            try {
                val displayManager =
                    context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } catch (ex: Exception) {
                Timber.e(ex)
                null
            }
        }
    val displayMode by produceState<String?>(null) {
        while (isActive) {
            value =
                display?.mode?.let {
                    val rate = String.format(Locale.getDefault(), "%.3f", it.refreshRate)
                    "${it.physicalWidth}x${it.physicalHeight}@${rate}fps, id=${it.modeId}"
                }
            delay(10.seconds)
        }
    }
    val textStyle =
        MaterialTheme.typography.bodySmall.copy(
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

    // NoBars: live figures, refreshed every second
    var live by remember { mutableStateOf(0) }
    val memoryUsed by produceState("") {
        withContext(WholphinDispatchers.Default) {
            while (isActive) {
                val used = StreamHealth.heapUsed()
                val max = StreamHealth.heapMax()
                value = "${formatBytes(used)} / ${formatBytes(max)} (${used * 100 / max}%)"
                live++
                delay(1.seconds)
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
    ) {
        ProvideTextStyle(textStyle) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // 1. The file
                DebugSection(
                    "THE FILE",
                    remember(currentPlayback) {
                        listOf(
                            DebugRow("File", PlaybackFacts.file(currentPlayback)),
                            DebugRow("Overall", PlaybackFacts.overall(currentPlayback)),
                            DebugRow("Video", PlaybackFacts.video(currentPlayback)),
                            DebugRow("Audio", PlaybackFacts.audio(currentPlayback)),
                            DebugRow("Subtitle", PlaybackFacts.subtitle(currentPlayback)),
                            DebugRow("Play method", currentPlayback?.playMethod?.serialName ?: "?"),
                        )
                    },
                    modifier = Modifier.weight(1.3f),
                )
                // 2. Right now: the film's own rate, network, buffer
                val scene = StreamHealth.sceneMbps
                val conn = StreamHealth.readMbps
                val ratio = if (scene > 0.5f && conn > 0f) conn / maxOf(scene, StreamHealth.scenePeakMbps) else 0f
                DebugSection(
                    "RIGHT NOW",
                    listOf(
                        DebugRow(
                            "Film rate",
                            if (scene > 0f) {
                                String.format(Locale.US, "%.0f Mbps loading now · peak %.0f Mbps (last minute of film)", scene, StreamHealth.scenePeakMbps)
                            } else {
                                "measuring…"
                            },
                        ),
                        DebugRow(
                            "Headroom",
                            if (ratio > 0f) String.format(Locale.US, "connection %.0f Mbps = %.1f× the peak", conn, ratio) else "measuring…",
                            when {
                                ratio <= 0f -> Color.Unspecified
                                ratio >= 2f -> Color(0xFF81C784)
                                ratio >= 1.2f -> Color(0xFFFFD54F)
                                else -> Color(0xFFE57373)
                            },
                        ),
                        DebugRow(
                            "Read-ahead",
                            String.format(
                                Locale.US,
                                "%.1f s · %d of %d MB · %s",
                                StreamHealth.aheadMs / 1000f,
                                StreamHealth.bufferBytes shr 20,
                                StreamHealth.bufferBudget shr 20,
                                StreamHealth.bufferState,
                            ),
                            if (StreamHealth.aheadMs in 1..9_999) Color(0xFFFFD54F) else Color.Unspecified,
                        ),
                        DebugRow(
                            "Into player",
                            String.format(Locale.US, "%.1f Mbps now · connection %.0f Mbps (%d%% busy)", StreamHealth.netMbps, conn, StreamHealth.readBusy),
                        ),
                        DebugRow("Last chunk", "${analyticsState.bitrate} download speed"),
                        DebugRow("Estimate", "${analyticsState.bitrateEstimate} (player's guess of the connection)"),
                        DebugRow(
                            "Wi-Fi",
                            if (StreamHealth.wifiRssi == 0) {
                                "no reading (Ethernet?)"
                            } else {
                                "${StreamHealth.wifiRssi} dBm · link ${StreamHealth.wifiLinkMbps} Mbps · " +
                                    if (StreamHealth.wifiFreqMhz >= 4900) "5 GHz" else "2.4 GHz"
                            },
                            if (StreamHealth.wifiRssi < -70 && StreamHealth.wifiRssi != 0) Color(0xFFFFD54F) else Color.Unspecified,
                        ),
                    ).also { live.hashCode() },
                    modifier = Modifier.weight(1.2f),
                )
                // 3. The TV and the health of this playback
                DebugSection(
                    "TV AND HEALTH",
                    listOf(
                        DebugRow("Video decoder", currentPlayback?.videoDecoder ?: currentPlayback?.backend?.toString() ?: "?"),
                        DebugRow(
                            "Audio path",
                            StreamHealth.audioPath(StreamHealth.audioDecoder.ifBlank { currentPlayback?.audioDecoder.orEmpty() }, StreamHealth.audioOut),
                        ),
                        DebugRow("Display", displayMode ?: "?"),
                        DebugRow("App memory", memoryUsed),
                        DebugRow("TV RAM", "${StreamHealth.systemAvailMb} MB free (${StreamHealth.systemSpareMb} spare)"),
                        DebugRow(
                            "Dropped",
                            "${analyticsState.droppedFrames} this film · ${StreamHealth.recentDrops} in last 10 min",
                            if (StreamHealth.recentDrops >= 10) Color(0xFFFFD54F) else Color.Unspecified,
                        ),
                        DebugRow("Waits", StreamHealth.recentWaits),
                        DebugRow("Fixes", "${StreamHealth.reconnects} reconnect / ${StreamHealth.reloads} reload / ${StreamHealth.rebuilds} rebuild"),
                        DebugRow("Last event", StreamHealth.lastEvent.ifEmpty { "none" }),
                        DebugRow("Report", StreamHealth.reportStatus),
                        DebugRow("Time", StreamHealth.now()),
                    ).also { live.hashCode() },
                    modifier = Modifier.weight(1.2f),
                )
            }
            currentPlayback?.transcodeInfo?.let { info ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SimpleTable(
                        listOf(
                            "Reason:" to info.transcodeReasons.joinToString(", "),
                            "HW Accel:" to info.hardwareAccelerationType?.toString(),
                            "Container:" to info.container,
                            "Bitrate:" to info.bitrate?.let { formatBitrate(it) },
                        ),
                        keyWidth = 64.dp,
                    )
                    SimpleTable(
                        listOf(
                            "Video:" to "${info.videoCodec}, ${info.width}x${info.height}",
                            "Video Direct:" to info.isVideoDirect.toString(),
                            "Audio:" to "${info.audioCodec}, ch=${info.audioChannels}",
                            "Audio Direct:" to info.isAudioDirect.toString(),
                        ),
                        keyWidth = 64.dp,
                    )
                }
            }
        }
        currentPlayback?.tracks?.letNotEmpty {
            PlaybackTrackInfo(
                trackSupport = it,
                textStyle = textStyle,
            )
        }
    }
}

/** NoBars: one line of the debug panel, optionally coloured (green / yellow / red). */
data class DebugRow(
    val key: String,
    val value: String,
    val color: Color = Color.Unspecified,
)

@Composable
private fun DebugSection(
    title: String,
    rows: List<DebugRow>,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier) {
        Text(text = title, color = Color.White.copy(alpha = 0.6f))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = r.key, modifier = Modifier.width(72.dp), color = Color.White.copy(alpha = 0.7f))
                Text(text = r.value, color = if (r.color == Color.Unspecified) Color.White else r.color)
            }
        }
    }
}

@Composable
fun SimpleTable(
    rows: List<Pair<String, Any?>>,
    modifier: Modifier = Modifier,
    keyWidth: Dp = 100.dp,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        rows.forEach {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = it.first,
                    modifier = Modifier.width(keyWidth),
                )
                Text(
                    text = it.second.toString(),
                    modifier = Modifier,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@PreviewTvSpec
@Composable
fun PlaybackDebugOverlayPreview() {
    val currentPlayback =
        CurrentPlayback(
            item =
                BaseItem(
                    data =
                        BaseItemDto(
                            id = UUID.randomUUID(),
                            type = BaseItemKind.EPISODE,
                        ),
                ),
            backend = PlayerBackend.EXO_PLAYER,
            playMethod = PlayMethod.TRANSCODE,
            playSessionId = "123",
            liveStreamId = "123",
            videoDecoder = "video.decoder.name",
            audioDecoder = null, // "audio.decoder.name",
            mediaSourceInfo =
                MediaSourceInfo(
                    protocol = MediaProtocol.HTTP,
                    type = MediaSourceType.DEFAULT,
                    isRemote = false,
                    readAtNativeFramerate = true,
                    ignoreDts = true,
                    ignoreIndex = true,
                    genPtsInput = false,
                    supportsTranscoding = true,
                    supportsDirectStream = true,
                    supportsDirectPlay = true,
                    isInfiniteStream = false,
                    requiresOpening = false,
                    requiresClosing = false,
                    requiresLooping = false,
                    supportsProbing = true,
                    transcodingSubProtocol = MediaStreamProtocol.HTTP,
                    hasSegments = false,
                ),
            transcodeInfo =
                TranscodingInfo(
                    videoCodec = "h264",
                    audioCodec = "aac",
                    container = "HLS",
                    width = 1080,
                    height = 1920,
                    isVideoDirect = false,
                    isAudioDirect = false,
                    transcodeReasons =
                        listOf(
                            TranscodeReason.VIDEO_PROFILE_NOT_SUPPORTED,
                            TranscodeReason.AUDIO_CHANNELS_NOT_SUPPORTED,
                        ),
                    hardwareAccelerationType = HardwareAccelerationType.NONE,
                ),
            tracks =
                listOf(
                    TrackSupport(
                        id = "0",
                        type = TrackType.VIDEO,
                        supported = TrackSupportReason.EXCEEDS_CAPABILITIES,
                        selected = true,
                        labels = listOf(),
                        codecs = "avc1",
                        format = Format.Builder().build(),
                    ),
                    TrackSupport(
                        id = "0",
                        type = TrackType.AUDIO,
                        supported = TrackSupportReason.HANDLED,
                        selected = true,
                        labels = listOf(),
                        codecs = "ac3",
                        format = Format.Builder().build(),
                    ),
                ) +
                    List(10) {
                        TrackSupport(
                            id = "0",
                            type = TrackType.TEXT,
                            supported = TrackSupportReason.HANDLED,
                            selected = false,
                            labels = listOf(),
                            codecs = "srt",
                            format = Format.Builder().build(),
                        )
                    },
        )
    WholphinTheme {
        PlaybackDebugOverlay(
            analyticsState = AnalyticsState(),
            currentPlayback = currentPlayback,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

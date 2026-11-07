package com.github.damontecres.wholphin.ui.playback.overlay

import com.github.damontecres.wholphin.services.LocalImdbVotes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.MediaSegmentDto
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun PlaybackController(
    item: BaseItem?,
    nextState: OverlayViewState?,
    player: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekBarChange: (Long) -> Unit,
    currentSegment: MediaSegmentDto?,
    onChangeState: (OverlayViewState) -> Unit,
    modifier: Modifier = Modifier,
    quickSettings: List<QuickSetting> = emptyList(),
    seekBarInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Controller(
            title = item?.title,
            ratingLine = remember(item?.id, LocalImdbVotes.current.size) { item?.let(::playerRatingLine) },
            subtitle = item?.subtitleLong,
            player = player,
            controllerViewState = controllerViewState,
            showPlay = showPlay,
            previousEnabled = previousEnabled,
            nextEnabled = nextEnabled,
            seekEnabled = seekEnabled,
            seekBack = seekBack,
            skipBackOnResume = skipBackOnResume,
            seekForward = seekForward,
            onPlaybackActionClick = onPlaybackActionClick,
            onClickPlaybackDialogType = onClickPlaybackDialogType,
            onSeekProgress = onSeekBarChange,
            seekBarInteractionSource = seekBarInteractionSource,
            currentSegment = currentSegment,
            quickSettings = quickSettings,
            modifier = Modifier,
        )
        when (nextState) {
            OverlayViewState.CHAPTERS -> {
                Text(
                    text = stringResource(R.string.chapters),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, top = 0.dp)
                            .onFocusChanged {
                                if (it.isFocused) onChangeState.invoke(nextState)
                            }.focusable(),
                )
            }

            OverlayViewState.PEOPLE -> {
                Text(
                    text = stringResource(R.string.cast_and_crew),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, top = 0.dp)
                            .onFocusChanged {
                                if (it.isFocused) onChangeState.invoke(nextState)
                            }.focusable(),
                )
            }

            OverlayViewState.QUEUE -> {
                Text(
                    text = stringResource(R.string.queue),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, top = 0.dp)
                            .onFocusChanged {
                                if (it.isFocused) onChangeState.invoke(nextState)
                            }.focusable(),
                )
            }

            else -> {
                Unit
            }
        }
    }
}

internal val titleTextSize = 28.sp
internal val subtitleTextSize = 18.sp

/**
 * A wrapper for the playback controls to show title and other information, plus the actual controls
 *
 * @see PlaybackControls
 */
@Composable
fun Controller(
    title: String?,
    ratingLine: String? = null,
    player: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekProgress: (Long) -> Unit,
    currentSegment: MediaSegmentDto?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    quickSettings: List<QuickSetting> = emptyList(),
    seekBarInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val seekBarFocused by seekBarInteractionSource.collectIsFocusedAsState()
    val verticalOffset by animateDpAsState(
        targetValue = if (seekBarFocused) (-32).dp else 0.dp,
        label = "TitleBumpOffset",
        animationSpec =
            spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = Dp.VisibilityThreshold,
            ),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .padding(start = 16.dp)
                    .offset(y = verticalOffset),
        ) {
            title?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth(.9f),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = titleTextSize,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // NoBars: "★ 7.8 · 1.2M votes   🍅 93%"
                    ratingLine?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = subtitleTextSize,
                            color = com.github.damontecres.wholphin.ui.components.FilledStarColor,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.End),
            ) {
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = subtitleTextSize,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.fillMaxWidth(.75f),
                    )
                }

                val context = LocalContext.current
                var endTimeStr by remember { mutableStateOf("...") }
                LaunchedEffect(player, context) {
                    while (isActive) {
                        val remaining =
                            (player.duration - player.currentPosition)
                                .div(player.playbackParameters.speed)
                                .toLong()
                                .milliseconds
                        val endTime = LocalTime.now().plusSeconds(remaining.inWholeSeconds)
                        endTimeStr = formatTime(context, endTime)
                        delay(1.seconds)
                    }
                }
                Text(
                    text = stringResource(R.string.ends_at, endTimeStr),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier =
                        Modifier
                            .padding(end = 32.dp),
                )
            }
        }
        PlaybackControls(
            modifier = Modifier.fillMaxWidth(),
            player = player,
            onPlaybackActionClick = onPlaybackActionClick,
            controllerViewState = controllerViewState,
            onSeekProgress = {
                onSeekProgress(it)
            },
            showPlay = showPlay,
            previousEnabled = previousEnabled,
            nextEnabled = nextEnabled,
            seekEnabled = seekEnabled,
            seekBarInteractionSource = seekBarInteractionSource,
            seekBarIntervals = 16,
            seekBack = seekBack,
            seekForward = seekForward,
            skipBackOnResume = skipBackOnResume,
            currentSegment = currentSegment,
            onClickPlaybackDialogType = onClickPlaybackDialogType,
            quickSettings = quickSettings,
        )
    }
}

/** NoBars: the rating shown next to the film's name in the player. */
private fun playerRatingLine(item: com.github.damontecres.wholphin.data.model.BaseItem): String? {
    val stars = item.data.communityRating?.takeIf { it > 0f }
    val tomatoes = item.data.criticRating?.takeIf { it >= 0f }
    val votes = com.github.damontecres.wholphin.services.ImdbVotesStore.get(item.id)?.votes
    return buildList {
        stars?.let {
            add("★ " + String.format(java.util.Locale.US, "%.1f", it) + (votes?.let { v -> " · " + com.github.damontecres.wholphin.services.ImdbVotesStore.short(v) + " votes" } ?: ""))
        }
        tomatoes?.let { add("🍅 ${it.toInt()}%") }
        com.github.damontecres.wholphin.services.BoxOffice.forItem(item.id)?.let { m ->
            com.github.damontecres.wholphin.services.BoxOffice.compact(m)?.let { add(it) }
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("   ")
}

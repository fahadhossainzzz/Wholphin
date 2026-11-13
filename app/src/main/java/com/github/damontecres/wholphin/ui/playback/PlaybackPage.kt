package com.github.damontecres.wholphin.ui.playback

import com.github.damontecres.wholphin.R
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.ui.playback.overlay.QuickSetting
import com.github.damontecres.wholphin.ui.playback.overlay.PlayerPeopleViewModel
import com.github.damontecres.wholphin.ui.playback.overlay.PersonPanel
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.children
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.surfaceColorAtElevation
import com.github.damontecres.wholphin.mpv.MpvPlayer
import com.github.damontecres.wholphin.preferences.AssPlaybackMode
import com.github.damontecres.wholphin.preferences.DpadSeekMode
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.skipBackOnResume
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.overlay.DpadSeekOverlay
import com.github.damontecres.wholphin.ui.playback.overlay.PauseIndicator
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackAction
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackOverlay
import com.github.damontecres.wholphin.ui.playback.overlay.SkipIndicator
import com.github.damontecres.wholphin.ui.playback.overlay.SkipSegmentButton
import com.github.damontecres.wholphin.ui.playback.overlay.rememberSeekBarState
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.applyTo
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.damontecres.wholphin.ui.seasonEpisode
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.Media3SubtitleOverride
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The actual playback page which shows media & playback controls
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel =
        hiltViewModel<PlaybackViewModel, PlaybackViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    LifecycleStartEffect(destination) {
        onStopOrDispose {
            viewModel.release()
        }
    }
    val state by viewModel.state.collectAsState()
    when (val st = state.loading) {
        is LoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        LoadingState.Pending,
        LoadingState.Loading,
        -> {
            LoadingPage(modifier.background(Color.Black))
        }

        LoadingState.Success -> {
            val playerState by viewModel.currentPlayer.collectAsState()
            PlaybackPageContent(
                playerInstance = playerState!!,
                preferences = preferences,
                destination = destination,
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlaybackPageContent(
    playerInstance: PlayerInstance,
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel,
) {
    val state by viewModel.state.collectAsState()
    val subtitleSearchState by viewModel.subtitleSearchState.collectAsState()
    val player = playerInstance.player
    val playerBackend = playerInstance.backend

    val prefs = preferences.appPreferences.playbackPreferences
    // NoBars: left/right always scrub with the seek bar and thumbnails (unless set to "none")
    val dpadSeekMode = if (prefs.dpadSeekMode == DpadSeekMode.SEEKBAR_NONE) DpadSeekMode.SEEKBAR_NONE else DpadSeekMode.SEEKBAR_TRICKPLAY
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(density) { viewModel.updateDensity(density) }
    val userDto by viewModel.currentUserDto.collectAsState()

    var showDebugInfo by remember { mutableStateOf(prefs.showDebugInfo) }

    var playbackDialog by remember { mutableStateOf<PlaybackDialogType?>(null) }

    var contentScale by remember(playerBackend) {
        mutableStateOf(
            if (playerBackend == PlayerBackend.MPV) {
                ContentScale.FillBounds
            } else {
                prefs.globalContentScale.scale
            },
        )
    }
    // NoBars: extra zoom from the remote's up/down buttons, back to automatic for every new video
    val currentItemId = state.currentPlayback?.item?.id
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(currentItemId) { zoom = 1f }
    val onChangeZoom: (Float) -> Unit = { value ->
        zoom = value.coerceIn(ZoomPolicy.MIN_ZOOM, ZoomPolicy.MAX_ZOOM)
    }
    // NoBars: zoom sits on top of whichever scale is chosen (Crop + 105% stays Crop, 5% bigger)
    val zooming = kotlin.math.abs(zoom - 1f) > 0.001f
    val displayScale =
        remember(contentScale, zoom) { if (zooming) ZoomedContentScale(contentScale, zoom) else contentScale }

    // NoBars: Dolby Vision can't be zoomed on some TVs, so switch to the HDR10 decoder when zooming is needed
    val needsZoom = playerBackend != PlayerBackend.MPV && zooming
    LaunchedEffect(needsZoom, currentItemId) {
        if (needsZoom && !ZoomPolicy.avoidDolbyVision) {
            ZoomPolicy.avoidDolbyVision = true
            if (ZoomPolicy.isPlayingDolbyVision(player)) {
                ZoomPolicy.restartVideoDecoder(player)
            }
        }
    }
    // NoBars: free the poster/artwork memory cache while a video plays, so the video buffer has more room
    val imageContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        coil3.SingletonImageLoader
            .get(imageContext)
            .memoryCache
            ?.clear()
    }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

    // NoBars: speed, scale and zoom on the control bar, showing their current value
    val playbackSpeedEnabled = playerBackend == PlayerBackend.MPV || state.currentPlayback?.audioDecoder != null
    val quickSettings =
        buildList {
            add(QuickSetting(PlaybackDialogType.PLAYBACK_SPEED, "${playbackSpeed}×", playbackSpeedEnabled))
            if (player !is MpvPlayer) {
                val scaleLabel =
                    playbackScaleOptions[contentScale]?.let { stringResource(it) } ?: stringResource(R.string.video_scale)
                add(QuickSetting(PlaybackDialogType.VIDEO_SCALE, scaleLabel))
                add(QuickSetting(PlaybackDialogType.ZOOM, "Zoom ${kotlin.math.round(zoom * 100).toInt()}%"))
            }
        }

    // NoBars: cast & crew row and the light person panel over the film
    val peopleViewModel: PlayerPeopleViewModel = hiltViewModel()
    LaunchedEffect(currentItemId) { peopleViewModel.load(state.currentPlayback?.item) }
    val people by peopleViewModel.people.collectAsState()
    val personPanel by peopleViewModel.panel.collectAsState()
    val returnToPerson by peopleViewModel.returnTo.collectAsState()
    LaunchedEffect(returnToPerson) { if (returnToPerson != null) viewModel.controllerViewState.showControls() }
    val navigationManager = viewModel.navigationManager
    // Leaving the player for another page: it shuts down (freeing its memory) and Back restarts the film here
    val restartHereAfter: (Destination) -> Unit = { next ->
        val id = state.currentPlayback?.item?.id
        val position = player.currentPosition
        peopleViewModel.close()
        navigationManager.goBack()
        if (id != null) navigationManager.navigateTo(Destination.Playback(id, position))
        navigationManager.navigateTo(next)
    }

    LaunchedEffect(state.currentPlayback?.subtitleDelay) {
        (player as? MpvPlayer)?.subtitleDelay =
            state.currentPlayback?.subtitleDelay ?: Duration.ZERO
    }
    // NoBars: subtitle delay for ExoPlayer: text subtitles shown by the app at (position - delay), see SubtitleShift.kt
    val shiftable by androidx.compose.runtime.produceState<ShiftableSubtitles?>(
        null,
        state.currentPlayback?.itemId,
        state.currentPlayback?.subtitleIndex,
        player,
    ) {
        value = null
        val playback = state.currentPlayback
        if (player !is MpvPlayer && playback != null) value = ShiftableSubtitles.load(playback)
    }
    val exoSubtitleDelayMs =
        if (player is MpvPlayer) 0L else (state.currentPlayback?.subtitleDelay ?: Duration.ZERO).inWholeMilliseconds
    var shiftedCues by remember { mutableStateOf<List<androidx.media3.common.text.Cue>?>(null) }
    LaunchedEffect(shiftable, exoSubtitleDelayMs) {
        val subs = shiftable
        if (subs == null || exoSubtitleDelayMs == 0L) {
            shiftedCues = null
            return@LaunchedEffect
        }
        while (true) {
            val now = subs.at(player.currentPosition - exoSubtitleDelayMs)
            if (now != shiftedCues) shiftedCues = now
            delay(50)
        }
    }

    val presentationState = rememberPresentationState(player, false)
    val playbackState by rememberPlayerState(player)
    var showBuffering by remember { mutableStateOf(false) }
    LaunchedEffect(playbackState) {
        if (playbackState == PlayerState.BUFFERING) {
            // Delay before showing the loading indicator
            // So if buffering is quick, the UI won't flash
            delay(250)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }
    val scaledModifier =
        Modifier.resizeWithContentScale(displayScale, presentationState.videoSizeDp)
    val focusRequester = remember { FocusRequester() }
    val playPauseState = rememberPlayPauseButtonState(player)
    val seekBarState = rememberSeekBarState(player, scope)

    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
    }
    val controllerViewState = remember { viewModel.controllerViewState }

    var skipIndicatorDuration by remember { mutableLongStateOf(0L) }
    // NoBars: scrubbing like Netflix. The film pauses while you choose a spot; OK (or Play) jumps there and plays,
    // Back puts you back where you were. Nothing jumps by itself.
    var scrubResume by remember { mutableStateOf(false) }
    val cancelScrub = {
        if (skipIndicatorDuration != 0L && dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY) {
            skipIndicatorDuration = 0L
            if (scrubResume) player.play()
        }
    }
    LaunchedEffect(controllerViewState.controlsVisible) {
        // If controller shows/hides, immediately cancel the skip indicator
        if (controllerViewState.controlsVisible) cancelScrub()
        skipIndicatorDuration = 0L
    }
    var skipPosition by remember { mutableLongStateOf(0L) }
    val updateSkipIndicator = { delta: Long ->
        if ((skipIndicatorDuration > 0 && delta < 0) || (skipIndicatorDuration < 0 && delta > 0)) {
            skipIndicatorDuration = 0
        }
        skipIndicatorDuration += delta
        skipPosition = player.currentPosition
    }
    val onDpadSeek: (Long) -> Unit = { deltaMs ->
        if (skipIndicatorDuration == 0L) {
            skipPosition = player.currentPosition
            scrubResume = player.isPlaying || player.playWhenReady
            player.pause()
        }
        if ((skipIndicatorDuration > 0 && deltaMs < 0) ||
            (skipIndicatorDuration < 0 && deltaMs > 0)
        ) {
            // changing direction: keep the overlay (0 would close it)
            skipIndicatorDuration = if (deltaMs > 0) 1L else -1L
        }
        skipIndicatorDuration += deltaMs
        if (skipIndicatorDuration == 0L) skipIndicatorDuration = if (deltaMs > 0) 1L else -1L
        skipPosition =
            (skipPosition + deltaMs).coerceIn(
                minimumValue = 0L,
                maximumValue = player.duration.coerceAtLeast(0L),
            )
    }
    val commitScrub = {
        if (skipIndicatorDuration != 0L) {
            skipIndicatorDuration = 0L
            player.seekTo(skipPosition)
            player.play()
        }
    }
    androidx.activity.compose.BackHandler(skipIndicatorDuration != 0L && dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY) {
        cancelScrub()
    }
    // NoBars: the same on the controls' seek bar: moving it pauses the film and nothing jumps by itself;
    // OK / Play jumps there and plays; Back, or leaving the bar, keeps the film where it was.
    val seekBarSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val seekBarFocused by seekBarSource.collectIsFocusedAsState()
    var barPending by remember { mutableStateOf<Long?>(null) }
    var barResume by remember { mutableStateOf(false) }
    val onBarSeek: (Long) -> Unit = { pos ->
        if (barPending == null) {
            barResume = player.isPlaying || player.playWhenReady
            player.pause()
        }
        controllerViewState.pulseControls(Long.MAX_VALUE)
        barPending = pos
    }
    val commitBarSeek = {
        barPending?.let {
            barPending = null
            player.seekTo(it)
            player.play()
            com.github.damontecres.wholphin.ui.playback.overlay.SeekBarReset.count++
            controllerViewState.pulseControls()
        }
        Unit
    }
    val cancelBarSeek = {
        if (barPending != null) {
            barPending = null
            com.github.damontecres.wholphin.ui.playback.overlay.SeekBarReset.count++
            if (barResume) player.play()
            controllerViewState.pulseControls()
        }
    }
    LaunchedEffect(seekBarFocused) { if (!seekBarFocused) cancelBarSeek() }
    androidx.activity.compose.BackHandler(barPending != null) { cancelBarSeek() }
    val keyHandler =
        remember(preferences) {
            PlaybackKeyHandler(
                player = player,
                controlsEnabled = state.nextUp == null,
                skipWithLeftRight = true,
                seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                getDurationMs = { player.duration.coerceAtLeast(0L) },
                controllerViewState = controllerViewState,
                updateSkipIndicator = updateSkipIndicator,
                clearSkipIndicator = { skipIndicatorDuration = 0 },
                onCommitDpadSeek = commitScrub,
                hasPendingBarSeek = { barPending != null },
                onCommitBarSeek = commitBarSeek,
                skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                onInteraction = viewModel::reportInteraction,
                oneClickPause = preferences.appPreferences.playbackPreferences.oneClickPause,
                onStop = {
                    player.stop()
                    viewModel.navigationManager.goBack()
                },
                onPlaybackDialogTypeClick = { playbackDialog = it },
                isDpadSeekVisible = {
                    dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY && skipIndicatorDuration != 0L
                },
                onDpadSeek = onDpadSeek,
                dpadSeekMode = dpadSeekMode,
                onSeekToNext = viewModel::playNextUp,
                onPSeekToPrevious = viewModel::playPrevious,
            )
        }

    val onPlaybackActionClick: (PlaybackAction) -> Unit = {
        when (it) {
            is PlaybackAction.PlaybackSpeed -> {
                playbackSpeed = it.value
            }

            is PlaybackAction.Scale -> {
                contentScale = it.scale
            }

            PlaybackAction.ShowDebug -> {
                showDebugInfo = !showDebugInfo
            }

            PlaybackAction.SendLogs -> {
                viewModel.sendAppLogs()
            }

            PlaybackAction.ShowPlaylist -> {
                TODO()
            }

            PlaybackAction.ShowVideoFilterDialog -> {
                TODO()
            }

            is PlaybackAction.ToggleAudio -> {
                viewModel.changeAudioStream(it.index)
            }

            is PlaybackAction.ToggleCaptions -> {
                viewModel.changeSubtitleStream(it.index)
            }

            PlaybackAction.SearchCaptions -> {
                controllerViewState.hideControls()
                viewModel.searchForSubtitles()
            }

            PlaybackAction.Next -> {
                // TODO focus is lost
                viewModel.playNextUp()
            }

            PlaybackAction.Previous -> {
                val pos = player.currentPosition
                if (pos < player.maxSeekToPreviousPosition && state.hasPrevious) {
                    viewModel.playPrevious()
                } else {
                    player.seekToPrevious()
                }
            }

            is PlaybackAction.ChangeBitrate -> {
                viewModel.changeMaxBitrate(it.maxBitrate)
            }
        }
    }

    val showSegment =
        state.currentSegment?.interacted == false &&
            state.nextUp == null && !controllerViewState.controlsVisible && skipIndicatorDuration == 0L
    BackHandler(showSegment) {
        viewModel.updateSegment(state.currentSegment?.segment?.id, true)
    }

    Box(
        modifier
            .background(if (state.nextUp == null) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        val playerSize by animateFloatAsState(if (state.nextUp == null) 1f else .6f)
        Box(
            modifier =
                Modifier
                    .fillMaxSize(playerSize)
                    .align(Alignment.TopCenter)
                    .onKeyEvent(keyHandler::onKeyEvent)
                    .focusRequester(focusRequester)
                    .focusable(),
        ) {
            var playerSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier =
                    scaledModifier.onSizeChanged {
                        playerSurfaceSize = it
                    },
            )
            if (presentationState.coverSurface) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black),
                ) {
                    LoadingPage(focusEnabled = false)
                }
            } else {
                AnimatedVisibility(
                    visible = showBuffering,
                    enter = fadeIn(tween(easing = LinearEasing)),
                    exit = fadeOut(tween(easing = LinearEasing)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    LoadingPage(
                        focusEnabled = false,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(AppColors.TransparentBlack25),
                    )
                }
            }

            AnimatedVisibility(
                visible =
                    !controllerViewState.controlsVisible &&
                        skipIndicatorDuration != 0L &&
                        dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DpadSeekOverlay(
                        player = player,
                        seekPositionMs = skipPosition,
                        trickplayInfo = state.currentMediaInfo.trickPlayInfo,
                        trickplayUrlFor = viewModel::getTrickplayUrl,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                                .fillMaxWidth(.95f),
                    )
                }
                // NoBars: the overlay stays until OK / Play (jump there) or Back (stay where you were)
            }

            // If D-pad skipping, show the amount skipped in an animation
            if (!controllerViewState.controlsVisible && skipIndicatorDuration != 0L &&
                dpadSeekMode != DpadSeekMode.SEEKBAR_TRICKPLAY
            ) {
                // Skip time mode: show seek distance indicator
                SkipIndicator(
                    durationMs = skipIndicatorDuration,
                    onFinish = {
                        skipIndicatorDuration = 0L
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 70.dp),
                )
                // Show a small progress bar along the bottom of the screen
                if (dpadSeekMode == DpadSeekMode.SEEKBAR_MINIMAL) {
                    val percent = skipPosition.toFloat() / player.duration.toFloat()
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .background(MaterialTheme.colorScheme.border)
                                        .clip(RectangleShape)
                                        .height(3.dp)
                                        .fillMaxWidth(percent),
                            )
                        }
                    }
                }
            }

            val controlsVisible =
                remember(controllerViewState.controlsVisible, playbackDialog, subtitleSearchState) {
                    controllerViewState.controlsVisible ||
                        playbackDialog != null ||
                        subtitleSearchState.status != SubtitleSearchStatus.Inactive
                }
            if (!controlsVisible && skipIndicatorDuration == 0L) {
                PauseIndicator(
                    player = player,
                    modifier =
                        Modifier
                            .align(Alignment.Center),
                )
            }

            // The playback controls

            PlaybackOverlay(
                modifier =
                    Modifier
                        .padding(WindowInsets.systemBars.asPaddingValues())
                        .fillMaxSize()
                        .background(Color.Transparent),
                item = state.currentPlayback?.item,
                player = player,
                controllerViewState = controllerViewState,
                showPlay = playPauseState.showPlay,
                previousEnabled = true,
                nextEnabled = state.hasNext,
                seekEnabled = true,
                seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                onPlaybackActionClick = onPlaybackActionClick,
                onClickPlaybackDialogType = { playbackDialog = it },
                onSeekBarChange = onBarSeek,
                seekBarInteractionSource = seekBarSource,
                showDebugInfo = showDebugInfo,
                currentPlayback = state.currentPlayback,
                chapters = state.currentMediaInfo.chapters,
                trickplayInfo = state.currentMediaInfo.trickPlayInfo,
                trickplayUrlFor = viewModel::getTrickplayUrl,
                queue = remember(state.playlistIndex, state.playlist) { state.upcomingItems },
                onClickPlaylist = viewModel::playItemInPlaylist,
                quickSettings = quickSettings,
                people = people,
                onClickPerson = peopleViewModel::openPerson,
                returnToPersonId = returnToPerson,
                onReturnedToPerson = peopleViewModel::returnedToPerson,
                currentSegment = state.currentSegment?.segment,
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                analyticsState = state.analyticsState,
            )

            var subtitleStyleAppliedForHdr by remember { mutableStateOf(state.currentMediaInfo.videoStream?.hdr == true) }
            val subtitleSettings =
                remember(state.currentMediaInfo) {
                    Timber.v("subtitle choice: ${state.currentMediaInfo.videoStream?.hdr}")
                    val useSeparate =
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences.useSeparateHdr
                    if (useSeparate && state.currentMediaInfo.videoStream?.hdr == true) {
                        preferences.appPreferences.interfacePreferences.hdrSubtitlesPreferences
                    } else {
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences
                    }
                }
            val subtitleImageOpacity =
                remember(subtitleSettings) { subtitleSettings.imageSubtitleOpacity / 100f }

            // Subtitles
            val subtitleMaxSize by animateFloatAsState(if (controllerViewState.controlsVisible) .7f else 1f)
            val isImageSubtitles =
                remember(state.subtitleCues) { state.subtitleCues.firstOrNull()?.bitmap != null }

            // Reset cueCount when density changes to force re-applying subtitle style
            var cueCount by remember(density) { mutableIntStateOf(0) }

            val subtitleVisible =
                skipIndicatorDuration == 0L &&
                    state.currentPlayback?.subtitleIndexEnabled == true &&
                    !presentationState.coverSurface

            AndroidView(
                factory = { context ->
                    SubtitleView(context).apply {
                        subtitleSettings.applyTo(this)
                        playerInstance.assHandler?.let { assHandler ->
                            if (prefs.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS) {
                                Timber.v("Adding AssSubtitleView")
                                addView(
                                    AssSubtitleView(context, assHandler).apply {
                                        layoutParams =
                                            FrameLayout
                                                .LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                ).apply { gravity = Gravity.CENTER }
                                    },
                                )
                            }
                        }
                    }
                },
                update = { subtitleView ->
                    if (subtitleStyleAppliedForHdr != state.currentMediaInfo.videoStream?.hdr) {
                        // If the media has switched between SDR & HDR, reapply subtitle settings
                        Timber.v("Switching SDR/HDR, reapplying subtitle style")
                        subtitleSettings.applyTo(subtitleView)
                    }
                    val cuesNow = shiftedCues ?: state.subtitleCues
                    subtitleView.setCues(cuesNow)
                    if (cuesNow.size > cueCount) {
                        Timber.i("Applying subtitle style to SubtitleView")
                        // The output creates a painter for each cue, so need to apply the changes when the number of cues increases
                        Media3SubtitleOverride(subtitleSettings.calculateEdgeSize(density))
                            .apply(subtitleView)
                        cueCount = cuesNow.size
                    }
                    subtitleView.children.firstOrNull { it is AssSubtitleView }?.let {
                        (it as? AssSubtitleView)?.apply {
                            val resized =
                                layoutParams.let { it.width != playerSurfaceSize.width || it.height != playerSurfaceSize.height }

                            if (resized && playerSurfaceSize.width > 0 && playerSurfaceSize.height > 0) {
                                Timber.v("Resizing AssSubtitleView: %s", playerSurfaceSize)
                                layoutParams =
                                    FrameLayout
                                        .LayoutParams(
                                            playerSurfaceSize.width,
                                            playerSurfaceSize.height,
                                        ).apply { gravity = Gravity.CENTER }
                            }
                        }
                    }
                },
                onReset = {
                    it.setCues(null)
                },
                modifier =
                    Modifier
                        .fillMaxSize(subtitleMaxSize)
                        .align(Alignment.TopCenter)
                        .background(Color.Transparent)
                        .graphicsLayer {
                            alpha =
                                if (!subtitleVisible) {
                                    0f
                                } else if (isImageSubtitles) {
                                    subtitleImageOpacity
                                } else {
                                    1f
                                }
                        },
            )
        }

        // Ask to skip intros, etc button
        AnimatedVisibility(
            showSegment,
            modifier =
                Modifier
                    .padding(40.dp)
                    .align(Alignment.BottomEnd),
        ) {
            state.currentSegment?.let { segment ->
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    focusRequester.tryRequestFocus()
                    delay(10.seconds)
                    viewModel.updateSegment(segment.segment.id, true)
                }
                SkipSegmentButton(
                    type = segment.segment.type,
                    onClick = {
                        viewModel.updateSegment(segment.segment.id, false)
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }

        // Next up episode
        BackHandler(state.nextUp != null) {
            if (player.isPlaying) {
                scope.launch(ExceptionHandler()) {
                    viewModel.cancelUpNextEpisode()
                }
            } else {
                viewModel.navigationManager.goBack()
            }
        }
        AnimatedVisibility(
            state.nextUp != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter),
        ) {
            state.nextUp?.let {
                var autoPlayEnabled by remember { mutableStateOf(viewModel.shouldAutoPlayNextUp()) }
                var timeLeft by remember {
                    mutableLongStateOf(
                        preferences.appPreferences.playbackPreferences.autoPlayNextDelaySeconds,
                    )
                }
                BackHandler(timeLeft > 0 && autoPlayEnabled) {
                    timeLeft = -1
                    autoPlayEnabled = false
                }
                if (autoPlayEnabled) {
                    LaunchedEffect(Unit) {
                        if (timeLeft == 0L) {
                            viewModel.playNextUp()
                        } else {
                            while (timeLeft > 0) {
                                delay(1.seconds)
                                timeLeft--
                            }
                            if (timeLeft == 0L && autoPlayEnabled) {
                                viewModel.playNextUp()
                            }
                        }
                    }
                }
                NextUpEpisode(
                    title =
                        listOfNotNull(
                            it.data.seasonEpisode,
                            it.name,
                        ).joinToString(" - "),
                    description = it.data.overview,
                    imageUrl = LocalImageUrlService.current.rememberImageUrl(it),
                    aspectRatio = it.aspectRatio ?: AspectRatios.WIDE,
                    onClick = {
                        viewModel.reportInteraction()
                        controllerViewState.hideControls()
                        viewModel.playNextUp()
                    },
                    timeLeft = if (autoPlayEnabled) timeLeft.seconds else null,
                    runtime = it.data.runTimeTicks?.ticks,
                    modifier =
                        Modifier
                            .padding(8.dp)
//                                    .height(128.dp)
                            .fillMaxHeight(1 - playerSize)
                            .fillMaxWidth(.66f)
                            .align(Alignment.BottomCenter)
                            .background(
                                MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                shape = RoundedCornerShape(8.dp),
                            ),
                )
            }
        }
    }

    if (subtitleSearchState.status != SubtitleSearchStatus.Inactive) {
        val wasPlaying = remember { player.isPlaying }
        LaunchedEffect(Unit) {
            player.pause()
        }
        val onDismissRequest = {
            if (wasPlaying) {
                player.play()
            }
            viewModel.cancelSubtitleSearch()
        }
        BasicDialog(
            onDismissRequest = onDismissRequest,
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Box(modifier = Modifier.padding(24.dp)) {
                DownloadSubtitlesContent(
                    state = subtitleSearchState.status,
                    language = subtitleSearchState.language,
                    onSearch = { lang ->
                        viewModel.searchForSubtitles(lang)
                    },
                    onClickDownload = {
                        viewModel.downloadAndSwitchSubtitles(it.id, wasPlaying)
                    },
                    modifier =
                        Modifier
                            .widthIn(max = 640.dp)
                            .heightIn(max = 400.dp),
                )
            }
        }
    }

    playbackDialog?.let { type ->
        PlaybackDialog(
            type = type,
            settings =
                PlaybackSettings(
                    showDebugInfo = showDebugInfo,
                    audioIndex = state.currentPlayback?.audioIndex,
                    audioStreams = state.currentMediaInfo.audioStreams,
                    subtitleIndex = state.currentPlayback?.subtitleIndex,
                    subtitleStreams = state.currentMediaInfo.subtitleStreams,
                    playbackSpeed = playbackSpeed,
                    contentScale = contentScale,
                    subtitleDelay = state.currentPlayback?.subtitleDelay ?: Duration.ZERO,
                    hasSubtitleDownloadPermission =
                        remember(userDto) { userDto?.policy?.let { it.isAdministrator || it.enableSubtitleManagement } == true },
                    // TODO Passing through audio prevents changing playback speed
                    // See https://github.com/damontecres/Wholphin/issues/164
                    playbackSpeedEnabled = playbackSpeedEnabled,
                    maxBitrate = state.maxBitrate,
                    zoom = zoom,
                ),
            onDismissRequest = {
                // NoBars: speed and scale now open from the control bar, so closing them goes back to the film
                playbackDialog = null
                if (controllerViewState.controlsVisible) {
                    controllerViewState.pulseControls()
                }
            },
            onControllerInteraction = {
                controllerViewState.pulseControls(Long.MAX_VALUE)
            },
            onClickPlaybackDialogType = {
                if (it == PlaybackDialogType.SUBTITLE_DELAY || it == PlaybackDialogType.ZOOM) {
                    // Hide controls so subtitles are fully visible
                    controllerViewState.hideControls()
                }
                playbackDialog = it
            },
            onPlaybackActionClick = onPlaybackActionClick,
            onChangeSubtitleDelay = { viewModel.updateSubtitleDelay(it) },
            onChangeZoom = onChangeZoom,
            enableSubtitleDelay = player is MpvPlayer || shiftable != null,
            enableVideoScale = player !is MpvPlayer,
        )
    }
    personPanel?.let { panel ->
        PersonPanel(
            state = panel,
            thumbUrl = peopleViewModel::thumbUrl,
            onSelect = peopleViewModel::select,
            onBack = peopleViewModel::back,
            onPlay = { film ->
                peopleViewModel.close()
                navigationManager.goBack()
                navigationManager.navigateTo(Destination.Playback(film))
            },
            onOpenItem = { restartHereAfter(it.destination()) },
            onOpenProfile = { peopleViewModel.openProfile() },
            onToggleBio = peopleViewModel::toggleBio,
        )
    }
}

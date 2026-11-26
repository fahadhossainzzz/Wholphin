package com.github.damontecres.wholphin.services

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.github.damontecres.wholphin.MainActivity
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Handles determining whether the refresh rate and/or resolution of the display need to changed when playing media
 */
@Singleton
class RefreshRateService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Find the best display mode for the given stream and signal to change to it
         */
        suspend fun changeRefreshRate(
            stream: MediaStream,
            switchRefreshRate: Boolean,
            switchResolution: Boolean,
        ) = withContext(WholphinDispatchers.IO) {
            if (!switchRefreshRate && !switchResolution) {
                com.github.damontecres.wholphin.nobars.playback.StreamHealth.rateSwitch = "switching off in Settings"
                Timber.e("NoBars display: refresh-rate switching is off in Settings")
                return@withContext
            }
            val displayManager =
                MainActivity.instance.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val displayModes =
                display.supportedModes
                    .orEmpty()
                    .map { DisplayMode(it) }
                    .sortedWith(
                        compareByDescending<DisplayMode>({ it.physicalWidth * it.physicalHeight })
                            .thenBy { it.refreshRateRounded },
                    )

            val currentDisplayMode = display.mode
            require(stream.type == MediaStreamType.VIDEO) { "Stream is not video" }
            val width = stream.width
            val height = stream.height
            val frameRate =
                // NoBars: the average rate when the file doesn't state the exact one (it used to skip switching)
                if (switchRefreshRate) stream.realFrameRate ?: stream.averageFrameRate else currentDisplayMode.refreshRate
            if (width == null || height == null || frameRate == null) {
                com.github.damontecres.wholphin.nobars.playback.StreamHealth.rateSwitch = "the file doesn't say its frame rate"
                Timber.e("NoBars display: video stream missing required info: width=%s, height=%s, frameRate=%s", width, height, frameRate)
                return@withContext
            }
            Timber.d("Getting refresh rate for: width=%s, height=%s, frameRate=%s", width, height, frameRate)
            val targetMode =
                findDisplayMode(
                    displayModes = displayModes,
                    streamWidth = width,
                    streamHeight = height,
                    targetFrameRate = frameRate,
                    refreshRateSwitch = switchRefreshRate,
                    resolutionSwitch = switchResolution,
                )
            Timber.i("Found display mode: %s, current=%s", targetMode, currentDisplayMode)
            Timber.e(
                "NoBars display: film %s fps, screen %s Hz, TV offers %s; chose %s",
                frameRate,
                currentDisplayMode.refreshRate,
                displayModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" },
                targetMode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" } ?: "none",
            )
            com.github.damontecres.wholphin.nobars.playback.StreamHealth.rateSwitch =
                when {
                    targetMode == null -> "the TV offers no matching rate"
                    targetMode.modeId == currentDisplayMode.modeId -> "already matching"
                    else -> "switched to ${com.github.damontecres.wholphin.nobars.playback.StreamHealth.hzText(targetMode.refreshRate)} Hz"
                }
            if (targetMode != null && targetMode.modeId != currentDisplayMode.modeId) {
                val listener = Listener(display.displayId)
                displayManager.registerDisplayListener(
                    listener,
                    Handler(Looper.myLooper() ?: Looper.getMainLooper()),
                )
                try {
                    MainActivity.instance.changeDisplayMode(targetMode.modeId)
                    val result =
                        withTimeoutOrNull(5.seconds) {
                            listener.deferred.await()
                        }
                    if (result == null) {
                        Timber.w("Timed out waiting for display change")
                        showToast(context, "Refresh rate switch is taking a long time")
                    }
                } catch (ex: Exception) {
                    Timber.w(ex, "Exception waiting for refresh rate switch")
                } finally {
                    displayManager.unregisterDisplayListener(listener)
                }
                // NoBars: did the TV really change?
                val now = display.mode
                if (now.modeId != targetMode.modeId) {
                    com.github.damontecres.wholphin.nobars.playback.StreamHealth.rateSwitch =
                        "asked for ${com.github.damontecres.wholphin.nobars.playback.StreamHealth.hzText(targetMode.refreshRate)} Hz, the TV stayed at ${com.github.damontecres.wholphin.nobars.playback.StreamHealth.hzText(now.refreshRate)} Hz"
                }
                Timber.e("NoBars display: asked for mode %s, the screen is now %s@%s", targetMode.modeId, now.modeId, now.refreshRate)
                val targetRate = (targetMode.refreshRate * 1000).roundToInt()
                val isSeamless =
                    targetRate == (currentDisplayMode.refreshRate * 1000).roundToInt() ||
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            currentDisplayMode.alternativeRefreshRates
                                .map { (it * 1000).roundToInt() }
                                .any { it % targetRate == 0 }
                        } else {
                            false
                        }
                if (!isSeamless) {
                    Timber.v("Waiting for non-seamless switch")
                    // Wait the recommended 2 seconds (https://developer.android.com/media/optimize/performance/frame-rate)
                    delay(2.seconds)
                }
            }
        }

        /**
         * Reset the display mode to the original
         */
        fun resetRefreshRate() {
            MainActivity.instance.changeDisplayMode(0)
        }

        /**
         * Listens for the display to change so we known the refresh rate or resolution is updated
         */
        private class Listener(
            val displayId: Int,
        ) : DisplayManager.DisplayListener {
            val deferred = CompletableDeferred<Unit>()

            override fun onDisplayAdded(displayId: Int) {
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == this.displayId) {
                    Timber.v("Got display change for $displayId")
                    deferred.complete(Unit)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
            }
        }

        companion object {
            /**
             * Find the best display mode for the given stream & preferences
             *
             * @param displayModes candidates that are sorted by resolution and frame rate descending
             */
            fun findDisplayMode(
                displayModes: List<DisplayMode>,
                streamWidth: Int,
                streamHeight: Int,
                targetFrameRate: Float,
                refreshRateSwitch: Boolean,
                resolutionSwitch: Boolean,
            ): DisplayMode? {
                val streamRate = targetFrameRate.times(1000).roundToInt()
//                Timber.v("display modes: %s", displayModes.joinToString("\n"))
                val candidates =
                    if (refreshRateSwitch) {
                        displayModes
                            .filterNot { it.physicalHeight < streamHeight || it.physicalWidth < streamWidth }
                            .filter { frameRateMatches(it.refreshRateRounded, streamRate) }
                    } else {
                        displayModes
                            .filterNot { it.physicalHeight < streamHeight || it.physicalWidth < streamWidth }
                    }
//                Timber.v("display modes candidates: %s", candidates.joinToString("\n"))
                return if (!resolutionSwitch) {
                    // NoBars: keep the highest resolution, and at it the best rate: exactly the film's, then a near
                    // one (24 Hz for 23.976 fps), then a whole multiple (48, 120 Hz)
                    candidates.minWithOrNull(
                        compareByDescending<DisplayMode> { it.physicalWidth * it.physicalHeight }
                            .thenBy { if (refreshRateSwitch) matchRank(it.refreshRateRounded, streamRate) ?: 99 else 0 },
                    )
                } else {
                    // Exact resolution & frame rate
                    candidates.firstOrNull {
                        it.physicalWidth == streamWidth &&
                            it.physicalHeight == streamHeight &&
                            it.refreshRateRounded == streamRate
                    }
                        // Next highest resolution, exact frame rate
                        ?: candidates.lastOrNull {
                            it.physicalWidth >= streamWidth &&
                                it.physicalHeight >= streamHeight &&
                                it.refreshRateRounded == streamRate
                        }
                        // Exact resolution, acceptable frame rate
                        ?: candidates.lastOrNull {
                            it.physicalWidth == streamWidth &&
                                it.physicalHeight == streamHeight &&
                                frameRateMatches(it.refreshRateRounded, streamRate)
                        }
                        // Next highest resolution, acceptable frame rate
                        ?: candidates.lastOrNull {
                            it.physicalWidth >= streamWidth &&
                                it.physicalHeight >= streamHeight &&
                                frameRateMatches(it.refreshRateRounded, streamRate)
                        }
                        // Fall back to largest resolution, exact frame rate
                        ?: candidates
                            .filter { it.refreshRateRounded == streamRate }
                            .maxByOrNull { it.physicalWidth * it.physicalHeight }
                        // Finally, just the highest resolution
                        ?: candidates.maxByOrNull { it.physicalWidth * it.physicalHeight }
                }
            }

            fun frameRateMatches(
                refreshRateRounded: Int,
                streamRate: Int,
            ): Boolean {
                // NoBars: only rates that show every frame for the same time. 60 Hz for a 24 fps film (2.5×) is the
                // uneven 3-2 pattern this is meant to avoid, so it no longer counts
                return matchRank(refreshRateRounded, streamRate) != null
            }

            /**
             * NoBars: how well a screen rate suits a film (both in thousandths of Hz/fps), lower is better; null when it
             * doesn't suit. 0 exact (23.976 for 23.976), 2-5 an exact whole multiple (47.952, 119.88), 11 near (24 for
             * 23.976: one repeated frame about every 40 s), 12-15 a near multiple (48, 120).
             */
            fun matchRank(
                refreshRateRounded: Int,
                streamRate: Int,
            ): Int? {
                if (streamRate <= 0 || refreshRateRounded <= 0) return null
                val k = Math.round(refreshRateRounded.toDouble() / streamRate).toInt()
                if (k < 1) return null
                val diff = kotlin.math.abs(refreshRateRounded - k.toLong() * streamRate)
                return when {
                    diff <= 5L * k -> if (k == 1) 0 else k
                    diff <= 0.0015 * k * streamRate -> 10 + k
                    else -> null
                }
            }
        }
    }

/**
 * Wrapper for a [Display.Mode]
 */
data class DisplayMode(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
) {
    val refreshRateRounded: Int = (refreshRate * 1000).roundToInt()

    constructor(mode: Display.Mode) : this(
        mode.modeId,
        mode.physicalWidth,
        mode.physicalHeight,
        mode.refreshRate,
    )
}

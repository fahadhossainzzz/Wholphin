@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import timber.log.Timber

/**
 * NoBars: a read-ahead buffer measured in seconds that gives way to the rest of the app.
 *
 * - Aims for [GOAL_MS] of video ahead.
 * - Below [FLOOR_MS] it always loads, so playback can never jam itself.
 * - Between the two it loads only while the app's memory is below [STOP_RATIO] of its real limit,
 *   and resumes once it is back under [RESUME_RATIO]. A high-bitrate film simply ends up with fewer
 *   seconds ahead; no file size can fill memory through the buffer.
 * - When it stops, spare buffer memory is handed back instead of being kept for reuse.
 */
class ElasticLoadControl private constructor(
    private val allocator: DefaultAllocator,
    private val delegate: DefaultLoadControl,
) : LoadControl {
    @Volatile private var loading = true

    private var lastHoldLog = 0L

    /** The current in-memory goal; the read-ahead waits for the player to reach it. */
    @Volatile var goal: Long = GOAL_MS
        private set

    /** Set by the stall guard when the player is starving: load regardless of memory, up to the floor x2. */
    @Volatile var emergencyUntil: Long = 0

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val bufferedMs = parameters.bufferedDurationUs / 1000
        val ratio = StreamHealth.heapRatio()
        // With the USB drive holding the next minutes, memory only needs a short buffer
        // The whole TV's free RAM counts too: Android kills the app outright when the system runs low
        val systemFreeMb = StreamHealth.systemSpareMb
        // Fed from the USB drive, memory only needs a few seconds: the drive itself is the buffer
        val driveFed = StreamHealth.driveFed
        val goalMs = if (driveFed) DRIVE_GOAL_MS else GOAL_MS
        val floorMs = if (driveFed) DRIVE_FLOOR_MS else FLOOR_MS
        goal = goalMs
        val decision =
            when {
                bufferedMs >= goalMs -> false

                System.currentTimeMillis() < emergencyUntil -> bufferedMs < floorMs * 2

                // Never starve: keep loading below the floor unless memory is truly at the edge
                bufferedMs < floorMs -> (ratio < 0.95f && systemFreeMb > 50) || bufferedMs < 3_000

                loading -> ratio < STOP_RATIO && systemFreeMb > SYSTEM_STOP_MB

                else -> ratio < RESUME_RATIO && systemFreeMb > SYSTEM_RESUME_MB
            }
        if (loading && !decision) {
            // Hand spare buffer memory back
            allocator.setTargetBufferSize(allocator.totalBytesAllocated)
            val now = System.currentTimeMillis()
            if (bufferedMs < goalMs && now - lastHoldLog >= 30_000) {
                lastHoldLog = now
                Timber.e(
                    "NoBars buffer: holding at %ds ahead, app memory %d%%, TV spare RAM %d MB",
                    bufferedMs / 1000,
                    (ratio * 100).toInt(),
                    systemFreeMb,
                )
            }
        } else if (!loading && decision) {
            allocator.setTargetBufferSize(Int.MAX_VALUE)
        }
        loading = decision
        StreamHealth.bufferState =
            when {
                decision -> "loading"
                bufferedMs >= goalMs -> "full (${goalMs / 1000} s goal)"
                else -> "holding: memory"
            }
        return decision
    }

    companion object {
        const val GOAL_MS = 120_000L
        const val DRIVE_GOAL_MS = 20_000L
        const val DRIVE_FLOOR_MS = 8_000L
        const val FLOOR_MS = 15_000L
        const val STOP_RATIO = 0.60f
        const val RESUME_RATIO = 0.50f

        // Spare TV RAM above Android's low-memory line
        const val SYSTEM_STOP_MB = 200L
        const val SYSTEM_RESUME_MB = 300L

        @Volatile
        var current: ElasticLoadControl? = null
            private set

        fun create(): ElasticLoadControl {
            val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            val delegate =
                DefaultLoadControl
                    .Builder()
                    .setAllocator(allocator)
                    // Only the start thresholds of these are used: 2.5 s to start, 5 s after a stall
                    .setBufferDurationsMs(FLOOR_MS.toInt(), GOAL_MS.toInt(), 2_500, 5_000)
                    .setTargetBufferBytes(Int.MAX_VALUE)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            return ElasticLoadControl(allocator, delegate).also { current = it }
        }
    }
}

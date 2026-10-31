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
        val decision =
            when {
                bufferedMs >= GOAL_MS -> false

                System.currentTimeMillis() < emergencyUntil -> bufferedMs < FLOOR_MS * 2

                // Never starve: keep loading below the floor unless memory is truly at the edge
                bufferedMs < FLOOR_MS -> ratio < 0.95f || bufferedMs < 3_000

                loading -> ratio < STOP_RATIO

                else -> ratio < RESUME_RATIO
            }
        if (loading && !decision) {
            // Hand spare buffer memory back
            allocator.setTargetBufferSize(allocator.totalBytesAllocated)
            if (bufferedMs < GOAL_MS) {
                Timber.i("NoBars buffer: holding at %ds ahead, memory %d%%", bufferedMs / 1000, (ratio * 100).toInt())
            }
        } else if (!loading && decision) {
            allocator.setTargetBufferSize(Int.MAX_VALUE)
        }
        loading = decision
        return decision
    }

    companion object {
        const val GOAL_MS = 120_000L
        const val FLOOR_MS = 15_000L
        const val STOP_RATIO = 0.75f
        const val RESUME_RATIO = 0.65f

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

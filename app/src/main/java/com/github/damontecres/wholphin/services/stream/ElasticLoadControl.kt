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
 * NoBars: the read-ahead buffer, with a goal in seconds and a budget in megabytes.
 *
 * - Aims for [GOAL_MS] of video ahead, and stops earlier when its own size (counted exactly by the
 *   allocator, not guessed from the app's memory) reaches the budget: at most [MAX_BUDGET_BYTES], half
 *   the app's limit, and always leaving [SYSTEM_KEEP_MB] of the TV's RAM spare.
 * - Once full it tops up again as soon as it drops under [RESUME_SHARE] of the budget, so it stays
 *   near full instead of draining down to the floor.
 * - Below [FLOOR_MS] it always loads, so playback can never jam itself.
 * - Emergency brakes: the app above [HEAP_BRAKE] of its limit, or the TV under [SYSTEM_BRAKE_MB] spare.
 * - When it stops, spare buffer memory is handed back instead of being kept for reuse.
 */
class ElasticLoadControl private constructor(
    private val allocator: DefaultAllocator,
    private val delegate: DefaultLoadControl,
) : LoadControl {
    @Volatile private var loading = true

    private var lastHoldLog = 0L

    private var refillStart = 0L

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
        val bytes = allocator.totalBytesAllocated.toLong()
        val heap = StreamHealth.heapRatio()
        val spareMb = StreamHealth.systemSpareMb
        val budget = budgetFor(bytes, spareMb)
        StreamHealth.bufferBytes = bytes
        StreamHealth.bufferBudget = budget
        val emergencyLoad = System.currentTimeMillis() < emergencyUntil
        val (decision, state) =
            when {
                // Never starve: below the floor it loads unless memory is truly at the edge
                bufferedMs < FLOOR_MS -> {
                    ((heap < 0.95f && spareMb > 50) || bufferedMs < 3_000) to "loading (under ${FLOOR_MS / 1000} s)"
                }

                emergencyLoad -> {
                    (bufferedMs < FLOOR_MS * 2) to "loading (rescue)"
                }

                // Emergency brakes: the app or the whole TV is short of memory
                heap >= HEAP_BRAKE || spareMb < SYSTEM_BRAKE_MB -> {
                    false to "holding: low memory"
                }

                bufferedMs >= GOAL_MS -> {
                    false to "full (${GOAL_MS / 1000} s goal)"
                }

                loading -> {
                    (bytes < budget).let { it to if (it) "loading" else "full (${budget shr 20} MB budget)" }
                }

                // Top up early, so the buffer stays near full instead of draining to the floor
                else -> {
                    val resume = bytes < budget * RESUME_SHARE && bufferedMs < GOAL_MS - 10_000
                    resume to if (resume) "loading" else StreamHealth.bufferState
                }
            }
        if (loading && !decision) {
            if (refillStart > 0 && state.startsWith("full")) StreamHealth.lastRefillMs = System.currentTimeMillis() - refillStart
            refillStart = 0
            // Hand spare buffer memory back
            allocator.setTargetBufferSize(allocator.totalBytesAllocated)
            val now = System.currentTimeMillis()
            if (state.startsWith("holding") && now - lastHoldLog >= 30_000) {
                lastHoldLog = now
                Timber.e(
                    "NoBars buffer: holding at %ds ahead (%d MB), app memory %d%%, TV spare RAM %d MB",
                    bufferedMs / 1000,
                    bytes shr 20,
                    (heap * 100).toInt(),
                    spareMb,
                )
            }
        } else if (!loading && decision) {
            refillStart = System.currentTimeMillis()
            allocator.setTargetBufferSize(Int.MAX_VALUE)
        }
        loading = decision
        StreamHealth.bufferState = state
        return decision
    }

    /**
     * How many bytes the buffer may hold now: at most [MAX_BUDGET_BYTES] or half the app's limit, and
     * always leaving [SYSTEM_KEEP_MB] of the TV's RAM spare.
     */
    private fun budgetFor(
        bytes: Long,
        spareMb: Long,
    ): Long {
        val bySystem = if (spareMb == Long.MAX_VALUE) Long.MAX_VALUE else bytes + ((spareMb - SYSTEM_KEEP_MB) shl 20)
        return minOf(MAX_BUDGET_BYTES, StreamHealth.heapMax() / 2, bySystem).coerceAtLeast(MIN_BUDGET_BYTES)
    }

    companion object {
        const val GOAL_MS = 120_000L
        const val FLOOR_MS = 15_000L
        const val MAX_BUDGET_BYTES = 250L * 1024 * 1024
        const val MIN_BUDGET_BYTES = 48L * 1024 * 1024
        const val RESUME_SHARE = 0.85
        const val HEAP_BRAKE = 0.85f
        const val SYSTEM_BRAKE_MB = 150L
        const val SYSTEM_KEEP_MB = 200L

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

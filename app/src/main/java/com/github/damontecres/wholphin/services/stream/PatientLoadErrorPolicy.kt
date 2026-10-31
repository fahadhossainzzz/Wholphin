@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * NoBars: network hiccups are retried immediately and without limit instead of surfacing as a
 * playback error (which in Wholphin means falling back to server transcoding). The stall guard
 * decides when a stream is really stuck. Real errors still fail as before: HTTP 4xx (not allowed,
 * not found) and everything the default policy treats as fatal (broken file, missing file).
 */
class PatientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val code = responseCode(loadErrorInfo.exception)
        if (code != null && code < 500) return C.TIME_UNSET
        val default = super.getRetryDelayMsFor(loadErrorInfo)
        if (default == C.TIME_UNSET) return C.TIME_UNSET
        return when {
            loadErrorInfo.errorCount <= 2 -> 0L
            loadErrorInfo.errorCount <= 6 -> 500L
            else -> 2_000L
        }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    private fun responseCode(e: Throwable): Int? {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }
}

@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.io.IOException

/**
 * NoBars: many MKV files end with a few stray bytes after the last real element (zeros left by the
 * tool that made them). FFmpeg ignores them; the player's MKV reader stops with "No valid varint
 * length mask found", which used to end playback a minute or two before the credits, because the
 * read-ahead reaches the end of the file that early.
 *
 * A read error in the last [TAIL_BYTES] of a file is treated as the end of the file: everything
 * before it has already been read, so the film plays to the end. Errors anywhere else, and network
 * errors, are passed on unchanged.
 */
class EndTolerantExtractor(
    private val inner: Extractor,
) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) = inner.init(output)

    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int =
        try {
            inner.read(input, seekPosition)
        } catch (e: Exception) {
            if (!isFileContentError(e)) throw e
            val length = input.length
            val left = if (length == C.LENGTH_UNSET.toLong()) -1L else length - input.position
            if (left !in 0..TAIL_BYTES) throw e
            StreamHealth.event("stray bytes at the end of the file ($left bytes left, ${e.javaClass.simpleName}: ${e.message}), treated as the end")
            Extractor.RESULT_END_OF_INPUT
        }

    override fun seek(
        position: Long,
        timeUs: Long,
    ) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

    private fun isFileContentError(e: Exception): Boolean =
        when (e) {
            is ParserException -> true
            is IOException -> false // network: retried as usual
            is RuntimeException -> true // IllegalStateException and similar from the parser
            else -> false
        }

    companion object {
        const val TAIL_BYTES = 64L * 1024
    }
}

@file:OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])

package com.github.damontecres.wholphin.services.stream

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.mp4.LeanMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * NoBars: the default extractors, except that MP4 files are read by [LeanMp4Extractor], which skips
 * tracks in unused languages (see [LanguageKeep]). Settings the player passes in are remembered so
 * the replacement is configured exactly like the Mp4Extractor it stands in for.
 */
class LeanExtractorsFactory(
    delegate: ExtractorsFactory,
) : ForwardingExtractorsFactory(delegate) {
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscodingEnabled = true
    private var codecsToParseWithinGop = C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
    private var parseHagcMetadata = true

    @Deprecated("Deprecated in media3")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        super.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        super.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        this.codecsToParseWithinGop = codecsToParseWithinGopSampleDependencies
        super.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        this.parseHagcMetadata = parseHagcMetadata
        super.setParseHagcMetadata(parseHagcMetadata)
        return this
    }

    override fun createExtractors(): Array<Extractor> = replace(super.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = replace(super.createExtractors(uri, responseHeaders))

    private fun replace(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            if (extractors[i] is Mp4Extractor) {
                LeanMp4Extractor(
                    subtitleParserFactory,
                    LeanMp4Extractor.codecsToParseWithinGopSampleDependenciesAsFlags(codecsToParseWithinGop) or
                        (if (textTrackTranscodingEnabled) 0 else LeanMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
                        (if (parseHagcMetadata) 0 else LeanMp4Extractor.FLAG_DISABLE_HAGC_METADATA),
                )
            } else {
                extractors[i]
            }
        }
}

@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * NoBars: never send DTS or TrueHD to the TV as-is.
 *
 * The Hisense 85Q6N reports that it accepts DTS for pass-through, then refuses to open the audio
 * output for it ("AudioTrack init failed ... audio/vnd.dts.hd"); Wholphin then fell back to having
 * the server convert the whole film, video included. Reporting these formats as not accepted makes
 * the player decode them itself (the TV's own decoder if it has one, else the built-in FFmpeg
 * decoder) and send ordinary multichannel sound, which the TV mixes for its speakers. Dolby formats
 * are untouched, so the TV's own Dolby processing keeps working.
 */
object NoDtsPassthrough {
    private val BLOCKED =
        setOf(
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS,
            MimeTypes.AUDIO_DTS_UHD_P2,
            MimeTypes.AUDIO_TRUEHD,
        )

    fun audioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enablePlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enablePlaybackParams)
            .setAudioOutputProvider(Provider(AudioTrackAudioOutputProvider.Builder(context).build()))
            .build()

    private class Provider(
        delegate: AudioOutputProvider,
    ) : ForwardingAudioOutputProvider(delegate) {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
            if (formatConfig.format.sampleMimeType in BLOCKED) {
                AudioOutputProvider.FormatSupport.UNSUPPORTED
            } else {
                super.getFormatSupport(formatConfig)
            }
    }
}

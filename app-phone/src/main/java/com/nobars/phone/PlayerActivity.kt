@file:OptIn(UnstableApi::class)

package com.nobars.phone

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.github.damontecres.wholphin.nobars.playback.DelayedTextRenderer
import com.github.damontecres.wholphin.nobars.playback.ElasticLoadControl
import com.github.damontecres.wholphin.nobars.playback.LeanExtractorsFactory
import com.github.damontecres.wholphin.nobars.playback.NoBarsLog
import com.github.damontecres.wholphin.nobars.playback.NoDtsPassthrough
import com.github.damontecres.wholphin.nobars.playback.PatientLoadErrorPolicy
import com.github.damontecres.wholphin.nobars.playback.PlaybackInfo
import com.github.damontecres.wholphin.nobars.playback.StallGuard
import com.github.damontecres.wholphin.nobars.playback.StreamHealth
import com.github.damontecres.wholphin.nobars.playback.SubtitleOffset
import com.nobars.jellyfin.NbJellyfin
import com.nobars.jellyfin.Playback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** Full-screen player. Plays the file as it is (direct play) with the TV app's playback engine. */
class PlayerActivity : ComponentActivity() {
    private val nb get() = NbJellyfin.current
    private var player: ExoPlayer? = null
    private var prepared: Playback.Prepared? = null
    private var guard: Job? = null
    private var started = false

    private var loading by mutableStateOf(true)
    private var message by mutableStateOf<String?>(null)
    private var controls by mutableStateOf(true)
    private var hint by mutableStateOf<String?>(null)
    private var delayMs by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        SubtitleOffset.delayUs = 0L
        val id = intent.getStringExtra(EXTRA_ID)?.let(UUID::fromString)
        val fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        val exo = buildPlayer(this).also { player = it }

        setContent {
            NbTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx -> playerView(ctx, exo) }, modifier = Modifier.fillMaxSize())
                    if (loading && message == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    hint?.let {
                        Text(
                            it,
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(12.dp),
                        )
                    }
                    if (controls && message == null) {
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x88000000)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { shiftSubs(-500) }) { Text("−0.5 s", color = Color.White) }
                            Text("Subtitles " + subsLabel(), color = Color.White, fontSize = 13.sp)
                            TextButton(onClick = { shiftSubs(500) }) { Text("+0.5 s", color = Color.White) }
                        }
                    }
                    message?.let {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xEE16161C)).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(it, color = Color.White, textAlign = TextAlign.Center)
                            Button(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
        }

        if (id == null) {
            message = "Nothing to play."
            return
        }
        lifecycleScope.launch {
            val p =
                try {
                    nb.playback.prepare(id, fromStart)
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: prepare failed")
                    message = "Couldn't start this title: ${e.message ?: e.javaClass.simpleName}"
                    return@launch
                }
            prepared = p
            val item =
                MediaItem
                    .Builder()
                    .setUri(p.url)
                    .setMediaId(p.item.id.toString())
                    .setSubtitleConfigurations(
                        p.subtitles.map { s ->
                            MediaItem.SubtitleConfiguration
                                .Builder(Uri.parse(s.url))
                                .setMimeType(s.mimeType)
                                .setLanguage(s.language)
                                .setLabel(s.label)
                                .setId("ext${s.index}")
                                .build()
                        },
                    ).build()
            exo.setMediaItem(item, p.startMs)
            exo.prepare()
            exo.playWhenReady = true
            guard =
                launch {
                    StallGuard(
                        context = this@PlayerActivity,
                        player = exo,
                        isDirectPlay = { true },
                        describe = { describe(p) },
                        onReport = { report -> sendReport(report) },
                        playback = { PhonePlayback(p.source, delayMs) },
                        appVersion = "phone ${BuildConfig.VERSION_NAME}",
                        clearImages = { coil3.SingletonImageLoader.get(this@PlayerActivity).memoryCache?.clear() },
                    ).run()
                }
            // progress to Jellyfin every 10 s, so Continue Watching is right on the TVs too
            while (isActive) {
                delay(10_000)
                if (started) nb.playback.reportProgress(p, exo.currentPosition, !exo.isPlaying)
            }
        }
    }

    private fun subsLabel(): String =
        when {
            delayMs == 0L -> "on time"
            delayMs > 0 -> "+" + String.format(Locale.US, "%.1f", delayMs / 1000f) + " s later"
            else -> String.format(Locale.US, "%.1f", -delayMs / 1000f) + " s earlier"
        }

    private fun shiftSubs(ms: Long) {
        delayMs += ms
        SubtitleOffset.delayUs = delayMs * 1000
    }

    private fun flash(text: String) {
        hint = text
        lifecycleScope.launch {
            delay(700)
            if (hint == text) hint = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun playerView(
        ctx: Context,
        exo: ExoPlayer,
    ): PlayerView {
        val view = PlayerView(ctx)
        view.player = exo
        view.setShowSubtitleButton(true)
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.controllerShowTimeoutMs = 3_500
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
        // double-tap the left or right half: back or forward 10 s
        val taps =
            GestureDetector(
                ctx,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val forward = e.x > view.width / 2f
                        val target = (exo.currentPosition + if (forward) 10_000 else -10_000).coerceIn(0, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                        exo.seekTo(target)
                        flash(if (forward) "+10 s" else "−10 s")
                        return true
                    }
                },
            )
        view.setOnTouchListener { _, ev ->
            taps.onTouchEvent(ev)
            false
        }
        return view
    }

    private fun buildPlayer(ctx: Context): ExoPlayer {
        val http =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        val upstream =
            OkHttpDataSource
                .Factory(StreamHealth.trackingCallFactory(http))
                .setDefaultRequestProperties(mapOf(nb.playback.authHeader()))
        val dataSource = StreamHealth.countingFactory(DefaultDataSource.Factory(ctx, upstream))
        val sources =
            DefaultMediaSourceFactory(dataSource, LeanExtractorsFactory(DefaultExtractorsFactory()))
                .setLoadErrorHandlingPolicy(PatientLoadErrorPolicy())
        val renderers =
            PhoneRenderers(ctx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        val exo =
            ExoPlayer
                .Builder(ctx, renderers)
                .setMediaSourceFactory(sources)
                .setLoadControl(ElasticLoadControl.create())
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build()
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguages(Locale.getDefault().language, "en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    val p = prepared ?: return
                    if (state == Player.STATE_READY) {
                        loading = false
                        if (!started) {
                            started = true
                            lifecycleScope.launch { nb.playback.reportStart(p, exo.currentPosition) }
                        }
                    }
                    if (state == Player.STATE_BUFFERING) loading = true
                    if (state == Player.STATE_ENDED) finish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.e(error, "NoBars phone: playback error %s", error.errorCodeName)
                    loading = false
                    message =
                        when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            -> "This phone can't play this file as it is (${formatOf(prepared?.source)}). " +
                                "This version only plays files directly, without converting them."
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            -> "Lost the connection to the server. Is the phone on the home Wi-Fi?"
                            else -> "Playback stopped: ${error.errorCodeName}"
                        }
                }
            },
        )
        return exo
    }

    private fun formatOf(source: MediaSourceInfo?): String {
        val streams = source?.mediaStreams.orEmpty()
        val v = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val a = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault } ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
        return listOfNotNull(source?.container, v?.codec?.uppercase(Locale.US), v?.videoRangeType?.name, a?.codec?.uppercase(Locale.US)).joinToString(" · ")
    }

    private fun describe(p: Playback.Prepared): String {
        val i = p.item
        val name =
            if (i.seriesName != null) {
                "${i.seriesName} S${i.parentIndexNumber ?: "?"}E${i.indexNumber ?: "?"} ${i.name.orEmpty()}"
            } else {
                "${i.name.orEmpty()} (${i.productionYear ?: "?"})"
            }
        val mb = (p.source.size ?: 0L) / 1_000_000
        return "$name | ${formatOf(p.source)} | $mb MB | phone ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}"
    }

    private fun sendReport(report: String) {
        NoBarsLog.writeBlock(report)
        val api = nb.api
        val header =
            "NoBars playback report\nclientInfo=${api.clientInfo}\ndeviceInfo=DeviceInfo(id=${api.deviceInfo.id}, name=${api.deviceInfo.name})\n\n"
        PhoneApp.scope.launch { nb.uploadLog(header + report) }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        val p = prepared
        val exo = player
        if (p != null && exo != null && started) {
            val pos = exo.currentPosition
            PhoneApp.scope.launch { nb.playback.reportProgress(p, pos, true) }
        }
    }

    override fun onDestroy() {
        guard?.cancel()
        val p = prepared
        val exo = player
        if (p != null && exo != null && started) {
            val pos = if (exo.playbackState == Player.STATE_ENDED) exo.duration else exo.currentPosition
            PhoneApp.scope.launch { nb.playback.reportStop(p, pos) }
        }
        player?.release()
        player = null
        SubtitleOffset.delayUs = 0L
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "itemId"
        const val EXTRA_FROM_START = "fromStart"
    }
}

/** What the playback engine reads about the current film (see core-player's PlaybackInfo). */
private class PhonePlayback(
    override val mediaSourceInfo: MediaSourceInfo,
    delayMs: Long,
) : PlaybackInfo {
    override val audioIndex: Int = mediaSourceInfo.defaultAudioStreamIndex ?: -1
    override val subtitleIndex: Int = -1
    override val subtitleDelay: Duration = (delayMs * 1000).microseconds
    override val videoDecoder: String? = null
    override val subtitleIndexEnabled: Boolean = false
}

/** The TV app's renderer changes that matter on a phone: subtitle delay, and DTS/TrueHD decoded in the app. */
private class PhoneRenderers(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.forEach { out.add(DelayedTextRenderer(it)) }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = NoDtsPassthrough.audioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
}

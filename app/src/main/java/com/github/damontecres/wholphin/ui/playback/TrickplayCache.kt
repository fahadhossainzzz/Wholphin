package com.github.damontecres.wholphin.ui.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.github.damontecres.wholphin.services.ServerAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.TrickplayInfo
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs

/** One seek-preview picture: [image] (a single picture, or a whole sheet) and the part of it to show. */
class SeekPicture(
    val image: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
)

/**
 * NoBars: the seek previews of the film being played, done the way Netflix/Roku do it: all of the film's preview
 * sheets (Jellyfin's trickplay images, ~3 MB a film) are downloaded the moment the film starts, then two layers:
 *
 * - soft: every sheet is opened once, at half size, in the background (~1 s, ~15 MB for 2 h); from then on every
 *   moment of the film has a picture instantly, however fast the button is held;
 * - sharp: full-size single pictures cut out on demand, the one under the frame first. Like ExoPlayer's scrubbing
 *   mode, a picture being cut is always finished and only the newest request waits (nothing in between is done),
 *   so holding the button never starves it. When the moving stops, the neighbours and then 30 ahead / 10 behind
 *   are sharpened.
 *
 * [picture] gives the sharp picture, else the soft one, else the nearest one there is: never an empty box (except
 * in the first second of a film). [version] changes whenever new pictures are ready, so the filmstrip redraws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TrickplayCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sharpDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val softDispatcher =
        Executors
            .newSingleThreadExecutor { r -> Thread(r, "nobars-previews").apply { priority = Thread.MIN_PRIORITY } }
            .asCoroutineDispatcher()

    @Volatile private var key: String? = null

    @Volatile private var info: TrickplayInfo? = null

    @Volatile private var sheets: Array<ByteArray?> = emptyArray()

    @Volatile private var soft: AtomicReferenceArray<ImageBitmap?> = AtomicReferenceArray(0)

    @Volatile private var softScale = 2
    private val decoders = ConcurrentHashMap<Int, BitmapRegionDecoder>()
    private val tiles =
        object : LruCache<Int, ImageBitmap>(6 * 1024 * 1024) {
            override fun sizeOf(
                key: Int,
                value: ImageBitmap,
            ): Int = value.width * value.height * 2
        }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var loadJob: Job? = null
    private var sharpJob: Job? = null
    private val pending = AtomicReference<Request?>(null)
    private var wake = Channel<Unit>(Channel.CONFLATED)

    private data class Request(
        val timeMs: Long,
        val stepMs: Long,
        val forward: Boolean,
        val durationMs: Long,
    )

    /** Start of a film: fetch all its preview sheets (in parallel, in the background), then build the soft layer. */
    fun load(
        itemKey: String,
        trickplay: TrickplayInfo,
        durationMs: Long,
        urlFor: (Int) -> String?,
    ) {
        val newKey = "$itemKey/${trickplay.width}"
        if (newKey == key) return
        clear()
        key = newKey
        info = trickplay
        val perSheet = trickplay.tileWidth * trickplay.tileHeight
        val count = ((durationMs / trickplay.interval).toInt() / perSheet + 1).coerceIn(1, 60)
        sheets = arrayOfNulls(count)
        soft = AtomicReferenceArray(count)
        // half size; a very long film at quarter size, so this layer stays under ~40 MB
        val halfBytes = count.toLong() * (trickplay.width * trickplay.tileWidth / 2) * (trickplay.height * trickplay.tileHeight / 2) * 2
        softScale = if (halfBytes > 40L * 1024 * 1024) 4 else 2
        val token = ServerAccess.token
        val myWake = Channel<Unit>(Channel.CONFLATED)
        wake = myWake
        sharpJob = scope.launch(sharpDispatcher) { sharpWorker(newKey, myWake) }
        loadJob =
            scope.launch {
                val started = System.currentTimeMillis()
                val total =
                    (0 until count)
                        .map { i ->
                            async {
                                val url = urlFor(i) ?: return@async 0
                                try {
                                    val c = URL(url).openConnection() as HttpURLConnection
                                    c.connectTimeout = 8_000
                                    c.readTimeout = 15_000
                                    if (token != null) c.setRequestProperty("Authorization", "MediaBrowser Token=\"$token\"")
                                    try {
                                        if (c.responseCode == 200) {
                                            val bytes = c.inputStream.use { it.readBytes() }
                                            if (key == newKey && i < sheets.size) sheets[i] = bytes
                                            bytes.size
                                        } else {
                                            0
                                        }
                                    } finally {
                                        c.disconnect()
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "NoBars: preview sheet %s not loaded", i)
                                    0
                                }
                            }
                        }.awaitAll()
                        .sum()
                if (key != newKey) return@launch
                Timber.i("NoBars: seek previews loaded: %s sheets, %s KB in %s ms", count, total / 1024, System.currentTimeMillis() - started)
                _version.value++
                lastRequest?.let { pending.compareAndSet(null, it) } // sharpen what is on screen now
                myWake.trySend(Unit)
                withContext(softDispatcher) { buildSoft(newKey, trickplay, perSheet) }
            }
    }

    /** Open every sheet once at reduced size, starting with the one being watched, then outwards. */
    private fun buildSoft(
        forKey: String,
        i: TrickplayInfo,
        perSheet: Int,
    ) {
        val started = System.currentTimeMillis()
        val here = ((pending.get()?.timeMs ?: lastTimeMs) / i.interval).toInt() / perSheet
        val order = sheets.indices.sortedBy { abs(it - here) }
        var bytes = 0L
        var made = 0
        for (s in order) {
            if (key != forKey) return
            val data = sheets.getOrNull(s) ?: continue
            val opts =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = softScale
                }
            val bmp =
                try {
                    BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                } catch (e: Throwable) {
                    Timber.w(e, "NoBars: soft preview sheet %s failed", s)
                    null
                } ?: continue
            if (key != forKey) return
            soft.set(s, bmp.asImageBitmap())
            bytes += bmp.width.toLong() * bmp.height * 2
            made++
            _version.value++
        }
        Timber.i(
            "NoBars: soft previews ready: %s sheets at 1/%s size, %s MB in %s ms",
            made,
            softScale,
            bytes / (1024 * 1024),
            System.currentTimeMillis() - started,
        )
    }

    @Volatile private var lastTimeMs = 0L

    @Volatile private var lastRequest: Request? = null

    fun clear() {
        loadJob?.cancel()
        sharpJob?.cancel()
        wake.close()
        key = null
        info = null
        pending.set(null)
        lastRequest = null
        sheets = emptyArray()
        soft = AtomicReferenceArray(0)
        decoders.values.forEach { runCatching { it.recycle() } }
        decoders.clear()
        tiles.evictAll()
        _version.value++
    }

    /**
     * The picture for this moment of the film: the sharp one if ready, else the soft one, else the nearest picture
     * there is (null only before anything is ready).
     */
    fun picture(timeMs: Long): SeekPicture? {
        val i = info ?: return null
        val index = (timeMs / i.interval).toInt()
        tiles.get(index)?.let { return SeekPicture(it, IntOffset.Zero, IntSize(it.width, it.height)) }
        val perSheet = i.tileWidth * i.tileHeight
        val layer = soft
        if (layer.length() == 0) return nearestSharp(index)
        val sheet = (index / perSheet).coerceIn(0, layer.length() - 1)
        softPicture(i, layer, sheet, index % perSheet)?.let { return it }
        // that sheet isn't open yet: the closest picture from the nearest sheet that is
        for (d in 1 until layer.length()) {
            if (sheet - d >= 0) softPicture(i, layer, sheet - d, perSheet - 1)?.let { return it }
            if (sheet + d < layer.length()) softPicture(i, layer, sheet + d, 0)?.let { return it }
        }
        return nearestSharp(index)
    }

    private fun nearestSharp(index: Int): SeekPicture? {
        for (d in 1..12) {
            (tiles.get(index - d) ?: tiles.get(index + d))?.let {
                return SeekPicture(it, IntOffset.Zero, IntSize(it.width, it.height))
            }
        }
        return null
    }

    private fun softPicture(
        i: TrickplayInfo,
        layer: AtomicReferenceArray<ImageBitmap?>,
        sheet: Int,
        tile: Int,
    ): SeekPicture? {
        val img = layer.get(sheet) ?: return null
        val s = softScale
        val w = i.width / s
        val h = i.height / s
        val x = (tile % i.tileWidth) * w
        val y = (tile / i.tileWidth) * h
        if (w <= 0 || h <= 0 || x + w > img.width || y + h > img.height) return null
        return SeekPicture(img, IntOffset(x, y), IntSize(w, h))
    }

    /**
     * Keep pictures sharp around [timeMs]: the one under the frame first, then its neighbours, then 30 ahead and
     * 10 behind ([forward] says which way is ahead), [stepMs] apart (one press). Only the newest request counts.
     */
    fun prepare(
        timeMs: Long,
        stepMs: Long,
        forward: Boolean,
        durationMs: Long,
    ) {
        if (info == null) return
        lastTimeMs = timeMs
        val r = Request(timeMs, stepMs, forward, durationMs)
        lastRequest = r
        pending.set(r)
        wake.trySend(Unit)
    }

    private suspend fun sharpWorker(
        forKey: String,
        signal: Channel<Unit>,
    ) {
        while (signal.receiveCatching().isSuccess) {
            while (scopeActive(forKey)) {
                val req = pending.getAndSet(null) ?: break
                val i = info ?: break
                if (sheets.isEmpty()) break
                for (index in wanted(i, req)) {
                    if (!scopeActive(forKey)) return
                    if (tiles.get(index) == null) {
                        val bmp = cut(i, index) // never interrupted half-way
                        if (bmp != null && key == forKey) {
                            tiles.put(index, bmp)
                            _version.value++
                        }
                    }
                    if (pending.get() != null) break // a newer position: drop the rest of this one
                }
            }
        }
    }

    private suspend fun scopeActive(forKey: String): Boolean = currentCoroutineContext().isActive && key == forKey

    private fun wanted(
        i: TrickplayInfo,
        r: Request,
    ): List<Int> {
        val step = r.stepMs.coerceAtLeast(i.interval.toLong())
        val out = LinkedHashSet<Int>()

        fun add(t: Long) {
            if (t >= 0 && (r.durationMs <= 0 || t <= r.durationMs)) out += (t / i.interval).toInt()
        }
        val dir = if (r.forward) 1 else -1
        add(r.timeMs)
        for (k in 1..4) {
            add(r.timeMs + dir * k * step)
            add(r.timeMs - dir * k * step)
        }
        for (k in 5..30) add(r.timeMs + dir * k * step)
        for (k in 5..10) add(r.timeMs - dir * k * step)
        return out.toList()
    }

    private fun cut(
        i: TrickplayInfo,
        index: Int,
    ): ImageBitmap? {
        val perSheet = i.tileWidth * i.tileHeight
        val sheet = index / perSheet
        val bytes = sheets.getOrNull(sheet) ?: return null
        return try {
            val decoder =
                decoders.getOrPut(sheet) {
                    @Suppress("DEPRECATION")
                    (
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                        } else {
                            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                        }
                    ) ?: return null
                }
            val tile = index % perSheet
            val x = (tile % i.tileWidth) * i.width
            val y = (tile / i.tileWidth) * i.height
            if (x + i.width > decoder.width || y + i.height > decoder.height) return null
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            decoder.decodeRegion(Rect(x, y, x + i.width, y + i.height), opts)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

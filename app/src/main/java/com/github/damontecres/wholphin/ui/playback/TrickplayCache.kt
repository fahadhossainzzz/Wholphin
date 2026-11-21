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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.TrickplayInfo
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One seek-preview picture: [image] (a single picture, or a whole sheet) and the part of it to show. */
class SeekPicture(
    val image: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
)

/**
 * NoBars: the seek previews of the film being played, done the way Netflix/Roku do it: all of the film's preview
 * sheets (Jellyfin's trickplay images, ~3 MB a film) are downloaded the moment the film starts and kept compressed;
 * single full-size pictures are cut out on demand, the one under the frame first. Like ExoPlayer's scrubbing mode,
 * a picture being cut is always finished and only the newest request waits (nothing in between is done), so holding
 * the button never starves it. Then the neighbours, then 30 ahead / 10 behind.
 *
 * [picture] gives the picture for a moment, else the nearest one there is. [version] changes whenever new pictures
 * are ready, so the filmstrip redraws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TrickplayCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sharpDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Volatile private var key: String? = null

    @Volatile private var info: TrickplayInfo? = null

    @Volatile private var sheets: Array<ByteArray?> = emptyArray()

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

    /** Start of a film: fetch all its preview sheets (in parallel, in the background), then cut the pictures on screen. */
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
            }
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
        decoders.values.forEach { runCatching { it.recycle() } }
        decoders.clear()
        tiles.evictAll()
        _version.value++
    }

    /**
     * The picture for this moment of the film if it is ready, else the nearest picture there is (null only before
     * anything is ready).
     */
    fun picture(timeMs: Long): SeekPicture? {
        val i = info ?: return null
        val index = (timeMs / i.interval).toInt()
        tiles.get(index)?.let {
            stats[0]++
            return whole(it)
        }
        // not cut yet (a moment): the nearest picture there is
        for (d in 1..60) {
            val near = tiles.get(index - d) ?: tiles.get(index + d)
            if (near != null) {
                stats[1]++
                return whole(near)
            }
        }
        stats[2]++
        return null
    }

    private fun whole(img: ImageBitmap) = SeekPicture(img, IntOffset.Zero, IntSize(img.width, img.height))

    // what the filmstrip boxes were given while seeking: their own picture, the nearest one, nothing
    private val stats = IntArray(3)

    /** End of a seek: log what the boxes got (to check the previews from a playback report). */
    fun logSeekStats() {
        if (stats.sum() == 0) return
        Timber.i("NoBars: seek pictures shown: exact %s, nearest %s, none %s", stats[0], stats[1], stats[2])
        stats.fill(0)
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

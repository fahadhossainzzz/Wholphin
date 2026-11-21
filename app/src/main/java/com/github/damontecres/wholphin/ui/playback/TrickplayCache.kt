package com.github.damontecres.wholphin.ui.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.github.damontecres.wholphin.services.ServerAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.TrickplayInfo
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * NoBars: the seek previews of the film being played, Netflix style: all of the film's preview sheets (Jellyfin's
 * trickplay images, ~3 MB a film, 10 MB at most) are downloaded the moment the film starts and kept compressed in
 * memory; single pictures are cut out of them when needed (a few ms each, only the 320-px picture, never the
 * whole sheet) and the ones around the current position are kept ready: 30 ahead and 10 behind, in the direction
 * of travel while seeking. [version] changes whenever new pictures are ready, so the filmstrip redraws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TrickplayCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var key: String? = null
    private var info: TrickplayInfo? = null
    private var sheets: Array<ByteArray?> = emptyArray()
    private val decoders = ConcurrentHashMap<Int, BitmapRegionDecoder>()
    private val tiles =
        object : LruCache<Int, ImageBitmap>(12 * 1024 * 1024) {
            override fun sizeOf(
                key: Int,
                value: ImageBitmap,
            ): Int = value.width * value.height * 2
        }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var loadJob: Job? = null
    private var decodeJob: Job? = null

    /** Start of a film: fetch all its preview sheets (in parallel, in the background). */
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
        loadJob =
            scope.launch {
                val started = System.currentTimeMillis()
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
                    .let { total ->
                        Timber.i("NoBars: seek previews loaded: %s sheets, %s KB in %s ms", count, total / 1024, System.currentTimeMillis() - started)
                    }
                _version.value++
                lastRequest?.let { (t, st, fw, d) -> prepare(t, st, fw, d) }
            }
    }

    private data class Request(
        val timeMs: Long,
        val stepMs: Long,
        val forward: Boolean,
        val durationMs: Long,
    )

    private var lastRequest: Request? = null

    fun clear() {
        loadJob?.cancel()
        decodeJob?.cancel()
        key = null
        info = null
        lastRequest = null
        sheets = emptyArray()
        decoders.values.forEach { runCatching { it.recycle() } }
        decoders.clear()
        tiles.evictAll()
        _version.value++
    }

    /** The picture for this moment of the film, if it is ready (else null: ask with [prepare]). */
    fun picture(timeMs: Long): ImageBitmap? {
        val i = info ?: return null
        return tiles.get((timeMs / i.interval).toInt())
    }

    /**
     * Keep pictures ready around [timeMs]: the ones on screen first, then 30 ahead and 10 behind ([forward] says
     * which way is ahead), [stepMs] apart (one press).
     */
    fun prepare(
        timeMs: Long,
        stepMs: Long,
        forward: Boolean,
        durationMs: Long,
    ) {
        val i = info ?: return
        lastRequest = Request(timeMs, stepMs, forward, durationMs)
        if (sheets.isEmpty()) return
        val step = stepMs.coerceAtLeast(i.interval.toLong())
        val wanted = LinkedHashSet<Int>()
        fun add(t: Long) {
            if (t >= 0 && (durationMs <= 0 || t <= durationMs)) wanted += (t / i.interval).toInt()
        }
        for (k in 0..4) {
            add(timeMs + k * step)
            add(timeMs - k * step)
        }
        val dir = if (forward) 1 else -1
        for (k in 5..30) add(timeMs + dir * k * step)
        for (k in 5..10) add(timeMs - dir * k * step)
        val todo = wanted.filter { tiles.get(it) == null }
        if (todo.isEmpty()) return
        decodeJob?.cancel()
        decodeJob =
            scope.launch(decodeDispatcher) {
                var made = 0
                for (index in todo) {
                    if (tiles.get(index) != null) continue
                    val bmp = cut(i, index) ?: continue
                    tiles.put(index, bmp)
                    made++
                    if (made % 4 == 0) _version.value++
                }
                if (made > 0) _version.value++
            }
    }

    private fun cut(
        i: TrickplayInfo,
        index: Int,
    ): ImageBitmap? {
        val perSheet = i.tileWidth * i.tileHeight
        val sheet = index / perSheet
        val bytes = sheets.getOrNull(sheet) ?: return null
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
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            decoder.decodeRegion(Rect(x, y, x + i.width, y + i.height), opts)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

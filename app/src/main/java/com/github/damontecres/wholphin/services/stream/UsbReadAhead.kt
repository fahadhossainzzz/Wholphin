@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.io.File

/**
 * NoBars: a USB drive in the TV used as a read-ahead store for direct-play streams.
 *
 * The player reads through a [CacheDataSource] on the drive; [prefetchStep] keeps downloading the
 * next minutes of the film onto the drive, so the player is fed from local storage like a film
 * played from USB, and short Wi-Fi or server dips never reach it. If there is no writable drive, or
 * it is removed, everything falls back to plain streaming.
 */
object UsbReadAhead {
    private const val MAX_CACHE_BYTES = 100L * 1024 * 1024 * 1024
    private const val PREFETCH_WINDOW_MS = 10 * 60 * 1000L
    private const val CHUNK_BYTES = 32L * 1024 * 1024
    private const val BLOCK_BYTES = 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    @Volatile private var initDone = false

    @Volatile var enabled: Boolean = true

    @Volatile private var failed = false

    val active: Boolean get() = enabled && !failed && cache != null

    /**
     * Set by the stall guard when reading from the drive is slower than the film needs: the player
     * then reads from the network (the drive keeps filling and stays as a safety net).
     */
    @Volatile var playerBypass: Boolean = false

    @Synchronized
    fun init(context: Context) {
        if (initDone) return
        initDone = true
        try {
            val dir = findUsbDir(context)
            if (dir == null) {
                StreamHealth.usbStatus = "no USB drive"
                Timber.e("NoBars USB: no writable removable drive found")
                return
            }
            val existing = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val max = minOf(MAX_CACHE_BYTES, ((dir.usableSpace + existing) * 0.9).toLong())
            if (max < 4L * 1024 * 1024 * 1024) {
                StreamHealth.usbStatus = "USB drive full"
                Timber.e("NoBars USB: drive has too little space (%d MB)", max shr 20)
                return
            }
            cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(max), StandaloneDatabaseProvider(context))
            StreamHealth.usbStatus = "ready (${max shr 30} GB)"
            Timber.e("NoBars USB: read-ahead on %s, up to %d GB", dir, max shr 30)
        } catch (e: Exception) {
            failed = true
            StreamHealth.usbStatus = "error"
            Timber.e(e, "NoBars USB: could not open the drive")
        }
    }

    private fun findUsbDir(context: Context): File? =
        context
            .getExternalFilesDirs(null)
            .drop(1)
            .filterNotNull()
            .filter {
                try {
                    Environment.isExternalStorageRemovable(it) &&
                        Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                } catch (e: Exception) {
                    false
                }
            }.maxByOrNull { it.usableSpace }
            ?.let { File(it, "readahead") }
            ?.takeIf { dir ->
                try {
                    dir.mkdirs()
                    val probe = File(dir, ".probe")
                    probe.writeText("ok")
                    probe.delete()
                    true
                } catch (e: Exception) {
                    Timber.e(e, "NoBars USB: %s is not writable", dir)
                    false
                }
            }

    /** Direct-play static streams only; transcodes and playlists always go to the network. */
    fun keyFor(uri: Uri): String? {
        val path = uri.path?.lowercase() ?: return null
        if (!path.contains("/videos/") || !path.substringAfterLast('/').startsWith("stream")) return null
        val params = uri.queryParameterNames.associateBy { it.lowercase() }
        val static = params["static"]?.let { uri.getQueryParameter(it) }
        if (!static.equals("true", ignoreCase = true)) return null
        val source = params["mediasourceid"]?.let { uri.getQueryParameter(it) } ?: return null
        val tag = params["tag"]?.let { uri.getQueryParameter(it) }.orEmpty()
        return "$source-$tag"
    }

    private val keyFactory = CacheKeyFactory { spec -> keyFor(spec.uri) ?: spec.uri.toString() }

    private fun cacheFactory(upstream: DataSource.Factory): CacheDataSource.Factory? =
        cache?.let {
            CacheDataSource
                .Factory()
                .setCache(it)
                .setCacheKeyFactory(keyFactory)
                .setUpstreamDataSourceFactory(upstream)
                // The player only reads from the drive; writing is the read-ahead's job, so a slow
                // write on the drive can never hold up playback
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(
                    object : CacheDataSource.EventListener {
                        override fun onCachedBytesRead(
                            cacheSizeBytes: Long,
                            cachedBytesRead: Long,
                        ) {}

                        override fun onCacheIgnored(reason: Int) {
                            Timber.e("NoBars USB: cache ignored for a read (reason %d)", reason)
                        }
                    },
                )
        }

    /** The player's data source: counts every byte for the stall guard, routes static streams via the drive. */
    fun playerDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { RoutingDataSource(upstream.createDataSource(), if (active) cacheFactory(upstream) else null) }

    private class RoutingDataSource(
        private val plain: DataSource,
        private val cached: CacheDataSource.Factory?,
    ) : DataSource {
        private var current: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            plain.addTransferListener(transferListener)
        }

        private var viaUsb = false

        // Reads from the drive go in big blocks: each read there has a fixed cost on Android
        private val block = ByteArray(BLOCK_BYTES)
        private var blockPos = 0
        private var blockLen = 0

        override fun open(dataSpec: DataSpec): Long {
            val useCache = cached != null && active && !playerBypass && keyFor(dataSpec.uri) != null
            val source =
                if (useCache) {
                    cached!!.createDataSource().also { ds -> listeners.forEach { ds.addTransferListener(it) } }
                } else {
                    plain
                }
            current = source
            viaUsb = useCache
            blockPos = 0
            blockLen = 0
            StreamHealth.opens.incrementAndGet()
            StreamHealth.markActivity()
            return source.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (!viaUsb) {
                val t = System.nanoTime()
                val n = current!!.read(buffer, offset, length)
                StreamHealth.netReads.add(n, System.nanoTime() - t)
                if (n > 0) StreamHealth.onBytes(n)
                return n
            }
            if (blockPos >= blockLen) {
                val t = System.nanoTime()
                val n = current!!.read(block, 0, BLOCK_BYTES)
                StreamHealth.usbReads.add(n, System.nanoTime() - t)
                if (n <= 0) return n
                blockPos = 0
                blockLen = n
            }
            val n = minOf(length, blockLen - blockPos)
            System.arraycopy(block, blockPos, buffer, offset, n)
            blockPos += n
            StreamHealth.onBytes(n)
            return n
        }

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
                blockPos = 0
                blockLen = 0
            }
        }
    }

    /**
     * One step of read-ahead: finds the first part of the next [PREFETCH_WINDOW_MS] of the film not
     * yet on the drive and downloads up to [CHUNK_BYTES] of it. Returns true if it downloaded
     * something (call again soon), false if the window is complete or nothing can be done.
     */
    suspend fun prefetchStep(
        upstream: DataSource.Factory,
        uri: Uri,
        fileSize: Long,
        durationMs: Long,
        positionMs: Long,
    ): Boolean {
        val c = cache ?: return false
        if (!active || fileSize <= 0 || durationMs <= 0) return false
        val key = keyFor(uri) ?: return false
        val bytesPerMs = fileSize.toDouble() / durationMs
        val margin = 64L * 1024 * 1024
        val start = ((positionMs * bytesPerMs).toLong() - margin).coerceIn(0, fileSize)
        val end = (((positionMs + PREFETCH_WINDOW_MS) * bytesPerMs).toLong() + margin).coerceIn(0, fileSize)
        // How much is already on the drive from the playback position onwards
        val here = (positionMs * bytesPerMs).toLong().coerceIn(0, fileSize)
        val contiguous = c.getCachedLength(key, here, fileSize - here).coerceAtLeast(0)
        StreamHealth.usbAheadSec = (contiguous / bytesPerMs / 1000).toLong()
        var p = start
        while (p < end) {
            currentCoroutineContext().ensureActive()
            val len = c.getCachedLength(key, p, end - p)
            if (len > 0) {
                p += len
                continue
            }
            val gap = minOf(-len, CHUNK_BYTES, end - p)
            if (gap <= 0) return false
            val spec =
                DataSpec
                    .Builder()
                    .setUri(uri)
                    .setPosition(p)
                    .setLength(gap)
                    .setKey(key)
                    .build()
            val source =
                CacheDataSource
                    .Factory()
                    .setCache(c)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            val writer = CacheWriter(source, spec, null, null)
            val started = System.nanoTime()
            try {
                runInterruptible { writer.cache() }
                StreamHealth.prefetchReads.add(gap.toInt(), System.nanoTime() - started)
            } catch (e: Exception) {
                writer.cancel()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "NoBars USB: read-ahead chunk failed at %d MB", p shr 20)
                if (e is androidx.media3.datasource.cache.Cache.CacheException) {
                    failed = true
                    StreamHealth.usbStatus = "drive error, streaming only"
                }
                return false
            }
            return true
        }
        return false
    }
}

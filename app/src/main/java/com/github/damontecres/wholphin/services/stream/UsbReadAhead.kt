@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.services.stream

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.random.Random

/**
 * NoBars: one-way pipeline through a USB drive in the TV.
 *
 * ```
 * Mac --Wi-Fi--> downloader --writes--> USB drive --reads--> player memory (10-20 s) --> decoder
 * ```
 *
 * - The downloader ([downloadStep]) is the only thing that talks to the server for a direct-play
 *   film. It keeps the drive [AHEAD_MS] ahead of where the player is reading; what was watched stays
 *   on the drive (oldest films are removed once [MAX_CACHE_BYTES] is used).
 * - The player reads only from the drive. If it reaches a spot not written yet it waits for it; the
 *   downloader jumps there at once with a small first piece.
 * - Each drive gets a write speed test the first time it is seen. A drive that is too slow, has
 *   errors, or fails during play is marked unreliable and never used again (until "Test drive
 *   again"). A film too heavy for the drive's measured speed streams from the network instead,
 *   without marking the drive.
 * - Without a reliable drive, everything is plain network streaming.
 */
object UsbReadAhead {
    private const val MAX_CACHE_BYTES = 100L * 1024 * 1024 * 1024
    private const val AHEAD_MS = 5 * 60 * 1000L
    private const val FALLBACK_AHEAD_BYTES = 256L * 1024 * 1024
    private const val FIRST_PIECE_BYTES = 2L * 1024 * 1024
    private const val CHUNK_BYTES = 16L * 1024 * 1024
    private const val BLOCK_BYTES = 1024 * 1024
    private const val WAIT_TIMEOUT_MS = 8_000L
    private const val MIN_WRITE_MBYTES_S = 6.0
    private const val TEST_MBYTES = 48
    private const val PREFS = "nobars_usb"

    enum class Verdict { UNTESTED, TESTING, OK, BAD }

    @Volatile var enabled: Boolean = true

    @Volatile private var appContext: Context? = null

    @Volatile private var cache: SimpleCache? = null

    @Volatile private var dir: File? = null

    @Volatile private var driveId: String? = null

    @Volatile private var dbProvider: StandaloneDatabaseProvider? = null

    @Volatile var verdict: Verdict = Verdict.UNTESTED
        private set

    @Volatile private var writeMBps: Double = 0.0

    /** Set for this app run when the drive disappeared (unplugged): not a mark against the drive. */
    @Volatile private var gone = false

    /** Size and length of the film being played, set by the playback screen. */
    data class Film(
        val sizeBytes: Long,
        val durationMs: Long,
    ) {
        val bytesPerMs: Double get() = sizeBytes.toDouble() / durationMs
        val mbytesPerSec: Double get() = bytesPerMs * 1000 / 1e6
    }

    @Volatile var film: Film? = null

    // Where the player is reading, so the downloader knows where to work
    @Volatile private var readerKey: String? = null

    @Volatile private var readerUri: Uri? = null

    @Volatile private var readerPos: Long = -1

    @Volatile private var currentWriter: CacheWriter? = null

    @Volatile private var writerStart: Long = -1

    @Volatile private var writerEnd: Long = -1

    private val dataLock = Object()

    /** The drive is present, tested and reliable. */
    val active: Boolean get() = enabled && !gone && cache != null && verdict == Verdict.OK

    /** Whether the current film can be fed from the drive (its bitrate fits the drive's write speed). */
    fun filmFits(): Boolean = film?.let { it.durationMs <= 0 || it.mbytesPerSec * 1.3 < writeMBps } ?: true

    // ---------------------------------------------------------------- drive discovery and checks

    /** Finds the drive (also one plugged in while the app was open) and tests it if new. Call off the main thread. */
    @Synchronized
    fun refresh(context: Context) {
        appContext = context.applicationContext
        if (!enabled) {
            StreamHealth.usbStatus = "off in settings"
            return
        }
        try {
            val found = findUsbDir(context)
            if (cache != null && found?.absolutePath != dir?.absolutePath) {
                releaseCache()
            }
            if (found == null) {
                StreamHealth.usbStatus = "no USB drive"
                Timber.e("NoBars USB: no writable removable drive found")
                return
            }
            gone = false
            if (cache == null) {
                val db = dbProvider ?: StandaloneDatabaseProvider(context.applicationContext).also { dbProvider = it }
                val existing = found.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val max = minOf(MAX_CACHE_BYTES, ((found.usableSpace + existing) * 0.9).toLong())
                if (max < 4L * 1024 * 1024 * 1024) {
                    StreamHealth.usbStatus = "USB drive full"
                    Timber.e("NoBars USB: drive has too little space (%d MB)", max shr 20)
                    return
                }
                cache = SimpleCache(found, LeastRecentlyUsedCacheEvictor(max), db)
                dir = found
                driveId = found.absolutePath.removePrefix("/storage/").substringBefore('/')
                Timber.e("NoBars USB: drive %s at %s, up to %d GB", driveId, found, max shr 30)
            }
            loadVerdict(context)
            if (verdict == Verdict.UNTESTED) startSpeedTest()
            updateStatus()
        } catch (e: Exception) {
            Timber.e(e, "NoBars USB: could not open the drive")
            StreamHealth.usbStatus = "error opening drive"
            releaseCache()
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
            ?.takeIf { d ->
                try {
                    d.mkdirs()
                    val probe = File(d.parentFile, ".probe")
                    probe.writeText("ok")
                    probe.delete()
                    true
                } catch (e: Exception) {
                    Timber.e(e, "NoBars USB: %s is not writable", d)
                    false
                }
            }

    private fun releaseCache() {
        try {
            cache?.release()
        } catch (e: Exception) {
            Timber.w(e, "NoBars USB: release failed")
        }
        cache = null
        dir = null
    }

    private fun loadVerdict(context: Context) {
        val id = driveId ?: return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null)
        when {
            saved == null -> {
                if (verdict != Verdict.TESTING) verdict = Verdict.UNTESTED
            }

            saved.startsWith("ok:") -> {
                verdict = Verdict.OK
                writeMBps = saved.removePrefix("ok:").toDoubleOrNull() ?: 0.0
            }

            else -> {
                verdict = Verdict.BAD
            }
        }
    }

    private fun saveVerdict(value: String?) {
        val id = driveId ?: return
        appContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.apply { if (value == null) remove(id) else putString(id, value) }
            ?.apply()
    }

    private fun startSpeedTest() {
        val base = dir?.parentFile ?: return
        verdict = Verdict.TESTING
        updateStatus()
        Thread({
            try {
                val file = File(base, ".nobars_speedtest")
                val block = Random.nextBytes(1024 * 1024)
                val t0 = SystemClock.elapsedRealtimeNanos()
                FileOutputStream(file).use { out ->
                    repeat(TEST_MBYTES) { out.write(block) }
                    out.fd.sync()
                }
                val writeSec = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
                val t1 = SystemClock.elapsedRealtimeNanos()
                val buf = ByteArray(1024 * 1024)
                var read = 0L
                FileInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                    }
                }
                val readSec = (SystemClock.elapsedRealtimeNanos() - t1) / 1e9
                file.delete()
                val write = TEST_MBYTES / writeSec
                val readSpeed = read / 1e6 / readSec
                Timber.e("NoBars USB test: write %.1f MB/s, read %.1f MB/s", write, readSpeed)
                if (write >= MIN_WRITE_MBYTES_S && read >= TEST_MBYTES * 1024L * 1024) {
                    writeMBps = write
                    verdict = Verdict.OK
                    saveVerdict("ok:$write")
                } else {
                    verdict = Verdict.BAD
                    saveVerdict("bad:slow $write MB/s")
                }
            } catch (e: Exception) {
                Timber.e(e, "NoBars USB test failed")
                verdict = Verdict.BAD
                saveVerdict("bad:test error")
            }
            updateStatus()
        }, "nobars-usb-test").start()
    }

    /** Settings: forget this drive's result and test it again. */
    fun retest(context: Context) {
        Thread({
            saveVerdict(null)
            verdict = Verdict.UNTESTED
            refresh(context)
        }, "nobars-usb-retest").start()
    }

    /** Settings: delete everything the read-ahead stored on the drive. */
    fun clear(context: Context) {
        Thread({
            synchronized(this) {
                val d = dir
                val db = dbProvider
                releaseCache()
                if (d != null && db != null) {
                    try {
                        SimpleCache.delete(d, db)
                        Timber.e("NoBars USB: read-ahead cleared")
                    } catch (e: Exception) {
                        Timber.e(e, "NoBars USB: clear failed")
                    }
                }
            }
            refresh(context)
        }, "nobars-usb-clear").start()
    }

    fun summary(): String {
        val used = cache?.cacheSpace?.let { "${it shr 30} GB used" } ?: "nothing stored"
        return "${StreamHealth.usbStatus} · $used"
    }

    private fun updateStatus() {
        StreamHealth.usbStatus =
            when {
                !enabled -> "off in settings"
                gone -> "drive removed, streaming"
                cache == null -> "no USB drive"
                verdict == Verdict.TESTING -> "testing drive speed"
                verdict == Verdict.BAD -> "drive unreliable, streaming"
                verdict == Verdict.OK && !filmFits() -> "film too heavy for drive (${writeMBps.toInt()} MB/s), streaming"
                verdict == Verdict.OK -> "ready (${writeMBps.toInt()} MB/s)"
                else -> "not tested"
            }
    }

    /** A failure of the drive itself during play: never use it again. Removal only stops it for this run. */
    private fun driveFailed(e: Throwable) {
        val d = dir
        if (d == null || !d.exists()) {
            gone = true
            Timber.e("NoBars USB: drive removed, streaming from the network")
        } else if (verdict == Verdict.OK) {
            verdict = Verdict.BAD
            saveVerdict("bad:${e.javaClass.simpleName}")
            Timber.e(e, "NoBars USB: drive failed during play; it will not be used again")
        }
        updateStatus()
        synchronized(dataLock) { dataLock.notifyAll() }
    }

    /** A read from the drive failed: only a removed drive or a real drive error counts against it. */
    private fun readError(e: Throwable) {
        val d = dir
        if (d == null || !d.exists() || isDriveError(e)) driveFailed(e)
    }

    private fun isDriveError(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is Cache.CacheException) return true
            val m = c.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("EIO") || m.contains("EROFS") || m.contains("ENOENT")) return true
            c = c.cause
        }
        return false
    }

    // ---------------------------------------------------------------- the player's side

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

    /** The player's data source: direct-play films come from the drive when it is reliable, else the network. */
    fun playerDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { RoutingDataSource(upstream.createDataSource()) }

    private fun noteReader(
        key: String,
        uri: Uri,
        position: Long,
    ) {
        readerKey = key
        readerUri = uri
        readerPos = position
        // The downloader is busy far from here (a skip): stop it so it starts at the new spot at once
        val writer = currentWriter
        if (writer != null && (position < writerStart || position > writerEnd + CHUNK_BYTES)) {
            writer.cancel()
        }
    }

    private class RoutingDataSource(
        private val plain: DataSource,
    ) : DataSource {
        private var useDrive = false
        private var uri: Uri? = null
        private var key: String? = null
        private var position = 0L
        private var remaining = C.LENGTH_UNSET.toLong()
        private var plainOpen = false

        // Reading from the drive: an open run of stored data, and a 1 MB block in memory
        private var run: CacheDataSource? = null
        private var runRemaining = 0L
        private val block = ByteArray(BLOCK_BYTES)
        private var blockPos = 0
        private var blockLen = 0

        override fun addTransferListener(transferListener: TransferListener) {
            plain.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            StreamHealth.opens.incrementAndGet()
            StreamHealth.markActivity()
            uri = dataSpec.uri
            val k = keyFor(dataSpec.uri)
            useDrive = k != null && active && filmFits()
            // Only the film's own stream decides this (not subtitles or images through the same factory)
            if (k != null) StreamHealth.driveFed = useDrive
            if (!useDrive) {
                plainOpen = true
                return plain.open(dataSpec)
            }
            key = k
            position = dataSpec.position
            blockPos = 0
            blockLen = 0
            val size = film?.sizeBytes ?: 0L
            remaining =
                when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    size > 0 -> (size - position).coerceAtLeast(0)
                    else -> C.LENGTH_UNSET.toLong()
                }
            noteReader(k!!, dataSpec.uri, position)
            return remaining
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (!useDrive) {
                val t = System.nanoTime()
                val n = plain.read(buffer, offset, length)
                StreamHealth.netReads.add(n, System.nanoTime() - t)
                if (n > 0) StreamHealth.onBytes(n)
                return n
            }
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (blockPos >= blockLen) {
                val n = fillBlock()
                if (n < 0) return n
            }
            val n = minOf(length, blockLen - blockPos)
            System.arraycopy(block, blockPos, buffer, offset, n)
            blockPos += n
            position += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            StreamHealth.onBytes(n)
            return n
        }

        /** Fills the block from the drive, waiting for the downloader if the data is not there yet. */
        private fun fillBlock(): Int {
            val c = cache ?: throw IOException("USB drive is gone")
            val k = key!!
            val u = uri!!
            val want =
                if (remaining == C.LENGTH_UNSET.toLong()) BLOCK_BYTES.toLong() else minOf(BLOCK_BYTES.toLong(), remaining)
            val waitStart = SystemClock.elapsedRealtime()
            while (runRemaining <= 0) {
                closeRun()
                if (!active) throw IOException("USB drive no longer used")
                val size = film?.sizeBytes ?: 0L
                if (size > 0 && position >= size) return C.RESULT_END_OF_INPUT
                val stored =
                    try {
                        c.getCachedLength(k, position, Long.MAX_VALUE)
                    } catch (e: Exception) {
                        readError(e)
                        throw IOException(e)
                    }
                if (stored > 0) {
                    val r =
                        CacheDataSource
                            .Factory()
                            .setCache(c)
                            .setCacheKeyFactory(keyFactory)
                            .setCacheWriteDataSinkFactory(null)
                            .createDataSource()
                    try {
                        r.open(
                            DataSpec
                                .Builder()
                                .setUri(u)
                                .setPosition(position)
                                .setLength(stored)
                                .setKey(k)
                                .build(),
                        )
                    } catch (e: IOException) {
                        readError(e)
                        throw e
                    }
                    run = r
                    runRemaining = stored
                    break
                }
                noteReader(k, u, position)
                if (SystemClock.elapsedRealtime() - waitStart > WAIT_TIMEOUT_MS) {
                    throw IOException("NoBars: data not on the drive yet")
                }
                try {
                    synchronized(dataLock) { dataLock.wait(50) }
                } catch (e: InterruptedException) {
                    throw InterruptedIOException()
                }
            }
            val t = System.nanoTime()
            val n =
                try {
                    run!!.read(block, 0, minOf(want, runRemaining).toInt())
                } catch (e: IOException) {
                    readError(e)
                    throw e
                }
            StreamHealth.usbReads.add(n, System.nanoTime() - t)
            if (n <= 0) {
                closeRun()
                runRemaining = 0
                return if (n == C.RESULT_END_OF_INPUT) fillBlock() else n
            }
            runRemaining -= n
            blockPos = 0
            blockLen = n
            return n
        }

        private fun closeRun() {
            try {
                run?.close()
            } catch (e: Exception) {
                // nothing to do
            }
            run = null
            runRemaining = 0
        }

        override fun getUri(): Uri? = if (useDrive) uri else plain.uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (useDrive) emptyMap() else plain.responseHeaders

        override fun close() {
            closeRun()
            blockPos = 0
            blockLen = 0
            if (plainOpen) {
                plainOpen = false
                plain.close()
            }
        }
    }

    // ---------------------------------------------------------------- the downloader

    /**
     * One step of the downloader: finds the first part of the next [AHEAD_MS] from where the player
     * reads that is not on the drive, and writes up to [CHUNK_BYTES] of it (only [FIRST_PIECE_BYTES]
     * when the player is waiting for it). Returns true if it did something (call again at once).
     */
    suspend fun downloadStep(upstream: DataSource.Factory): Boolean {
        val c = cache ?: return false
        if (!active || !filmFits()) return false
        val key = readerKey ?: return false
        val uri = readerUri ?: return false
        val pos = readerPos
        if (pos < 0) return false
        val f = film
        val size = f?.sizeBytes ?: 0L
        val aheadBytes = if (f != null && f.durationMs > 0) (AHEAD_MS * f.bytesPerMs).toLong() else FALLBACK_AHEAD_BYTES
        val end = if (size > 0) minOf(pos + aheadBytes, size) else pos + aheadBytes

        var p = pos
        while (p < end) {
            val len =
                try {
                    c.getCachedLength(key, p, end - p)
                } catch (e: Exception) {
                    readError(e)
                    return false
                }
            if (len > 0) {
                p += len
                continue
            }
            val playerWaiting = p - pos < BLOCK_BYTES
            val piece = minOf(-len, if (playerWaiting) FIRST_PIECE_BYTES else CHUNK_BYTES, end - p)
            if (piece <= 0) break
            val source =
                CacheDataSource
                    .Factory()
                    .setCache(c)
                    .setCacheKeyFactory(keyFactory)
                    .setUpstreamDataSourceFactory(upstream)
                    .createDataSource()
            val spec =
                DataSpec
                    .Builder()
                    .setUri(uri)
                    .setPosition(p)
                    .setLength(piece)
                    .setKey(key)
                    .build()
            val writer = CacheWriter(source, spec, null, null)
            currentWriter = writer
            writerStart = p
            writerEnd = p + piece
            val started = System.nanoTime()
            try {
                runInterruptible { writer.cache() }
                StreamHealth.prefetchReads.add(piece.toInt(), System.nanoTime() - started)
            } catch (e: InterruptedIOException) {
                // Cancelled because the player skipped elsewhere: start over at the new spot
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                writer.cancel()
                throw e
            } catch (e: Exception) {
                if (isDriveError(e)) {
                    driveFailed(e)
                    return false
                }
                Timber.e(e, "NoBars USB: download of %d MB at %d MB failed (network), retrying", piece shr 20, p shr 20)
                return false
            } finally {
                currentWriter = null
                synchronized(dataLock) { dataLock.notifyAll() }
            }
            updateAhead(c, key, pos, f)
            return true
        }
        updateAhead(c, key, pos, f)
        return false
    }

    private fun updateAhead(
        c: Cache,
        key: String,
        pos: Long,
        f: Film?,
    ) {
        if (f == null || f.durationMs <= 0) return
        val stored = c.getCachedLength(key, pos, Long.MAX_VALUE).coerceAtLeast(0)
        StreamHealth.usbAheadSec = (stored / f.bytesPerMs / 1000).toLong()
        updateStatus()
    }

    /** Stall rescue: cut a stuck download so it restarts at once. */
    fun cancelDownload() {
        currentWriter?.cancel()
    }
}

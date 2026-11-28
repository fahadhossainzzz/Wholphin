package com.github.damontecres.wholphin.nobars.data

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/**
 * NoBars: how much disk space each film and show takes, for the "Size on disk" order. Jellyfin knows every file's size
 * (its media sources) but can't sort by it, so the TV lists them once (films: all their versions together; shows: all
 * episodes added up; seasons too) in the background and keeps the numbers for 30 minutes (10 for the size order).
 */
object SizeStore {
    data class Size(
        val bytes: Long,
        /** Episodes counted, for shows; 0 for films */
        val episodes: Int = 0,
    )

    private val _sizes = MutableStateFlow<Map<UUID, Size>>(emptyMap())
    val sizes: StateFlow<Map<UUID, Size>> = _sizes
    private val lock = Mutex()
    private var loadedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Load in the background when nothing is loaded yet or it's older than 30 minutes (posters call this). */
    fun refreshSoon() {
        if (System.currentTimeMillis() - loadedAt < 30 * 60_000 || lock.isLocked) return
        scope.launch { load(30 * 60_000) }
    }

    /** Forget the sizes (after a file is deleted), so the next look loads them again. */
    fun invalidate() {
        loadedAt = 0L
        refreshSoon()
    }

    /** For the "Size on disk" order: the sizes, at most 10 minutes old. */
    suspend fun ensure(
        api: ApiClient,
        userId: UUID?,
    ) = load(10 * 60_000)

    private suspend fun load(maxAgeMs: Long) =
        lock.withLock {
            if (System.currentTimeMillis() - loadedAt < maxAgeMs && _sizes.value.isNotEmpty()) return@withLock
            val user = ServerAccess.userId ?: return@withLock
            val raw =
                try {
                    ServerAccess.get(
                        "/Users/$user/Items?IncludeItemTypes=Movie,Episode&Recursive=true&Fields=MediaSources" +
                            "&EnableImages=false&EnableUserData=false&EnableTotalRecordCount=false",
                    )
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: sizes unavailable")
                    null
                } ?: return@withLock
            val items = JSONObject(raw).optJSONArray("Items") ?: return@withLock
            val out = HashMap<UUID, Size>()
            val shows = HashMap<UUID, Size>()
            val seasons = HashMap<UUID, Size>()
            for (i in 0 until items.length()) {
                val dto = items.getJSONObject(i)
                val id = uuid(dto.optString("Id")) ?: continue
                val sources = dto.optJSONArray("MediaSources")
                var bytes = 0L
                if (sources != null) for (j in 0 until sources.length()) bytes += sources.getJSONObject(j).optLong("Size", 0L)
                if (bytes <= 0) continue
                out[id] = Size(bytes)
                if (dto.optString("Type") == "Episode") {
                    uuid(dto.optString("SeriesId"))?.let { s ->
                        val t = shows[s] ?: Size(0, 0)
                        shows[s] = Size(t.bytes + bytes, t.episodes + 1)
                    }
                    uuid(dto.optString("SeasonId"))?.let { s ->
                        val t = seasons[s] ?: Size(0, 0)
                        seasons[s] = Size(t.bytes + bytes, t.episodes + 1)
                    }
                }
            }
            out.putAll(shows)
            out.putAll(seasons)
            _sizes.value = out
            loadedAt = System.currentTimeMillis()
        }

    private fun uuid(s: String?): UUID? =
        s?.takeIf { it.length >= 32 }?.let {
            try {
                UUID.fromString(if (it.contains('-')) it else it.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    fun get(id: UUID): Size? = _sizes.value[id]

    /** "8.2 GB", "42.5 GB · 10 eps" (withEpisodes), "780 MB" */
    fun label(
        size: Size,
        withEpisodes: Boolean = true,
    ): String {
        val gb = size.bytes / 1e9
        val amount = if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", size.bytes / 1e6)
        return if (withEpisodes && size.episodes > 0) "$amount · ${size.episodes} eps" else amount
    }
}

/** NoBars: true while a library is sorted by size (kept for the grid; the size now shows on every card). */
val LocalShowSizeOnCards = compositionLocalOf { false }

package com.github.damontecres.wholphin.services

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import java.util.Locale
import java.util.UUID

/**
 * NoBars: how much disk space each film and show takes, for the "Size on disk" order. Jellyfin knows every file's size
 * (its media sources) but can't sort by it, so the TV lists them once (films: all their versions together; shows: all
 * episodes added up) and keeps the numbers for 10 minutes.
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

    suspend fun ensure(
        api: ApiClient,
        userId: UUID?,
    ) = lock.withLock {
        if (System.currentTimeMillis() - loadedAt < 10 * 60_000 && _sizes.value.isNotEmpty()) return@withLock
        val items =
            api.itemsApi
                .getItems(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                    recursive = true,
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                    enableImages = false,
                    enableUserData = false,
                    enableTotalRecordCount = false,
                ).content.items
        val out = HashMap<UUID, Size>()
        val shows = HashMap<UUID, Size>()
        for (dto in items) {
            val bytes = dto.mediaSources.orEmpty().sumOf { it.size ?: 0L }
            if (dto.type == BaseItemKind.EPISODE) {
                val series = dto.seriesId ?: continue
                val s = shows[series] ?: Size(0, 0)
                shows[series] = Size(s.bytes + bytes, s.episodes + 1)
            } else if (bytes > 0) {
                out[dto.id] = Size(bytes)
            }
        }
        out.putAll(shows)
        _sizes.value = out
        loadedAt = System.currentTimeMillis()
    }

    fun get(id: UUID): Size? = _sizes.value[id]

    /** "8.2 GB", "42.5 GB · 10 eps", "780 MB" */
    fun label(size: Size): String {
        val gb = size.bytes / 1e9
        val amount = if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", size.bytes / 1e6)
        return if (size.episodes > 0) "$amount · ${size.episodes} eps" else amount
    }
}

/** NoBars: true while a library is sorted by size, so the cards add the size after the year. */
val LocalShowSizeOnCards = compositionLocalOf { false }

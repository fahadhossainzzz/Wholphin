package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import java.util.UUID

/**
 * NoBars: the films the rows have shown recently, so a film's page can open at once with what's already known
 * (title, year, ratings, story, background) while the full details load. Small and bounded (the last 400).
 */
object ItemPreviewCache {
    private const val MAX = 400
    private val map =
        object : LinkedHashMap<UUID, BaseItem>(MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, BaseItem>?) = size > MAX
        }

    fun put(item: BaseItem) {
        synchronized(map) { map[item.id] = item }
    }

    fun get(id: UUID): BaseItem? = synchronized(map) { map[id] }
}

/**
 * NoBars: whether Home needs to ask Jellyfin for its rows again when you come back to it. Coming back from a film's
 * page shows Home as you left it; after playback, a watched or favourite change, or 10 minutes away, it refreshes
 * (quietly, without a spinner).
 */
object HomeRefresh {
    @Volatile var dirty = true

    @Volatile var lastLoad = 0L

    fun needed(): Boolean = dirty || System.currentTimeMillis() - lastLoad > 10 * 60_000L

    fun loaded() {
        dirty = false
        lastLoad = System.currentTimeMillis()
    }
}

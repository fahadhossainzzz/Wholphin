package com.github.damontecres.wholphin.nobars.data

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * NoBars: what the server is preparing right now (a new download being made ready before it reaches the library:
 * subtitle, copy, then seek-bar thumbnails), from the importer's status (Plugins/ImdbRatings/ServerStatus/importer).
 * Home shows it ("Preparing: Anbil Avan · subtitle"); a delivered title still making its thumbnails is marked on
 * its poster until done.
 */
object PreparingStore {
    data class Prep(
        val name: String,
        val stage: String,
        val percent: Int?,
        val id: UUID?,
    ) {
        val stageText: String
            get() =
                when (stage) {
                    "subtitles" -> "subtitle"
                    "copying" -> "copying" + (percent?.let { " $it%" } ?: "")
                    "thumbnails" -> "seek previews"
                    else -> stage
                }
    }

    private val _items = MutableStateFlow<List<Prep>>(emptyList())
    val items: StateFlow<List<Prep>> = _items

    /** Ids of titles in the library that are still being prepared (their thumbnails). */
    fun preparing(id: UUID?): Boolean = id != null && _items.value.any { it.id == id }

    suspend fun refresh() =
        withContext(WholphinDispatchers.IO) {
            try {
                val raw = ServerAccess.get("/Plugins/ImdbRatings/ServerStatus/importer") ?: return@withContext
                val arr = JSONObject(raw).optJSONArray("preparing")
                val out = ArrayList<Prep>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id =
                            o.optString("id").takeIf { it.length >= 32 }?.let {
                                try {
                                    UUID.fromString(
                                        if (it.contains('-')) it else it.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"),
                                    )
                                } catch (e: IllegalArgumentException) {
                                    null
                                }
                            }
                        out +=
                            Prep(
                                name = o.optString("name").replace(Regex("\\s*\\(\\d{4}\\)$"), ""),
                                stage = o.optString("stage"),
                                percent = if (o.has("percent") && !o.isNull("percent")) o.optInt("percent") else null,
                                id = id,
                            )
                    }
                }
                _items.value = out
            } catch (e: Exception) {
                Timber.w(e, "NoBars: preparing status unavailable")
            }
        }

    /** Keeps the list current while a screen shows it (every 20 s). */
    suspend fun follow() {
        while (true) {
            refresh()
            delay(20_000)
        }
    }
}

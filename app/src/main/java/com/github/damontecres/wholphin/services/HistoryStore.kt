package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * NoBars: your watch history. It lives on Trakt (you mark, rate and review there: phone, laptop, or "Mark watched…"
 * on the TV); our plugin serves the server's copy of it, checking Trakt live first (has it changed?), so it always
 * matches Trakt. Titles: "movie:123" / "tv:456" (TMDB ids) -> (last watched yyyy-MM-dd or "", times watched);
 * episodes: "tv:456" -> {"1x2": day}.
 */
object HistoryStore {
    data class History(
        val items: Map<String, Pair<String, Int>> = emptyMap(),
        val episodes: Map<String, Map<String, String>> = emptyMap(),
    )

    private val _state = MutableStateFlow(History())
    val state: StateFlow<History> = _state

    val items: Map<String, Pair<String, Int>> get() = _state.value.items

    @Volatile private var loadedAt = 0L

    suspend fun load(): Map<String, Pair<String, Int>> =
        withContext(WholphinDispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History") ?: return@withContext items)
                parse(o)
                loadedAt = System.currentTimeMillis()
                items
            } catch (e: Exception) {
                Timber.w(e, "NoBars: watch history unavailable")
                items
            }
        }

    /** Load in the background when nothing is loaded yet or it is older than 2 minutes (posters call this). */
    suspend fun loadIfOld() {
        if (System.currentTimeMillis() - loadedAt > 120_000) load()
    }

    private fun parse(o: JSONObject) {
        val its = o.optJSONObject("items") ?: JSONObject()
        val map = HashMap<String, Pair<String, Int>>(its.length())
        its.keys().forEach { k -> its.optJSONArray(k)?.let { a -> map[k] = a.optString(0) to a.optInt(1, 1) } }
        val e = o.optJSONObject("eps") ?: JSONObject()
        val eps = HashMap<String, Map<String, String>>(e.length())
        e.keys().forEach { k ->
            val show = e.optJSONObject(k) ?: return@forEach
            eps[k] = show.keys().asSequence().associateWith { show.optString(it) }
        }
        _state.value = History(map, eps)
    }

    fun watched(
        key: String,
        season: Int? = null,
        episode: Int? = null,
    ): Boolean {
        val h = _state.value
        return when {
            season == null -> key in h.items
            episode == null -> h.episodes[key].orEmpty().keys.any { it.startsWith("${season}x") }
            else -> "${season}x$episode" in h.episodes[key].orEmpty()
        }
    }

    /** Add a viewing to your Trakt history; [whenWatched]: now, released, unknown or yyyy-MM-dd. True when Trakt took it. */
    suspend fun mark(
        key: String,
        whenWatched: String,
        season: Int?,
        episode: Int?,
    ): Boolean =
        withContext(WholphinDispatchers.IO) {
            val k = key.replace(":", "-")
            val q = "key=$k&when=$whenWatched" + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val ok = ServerAccess.post("/Plugins/ImdbRatings/History/Mark?$q") != null
            load()
            ok
        }

    /** Remove every viewing of it from your Trakt history. */
    suspend fun unmark(
        key: String,
        season: Int?,
        episode: Int?,
    ): Boolean =
        withContext(WholphinDispatchers.IO) {
            val k = key.replace(":", "-")
            val q = "key=$k" + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val ok = ServerAccess.post("/Plugins/ImdbRatings/History/Unmark?$q") != null
            load()
            ok
        }
}

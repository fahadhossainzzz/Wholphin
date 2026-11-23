package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + WholphinDispatchers.IO)

    /**
     * Mark watched at once on the TV (the tick shows straight away) and send it to Trakt in the background; if Trakt
     * doesn't take it, the tick is taken back and [onFail] is called (on the main thread).
     */
    fun markInBackground(
        key: String,
        whenWatched: String,
        season: Int?,
        episode: Int?,
        onFail: () -> Unit,
    ) {
        val before = _state.value
        val today = java.time.LocalDate.now().toString()
        val day = if (whenWatched == "now") today else ""
        _state.value =
            when {
                season == null -> before.copy(items = before.items + (key to (day to ((before.items[key]?.second ?: 0) + 1))))
                episode == null -> before.copy(items = before.items + (key to (day to (before.items[key]?.second ?: 1))))
                else ->
                    before.copy(
                        items = before.items + (key to (day to (before.items[key]?.second ?: 1))),
                        episodes = before.episodes + (key to (before.episodes[key].orEmpty() + ("${season}x$episode" to day))),
                    )
            }
        scope.launch {
            if (!mark(key, whenWatched, season, episode, reload = false)) {
                _state.value = before
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onFail() }
            } else {
                kotlinx.coroutines.delay(4_000) // the server refreshes its copy right after; then take the exact dates
                load()
            }
        }
    }

    /** Remove from your history at once on the TV, and on Trakt in the background (put back if Trakt refuses). */
    fun removeInBackground(
        key: String,
        season: Int?,
        episode: Int?,
        onFail: () -> Unit,
    ) {
        val before = _state.value
        _state.value =
            when {
                season == null -> before.copy(items = before.items - key, episodes = before.episodes - key)
                episode == null -> before.copy(episodes = before.episodes + (key to before.episodes[key].orEmpty().filterKeys { !it.startsWith("${season}x") }))
                else -> before.copy(episodes = before.episodes + (key to (before.episodes[key].orEmpty() - "${season}x$episode")))
            }
        scope.launch {
            if (!unmark(key, season, episode, reload = false)) {
                _state.value = before
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onFail() }
            } else {
                kotlinx.coroutines.delay(4_000)
                load()
            }
        }
    }

    /** Add a viewing to your Trakt history; [whenWatched]: now, released, unknown or yyyy-MM-dd. True when Trakt took it. */
    suspend fun mark(
        key: String,
        whenWatched: String,
        season: Int?,
        episode: Int?,
        reload: Boolean = true,
    ): Boolean =
        withContext(WholphinDispatchers.IO) {
            val k = key.replace(":", "-")
            val q = "key=$k&when=$whenWatched" + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val ok = ServerAccess.post("/Plugins/ImdbRatings/History/Mark?$q") != null
            if (reload) load()
            ok
        }

    /** Remove every viewing of it from your Trakt history. */
    suspend fun unmark(
        key: String,
        season: Int?,
        episode: Int?,
        reload: Boolean = true,
    ): Boolean =
        withContext(WholphinDispatchers.IO) {
            val k = key.replace(":", "-")
            val q = "key=$k" + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val ok = ServerAccess.post("/Plugins/ImdbRatings/History/Unmark?$q") != null
            if (reload) load()
            ok
        }
}

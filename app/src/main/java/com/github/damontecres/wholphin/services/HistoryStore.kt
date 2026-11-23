package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * NoBars: your watch history. It lives on Trakt (you mark, rate and review there, on the phone or laptop); our plugin
 * serves the server's copy of it, checking Trakt live first (has it changed?) so it always matches Trakt.
 * Key: "movie:123" / "tv:456" (TMDB ids) -> (last watched yyyy-MM-dd, times watched).
 */
object HistoryStore {
    @Volatile
    var items: Map<String, Pair<String, Int>> = emptyMap()
        private set

    suspend fun load(): Map<String, Pair<String, Int>> =
        withContext(WholphinDispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History") ?: return@withContext items)
                val its = o.optJSONObject("items") ?: return@withContext items
                val map = HashMap<String, Pair<String, Int>>(its.length())
                its.keys().forEach { k ->
                    its.optJSONArray(k)?.let { a -> map[k] = a.optString(0) to a.optInt(1, 1) }
                }
                items = map
                map
            } catch (e: Exception) {
                Timber.w(e, "NoBars: watch history unavailable")
                items
            }
        }
}

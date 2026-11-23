package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * NoBars: the facts for the bottom band of posters that aren't in your library or the daily rows (a person's career,
 * "More like this"): IMDb ★ and votes (the server's IMDb database, the same numbers as your library), box office and
 * budget (TMDB), 🍅 when already known. Asked for a whole row at once (/Plugins/ImdbRatings/Facts); kept here for the
 * session. Keys: "movie:123" / "tv:456".
 */
object PosterFacts {
    data class Facts(
        val rating: Float?,
        val votes: Int?,
        val gross: Long?,
        val budget: Long?,
        val year: Int?,
        val rt: Int?,
        /** First release (films) / first episode (series), yyyy-MM-dd */
        val releaseDate: String? = null,
    ) {
        val money: String?
            get() = gross?.let { BoxOffice.posterLabel(BoxOffice.Money(it, budget, year, source = "TMDB")) }
    }

    private val facts = ConcurrentHashMap<String, Facts>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun get(key: String): Facts? = facts[key]

    suspend fun load(keys: List<String>) =
        withContext(WholphinDispatchers.IO) {
            val todo = keys.filter { !facts.containsKey(it) && asked.add(it) }
            for (chunk in todo.chunked(60)) {
                try {
                    val q = chunk.joinToString(",") { it.replace(":", "-") }
                    val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/Facts?keys=$q") ?: continue)
                    o.keys().forEach { k ->
                        val f = o.optJSONObject(k) ?: return@forEach
                        facts[k.replace("-", ":")] =
                            Facts(
                                rating = f.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                                votes = f.optInt("v").takeIf { it > 0 },
                                gross = f.optLong("g").takeIf { it > 0 },
                                budget = f.optLong("b").takeIf { it > 0 },
                                year = f.optInt("y").takeIf { it > 0 },
                                rt = if (f.has("rt")) f.optInt("rt") else null,
                                releaseDate = f.optString("rd").takeIf { it.length == 10 },
                            )
                    }
                    _version.value++
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: poster facts unavailable")
                    chunk.forEach { asked.remove(it) }
                }
            }
        }
}

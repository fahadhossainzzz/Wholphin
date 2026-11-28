package com.nobars.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth

/** "movie:603" / "tv:1399": the key the NoBars server uses for a title. */
fun Entry.key(): String = (if (series) "tv:" else "movie:") + tmdb

/** The TMDB number of a library title (films and shows; an episode's show). */
fun BaseItemDto.tmdb(): Pair<Int, Boolean>? {
    val id = providerIds?.get("Tmdb")?.toIntOrNull() ?: return null
    return when (type) {
        BaseItemKind.MOVIE -> id to false
        BaseItemKind.SERIES -> id to true
        else -> null
    }
}

// ---- Watchlist rows (the TV's grouping)

fun whereOf(e: Entry): Set<String> {
    val out = mutableSetOf<String>()
    val on = e.services + e.ott.map { it.first }
    if (StreamingStore.libraryId(e) != null) out += "library"
    if (on.any { it.startsWith("Netflix") } || e.status?.contains("On Netflix") == true) out += "netflix"
    if (on.any { it.contains("Prime") } || e.status?.contains("On Prime") == true) out += "prime"
    if (e.status?.contains("In cinemas") == true) out += "cinemas"
    return out
}

fun watchlistRows(
    list: List<Entry>,
    watched: Set<String>,
): List<Pair<String, List<Entry>>> {
    // a film in your history has left the Watchlist (Trakt takes it off too); series stay until removed
    val left = list.filterNot { !it.series && "movie:${it.tmdb}" in watched }
    fun placed(e: Entry) = whereOf(e).let { "netflix" in it || "prime" in it || "library" in it }
    return listOf(
        "Total" to left,
        "Films" to left.filterNot { it.series },
        "TV shows" to left.filter { it.series },
        "On Netflix" to left.filter { "netflix" in whereOf(it) },
        "On Prime Video" to left.filter { "prime" in whereOf(it) },
        "In your library" to left.filter { "library" in whereOf(it) },
        "Others" to left.filterNot(::placed),
        "Best rated" to left.sortedByDescending { it.imdbRating ?: it.tmdbRating ?: 0f },
    ).filter { it.second.isNotEmpty() }
}

// ---- History (the same server rows the TV's History page shows)

object PhoneHistory {
    data class Row(
        val id: String,
        val name: String,
        val total: Int,
        val items: List<Entry>,
    )

    private const val POSTER = "https://image.tmdb.org/t/p/w342"

    @Volatile var rows: List<Row>? = null

    private fun entry(o: JSONObject): Entry? {
        val key = o.optString("k")
        val series = key.startsWith("tv:")
        val tmdb = key.substringAfter(":").toIntOrNull() ?: return null
        val day = o.optString("d")
        val plays = o.optInt("n", 1)
        return Entry(
            tmdb = tmdb,
            series = series,
            title = o.optString("t"),
            year = o.optInt("y").takeIf { it > 0 },
            poster = o.optString("p").takeIf { it.isNotBlank() }?.let { POSTER + it },
            overview = o.optString("o"),
            genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            runtime = o.optInt("rn").takeIf { it > 0 && !series },
            seasons = o.optInt("s").takeIf { it > 0 && series },
            imdbRating = o.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            imdbVotes = o.optInt("v").takeIf { it > 0 },
            libraryId = null,
            rank = null,
            // "Watched 3 Jan 2025 · 2 times"
            status = watchedLine(day, plays),
        )
    }

    private fun watchedLine(
        day: String,
        plays: Int,
    ): String? {
        val d = runCatching { LocalDate.parse(day.take(10)) }.getOrNull() ?: return null
        val f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
        return "Watched " + d.format(f) + if (plays > 1) " · $plays times" else ""
    }

    private fun entries(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::entry) } }.orEmpty()

    private fun page(
        row: String,
        kind: String?,
        limit: Int,
    ): Row? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = "row=${enc(row)}&offset=0&limit=$limit&sort=watched&reverse=false" + (kind?.let { "&kind=$it" } ?: "")
        val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf?$q") ?: return null)
        return Row(kind ?: row, "", o.optInt("total"), entries(o.optJSONArray("items")))
    }

    suspend fun load(): List<Row>? =
        withContext(Dispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf") ?: return@withContext rows)
                val a = o.optJSONArray("rows") ?: JSONArray()
                val list =
                    (0 until a.length()).mapNotNull { i ->
                        val r = a.optJSONObject(i) ?: return@mapNotNull null
                        val id = r.optString("id")
                        Row(id, if (id == "recent") "Total" else r.optString("name"), r.optInt("total"), entries(r.optJSONArray("items")))
                    }
                val films = page("recent", "movie", 24)?.takeIf { it.total > 0 }?.copy(id = "films", name = "Films")
                val shows = page("recent", "tv", 24)?.takeIf { it.total > 0 }?.copy(id = "shows", name = "TV shows")
                (list.take(1) + listOfNotNull(films, shows) + list.drop(1)).also { rows = it }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: history rows unavailable")
                rows
            }
        }

    /** "See all": up to 600 of one row, newest first. */
    suspend fun all(row: Row): List<Entry> =
        withContext(Dispatchers.IO) {
            try {
                when (row.id) {
                    "films" -> page("recent", "movie", 600)
                    "shows" -> page("recent", "tv", 600)
                    else -> page(row.id, null, 600)
                }?.items ?: row.items
            } catch (e: Exception) {
                Timber.w(e, "NoBars: history page unavailable")
                row.items
            }
        }
}

// ---- Calendar (the server's month: yours · watched · the world)

data class CalItem(
    val key: String?,
    val title: String,
    val kind: String,
    val tag: String,
    val line: String,
    val image: String?,
    val rating: Float?,
)

object PhoneCalendar {
    private val months = java.util.concurrent.ConcurrentHashMap<YearMonth, Map<LocalDate, List<CalItem>>>()

    fun cached(m: YearMonth) = months[m]

    suspend fun load(m: YearMonth): Map<LocalDate, List<CalItem>>? =
        withContext(Dispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/Calendar/$m") ?: return@withContext months[m])
                val days = o.optJSONObject("days") ?: JSONObject()
                val out = sortedMapOf<LocalDate, List<CalItem>>()
                days.keys().forEach { day ->
                    val a = days.optJSONArray(day) ?: return@forEach
                    out[LocalDate.parse(day)] =
                        (0 until a.length()).mapNotNull { i ->
                            val it = a.optJSONObject(i) ?: return@mapNotNull null
                            CalItem(
                                key = it.optString("k").takeIf { k -> k.contains(":") },
                                title = it.optString("t"),
                                kind = it.optString("kind").ifBlank { "world" },
                                tag = it.optString("tag"),
                                line = listOf(it.optString("what"), it.optString("where"), it.optString("time")).filter { s -> s.isNotBlank() }.joinToString("  ·  "),
                                image = it.optString("img").takeIf { s -> s.startsWith("http") },
                                rating = it.optDouble("r").takeIf { r -> !r.isNaN() && r > 0 }?.toFloat(),
                            )
                        }
                }
                out.also { months[m] = it }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: calendar %s unavailable", m)
                months[m]
            }
        }
}

// ---- Opening other apps

fun openTrailer(
    context: Context,
    key: String,
) = openUri(context, "https://www.youtube.com/watch?v=$key")

/** Opens the title in Netflix / Prime Video (their phone apps take these links), else a web search. */
fun openService(
    context: Context,
    service: String,
    e: Entry,
) {
    val q = URLEncoder.encode(e.title, "UTF-8")
    val s = service.lowercase()
    val url =
        when {
            s.startsWith("netflix") && e.netflixId != null -> "https://www.netflix.com/title/${e.netflixId}"
            s.startsWith("netflix") -> "https://www.netflix.com/search?q=$q"
            s.contains("prime") || s.contains("amazon") -> "https://app.primevideo.com/search?phrase=$q"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode("${e.title} ${e.year ?: ""} watch $service", "UTF-8")
        }
    openUri(context, url)
}

fun openUri(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show()
    }
}

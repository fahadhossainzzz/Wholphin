package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NoBars: Netflix and Prime Video (subscription titles only). The Mac builds two lists once a day
 * after 07:30 and our IMDb plugin serves them through Jellyfin, so the TV never talks to TMDB:
 *  - library: which of your films/shows are on each service (fetched at start, ~6 KB)
 *  - catalog: the rows of the Netflix and Prime Video pages (fetched when a page opens, ~170 KB)
 */
object StreamingStore {
    const val NETFLIX = "netflix"
    const val PRIME = "prime"
    const val CINEMA = "cinema"

    data class Entry(
        val tmdb: Int,
        val series: Boolean,
        val title: String,
        val year: Int?,
        val poster: String?,
        val overview: String,
        val genres: List<String>,
        val runtime: Int?,
        val seasons: Int?,
        val imdbRating: Float?,
        val imdbVotes: Int?,
        val libraryId: UUID?,
        val rank: Int?,
        /** Rotten Tomatoes %, from OMDb */
        val rt: Int? = null,
        /** e.g. "#2 worldwide this week · 28.4M views" */
        val note: String? = null,
        /** Worldwide box office and budget, US dollars (films only) */
        val gross: Long? = null,
        val budget: Long? = null,
        val grossToday: Long? = null,
        /** Cinema page: "In cinemas since 24 Oct · Digital release 13 Dec" */
        val date: String? = null,
        /** Cinema page: date on the poster, e.g. "20 Nov" */
        val badge: String? = null,
        /** YouTube video key of the trailer */
        val trailer: String? = null,
        /** Top three cast */
        val cast: String? = null,
        /** Gross as published, e.g. "₹1,852.44 crore" */
        val grossLocal: String? = null,
        /** Audience (🍿) score when there's no critics' score */
        val pop: Int? = null,
        /** TMDB's own rating, for titles IMDb hasn't rated yet */
        val tmdbRating: Float? = null,
        val tmdbVotes: Int? = null,
        /** Theatre page: "+$48M this week ▲ · day 12 in cinemas" */
        val facts: String? = null,
    )

    data class Row(
        val title: String,
        val ranked: Boolean,
        val items: List<Entry>,
    )

    private val _library = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Jellyfin item id (no dashes) -> services carrying it */
    val library: StateFlow<Map<String, List<String>>> = _library

    @Volatile var logos: Map<String, String> = emptyMap()
        private set

    @Volatile private var catalog: Map<String, List<Row>>? = null

    @Volatile private var catalogAt = 0L

    /** Catalogue entries by the made-up item id their poster cards use. */
    val entries = ConcurrentHashMap<UUID, Entry>()

    fun displayName(service: String) =
        when (service) {
            NETFLIX -> "Netflix"
            CINEMA -> "Theatre"
            else -> "Prime Video"
        }

    /** "movie:123" / "tv:456" (TMDB) -> Jellyfin item id, for everything in the library */
    @Volatile var owned: Map<String, String> = emptyMap()
        private set

    /** The library copy of a catalogue title, if you have it (the daily list, or anything imported since). */
    fun libraryId(e: Entry): UUID? = e.libraryId ?: owned["${if (e.series) "tv" else "movie"}:${e.tmdb}"]?.let(::uuidFromHex)

    suspend fun loadLibrary() =
        withContext(WholphinDispatchers.IO) {
            try {
                val json = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/Streaming/library") ?: return@withContext)
                logos = json.optJSONObject("logos")?.toStringMap() ?: emptyMap()
                val items = json.optJSONObject("items") ?: JSONObject()
                val map = HashMap<String, List<String>>()
                items.keys().forEach { k -> map[k] = items.optJSONArray(k)?.toStringList() ?: emptyList() }
                _library.value = map
                BoxOffice.load(json.optJSONObject("money"))
                owned = json.optJSONObject("owned")?.toStringMap() ?: emptyMap()
                CriticFallback.load(json.optJSONObject("rt"), json.optJSONObject("pop"))
                Timber.i("NoBars: streaming library list loaded (%d titles)", map.size)
            } catch (e: Exception) {
                Timber.w(e, "NoBars: streaming library list unavailable")
            }
        }

    /** Rows for one service, fetched once per hour at most. Null when the lists aren't available. */
    suspend fun rows(service: String): List<Row>? =
        withContext(WholphinDispatchers.IO) {
            if (catalog == null || System.currentTimeMillis() - catalogAt > 3_600_000) {
                try {
                    val json = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/Streaming/catalog") ?: return@withContext catalog?.get(service))
                    json.optJSONObject("logos")?.toStringMap()?.let { if (it.isNotEmpty()) logos = it }
                    val services = json.optJSONObject("services") ?: JSONObject()
                    catalog =
                        services.keys().asSequence().associateWith { name ->
                            val rows = services.optJSONObject(name)?.optJSONArray("rows") ?: JSONArray()
                            (0 until rows.length()).map { i ->
                                val r = rows.getJSONObject(i)
                                val items = r.optJSONArray("items") ?: JSONArray()
                                Row(
                                    title = r.optString("title"),
                                    ranked = r.optBoolean("ranked"),
                                    items = (0 until items.length()).map { parse(items.getJSONObject(it)) },
                                )
                            }
                        }
                    catalogAt = System.currentTimeMillis()
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: streaming catalogue unavailable")
                }
            }
            catalog?.get(service)
        }

    private fun parse(o: JSONObject): Entry =
        Entry(
            tmdb = o.optInt("tmdb"),
            series = o.optString("type") == "series",
            title = o.optString("title"),
            year = o.optInt("year").takeIf { it > 0 },
            poster = o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
            overview = o.optString("overview"),
            genres = o.optJSONArray("genres")?.toStringList() ?: emptyList(),
            runtime = o.optInt("runtime").takeIf { it > 0 },
            seasons = o.optInt("seasons").takeIf { it > 0 },
            imdbRating = o.optDouble("imdbRating").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            imdbVotes = o.optInt("imdbVotes").takeIf { it > 0 },
            libraryId = o.optString("libraryId").takeIf { it.length == 32 }?.let(::uuidFromHex),
            rank = o.optInt("rank").takeIf { it > 0 },
            rt = if (o.has("rt")) o.optInt("rt") else null,
            note = o.optString("note").takeIf { it.isNotBlank() && it != "null" },
            gross = o.optLong("gross").takeIf { it > 0 },
            budget = o.optLong("budget").takeIf { it > 0 },
            grossToday = o.optLong("grossToday").takeIf { it > 0 },
            date = o.str("date"),
            badge = o.str("badge"),
            trailer = o.str("trailer"),
            cast = o.str("cast"),
            grossLocal = o.str("grossLocal"),
            pop = if (o.has("pop")) o.optInt("pop") else null,
            tmdbRating = o.optDouble("tmdbRating").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            tmdbVotes = o.optInt("tmdbVotes").takeIf { it > 0 },
            facts = o.str("facts"),
        )

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** For the "Want it" list: the fields needed to show the card again later. */
    fun toJson(e: Entry): JSONObject =
        JSONObject().apply {
            put("tmdb", e.tmdb)
            put("type", if (e.series) "series" else "movie")
            put("title", e.title)
            e.year?.let { put("year", it) }
            e.poster?.let { put("poster", it) }
            put("overview", e.overview)
            e.date?.let { put("date", it) }
            e.trailer?.let { put("trailer", it) }
            e.cast?.let { put("cast", it) }
            e.imdbRating?.let { put("imdbRating", it.toDouble()) }
            e.imdbVotes?.let { put("imdbVotes", it) }
        }

    fun fromJson(o: JSONObject): Entry = parse(o)

    /**
     * A poster card for a catalogue title: a made-up item that the normal row cards can show. The id
     * includes the row, so the same title in two rows keeps each row's own rank and note.
     */
    fun toBaseItem(
        e: Entry,
        row: String = "",
    ): BaseItem {
        val id = UUID.nameUUIDFromBytes("tmdb-${if (e.series) "tv" else "movie"}-${e.tmdb}-$row".toByteArray())
        entries[id] = e
        return BaseItem(
            BaseItemDto(
                id = id,
                type = if (e.series) BaseItemKind.SERIES else BaseItemKind.MOVIE,
                name = e.title,
                productionYear = e.year,
                communityRating = e.imdbRating ?: e.tmdbRating,
                criticRating = e.rt?.toFloat(),
                overview = e.overview,
                genres = e.genres,
                runTimeTicks = e.runtime?.let { it * 600_000_000L },
            ),
            imageUrlOverride = e.poster,
        )
    }

    /** Small corner label on catalogue posters: the Top 10 rank, or ✓ when it's in your library. */
    fun badge(id: UUID?): String? {
        val e = id?.let { entries[it] } ?: return null
        return when {
            e.rank != null -> "#${e.rank}" + if (libraryId(e) != null) " ✓" else ""
            libraryId(e) != null -> "✓"
            else -> e.badge
        }
    }

    fun votes(id: UUID?): Int? = id?.let { entries[it]?.let { e -> e.imdbVotes ?: e.tmdbVotes } }

    private fun uuidFromHex(hex: String): UUID =
        UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }
}

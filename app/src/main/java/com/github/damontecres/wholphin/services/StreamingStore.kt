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

    /** Today's facts for every title on My list (the Mac checks the whole list each morning) */
    const val LIST = "list"

    /** The Watchlist page in the menu */
    const val WATCHLIST = "watchlist"

    /** Poster ids of the Watchlist row's own cards (they don't need the 🔖 mark) */
    private val watchlistCards = java.util.Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

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
        /** Netflix's own title number (from Wikidata), to open the title itself in the Netflix app */
        val netflixId: String? = null,
        /** Theatre page: where it's out on digital, anywhere in the world (name, logo url), up to 4 for the poster */
        val ott: List<Pair<String, String>> = emptyList(),
        /** "Netflix (US, UK, Japan +80) · Hulu (US)" */
        val streamOn: String? = null,
        val freeOn: String? = null,
        /** "Apple TV, Google Play, Amazon" */
        val rentBuy: String? = null,
        /** How many more streaming / free platforms besides the poster's one logo */
        val ottMore: Int = 0,
        /** Streaming / free platforms, most countries first (for the Open button) */
        val services: List<String> = emptyList(),
        /** Background picture for the banner at the top of the page */
        val backdrop: String? = null,
        /** Netflix Top 10: change from last week, "▲2", "▼1", "NEW" or "=" */
        val move: String? = null,
        /** My list: where the title is today, "In your library ✓ · On Netflix" or "No longer on Prime Video (since 3 Jan)" */
        val status: String? = null,
        /** My list: when it was added (yyyy-MM-dd) and from which page (netflix / prime / cinema) */
        val added: String? = null,
        val from: String? = null,
        /** The title's journey: trailer, premiere, cinemas, home release, streaming, Top 10, your library */
        val journey: List<JourneyStep> = emptyList(),
    )

    /** One dot on the panel's timeline. [kind]: build, cinema, home, chart, you. [by]: the date is "by then". */
    data class JourneyStep(
        val kind: String,
        val title: String,
        val date: String?,
        val detail: String?,
        val by: Boolean = false,
        val weekly: List<Long> = emptyList(),
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
            WATCHLIST -> "Watchlist"
            else -> "Prime Video"
        }

    /** "movie:123" / "tv:456" (TMDB) -> Jellyfin item id, for everything in the library */
    @Volatile var owned: Map<String, String> = emptyMap()

    /** Library item id (no dashes) -> Netflix's title number, for titles in the library that are on Netflix */
    @Volatile var netflixIds: Map<String, String> = emptyMap()
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
                netflixIds = json.optJSONObject("netflix")?.toStringMap() ?: emptyMap()
                CriticFallback.load(json.optJSONObject("rt"), json.optJSONObject("pop"))
                json.optJSONObject("journeys")?.let { j ->
                    libraryJourneys = j.keys().asSequence().associateWith { k -> j.optJSONArray(k)?.let(::parseSteps).orEmpty() }
                }
                json.optJSONObject("files")?.let { f ->
                    libraryFiles =
                        f.keys().asSequence().associateWith { k ->
                            val o = f.optJSONObject(k) ?: JSONObject()
                            FileSummary(o.str("a"), o.str("b"), o.str("d"))
                        }
                }
                json.optJSONObject("facts")?.let { f ->
                    libraryFacts =
                        f.keys().asSequence().associateWith { k ->
                            val o = f.optJSONObject(k) ?: JSONObject()
                            LibraryFacts(o.str("streamOn"), o.str("freeOn"), o.str("rentBuy"), o.str("collection"))
                        }
                }
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
            netflixId = o.str("nf"),
            ott =
                o.optJSONArray("ott")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { p -> p.optString("n").takeIf { it.isNotBlank() }?.let { n -> n to p.optString("l") } }
                    }
                } ?: emptyList(),
            streamOn = o.str("streamOn"),
            freeOn = o.str("freeOn"),
            rentBuy = o.str("rentBuy"),
            ottMore = o.optInt("ottMore"),
            services = o.optJSONArray("svc")?.toStringList() ?: emptyList(),
            backdrop = o.str("backdrop"),
            move = o.str("move"),
            status = o.str("status"),
            added = o.str("added"),
            from = o.str("from"),
            journey = o.optJSONArray("journey")?.let(::parseSteps) ?: emptyList(),
        )

    private fun parseSteps(a: JSONArray): List<JourneyStep> =
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { j ->
                JourneyStep(
                    kind = j.optString("k"),
                    title = j.optString("t"),
                    date = j.str("d"),
                    detail = j.str("x"),
                    by = j.optBoolean("by"),
                    weekly = j.optJSONArray("wk")?.let { w -> (0 until w.length()).map { w.optLong(it) } } ?: emptyList(),
                )
            }
        }

    /** Library titles (id without dashes): where else to watch and the collection */
    data class LibraryFacts(
        val streamOn: String?,
        val freeOn: String?,
        val rentBuy: String?,
        val collection: String?,
    )

    @Volatile var libraryJourneys: Map<String, List<JourneyStep>> = emptyMap()
        private set

    @Volatile var libraryFacts: Map<String, LibraryFacts> = emptyMap()
        private set

    /** Films and episodes (id without dashes): "4K DV · Atmos 7.1", "29 GB · 27 Mbps · 2 versions", drive "HDD2" */
    data class FileSummary(
        val av: String?,
        val size: String?,
        val drive: String?,
    )

    @Volatile var libraryFiles: Map<String, FileSummary> = emptyMap()
        private set

    /** A title's page (cast, crew, trailers, titles like it, about), fetched when its page opens; the last 50 kept */
    data class TitlePage(
        val tagline: String?,
        val originalTitle: String?,
        val language: String?,
        val countries: List<String>,
        val studios: List<String>,
        val cert: String?,
        val status: String?,
        val episodes: Int?,
        val networks: List<String>,
        val collection: String?,
        val keywords: List<String>,
        val cast: List<Triple<String, String?, String?>>,
        val crew: List<Pair<String, List<String>>>,
        val videos: List<Triple<String, String, String?>>,
        val like: List<Entry>,
    )

    private val pages =
        object : LinkedHashMap<String, TitlePage>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TitlePage>?) = size > 50
        }

    suspend fun titlePage(
        tmdb: Int,
        series: Boolean,
    ): TitlePage? =
        withContext(WholphinDispatchers.IO) {
            val key = "${if (series) "tv" else "movie"}-$tmdb"
            synchronized(pages) { pages[key] }?.let { return@withContext it }
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/Streaming/title/$key") ?: return@withContext null)
                fun list(k: String) = o.optJSONArray(k)?.toStringList().orEmpty()
                val page =
                    TitlePage(
                        tagline = o.str("tagline"),
                        originalTitle = o.str("originalTitle"),
                        language = o.str("language"),
                        countries = list("countries"),
                        studios = list("studios"),
                        cert = o.str("cert"),
                        status = o.str("status"),
                        episodes = o.optInt("episodes").takeIf { it > 0 },
                        networks = list("networks"),
                        collection = o.str("collection"),
                        keywords = list("keywords"),
                        cast =
                            o.optJSONArray("cast")?.let { a ->
                                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> Triple(c.optString("n"), c.str("c"), c.str("p")) } }
                            }.orEmpty(),
                        crew =
                            o.optJSONArray("crew")?.let { a ->
                                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> c.optString("job") to (c.optJSONArray("names")?.toStringList().orEmpty()) } }
                            }.orEmpty(),
                        videos =
                            o.optJSONArray("videos")?.let { a ->
                                (0 until a.length()).mapNotNull { i ->
                                    a.optJSONObject(i)?.let { v -> v.str("k")?.let { k -> Triple(v.optString("n").ifBlank { v.optString("t") }, k, v.str("d")) } }
                                }
                            }.orEmpty(),
                        like =
                            o.optJSONArray("like")?.let { a ->
                                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parseLike) }
                            }.orEmpty(),
                    )
                synchronized(pages) { pages[key] = page }
                page
            } catch (e: Exception) {
                Timber.w(e, "NoBars: title page %s unavailable", key)
                null
            }
        }

    private fun parseLike(o: JSONObject): Entry {
        val lib = o.str("libraryId")
        val poster = o.str("poster") ?: lib?.let { id -> ServerAccess.baseUrl?.trimEnd('/')?.let { "$it/Items/$id/Images/Primary?maxHeight=360" } }
        return Entry(
            tmdb = o.optInt("tmdb"),
            series = o.optString("type") == "series",
            title = o.optString("title"),
            year = o.optInt("year").takeIf { it > 0 },
            poster = poster,
            overview = "",
            genres = emptyList(),
            runtime = null,
            seasons = null,
            imdbRating = null,
            imdbVotes = null,
            libraryId = lib?.takeIf { it.length == 32 }?.let(::uuidFromHex),
            rank = null,
        )
    }

    /** Today's card for a title, wherever it is: the rows already loaded, the Watchlist, or any page's list. */
    suspend fun findEntry(
        tmdb: Int,
        series: Boolean,
    ): Entry? {
        fun match(e: Entry) = e.tmdb == tmdb && e.series == series
        entries.values.firstOrNull { match(it) && it.overview.isNotBlank() }?.let { return it }
        for (service in listOf(NETFLIX, PRIME, CINEMA, LIST)) {
            rows(service)?.asSequence()?.flatMap { it.items }?.firstOrNull(::match)?.let { return it }
        }
        WantedStore.load()
        return WantedStore.items.value.firstOrNull(::match) ?: entries.values.firstOrNull(::match)
    }

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
            // NoBars: enough to show the card and its panel months later, after it has left every row
            if (e.genres.isNotEmpty()) put("genres", JSONArray(e.genres))
            e.runtime?.let { put("runtime", it) }
            e.seasons?.let { put("seasons", it) }
            e.backdrop?.let { put("backdrop", it) }
            e.netflixId?.let { put("nf", it) }
            if (e.services.isNotEmpty()) put("svc", JSONArray(e.services))
            if (e.ott.isNotEmpty()) put("ott", JSONArray(e.ott.map { JSONObject().put("n", it.first).put("l", it.second) }))
            if (e.ottMore > 0) put("ottMore", e.ottMore)
            e.streamOn?.let { put("streamOn", it) }
            e.freeOn?.let { put("freeOn", it) }
            e.rentBuy?.let { put("rentBuy", it) }
            e.rt?.let { put("rt", it) }
            e.pop?.let { put("pop", it) }
            e.tmdbRating?.let { put("tmdbRating", it.toDouble()) }
            e.tmdbVotes?.let { put("tmdbVotes", it) }
            e.added?.let { put("added", it) }
            e.from?.let { put("from", it) }
            if (e.journey.isNotEmpty()) {
                put(
                    "journey",
                    JSONArray(
                        e.journey.map { j ->
                            JSONObject().apply {
                                put("k", j.kind)
                                put("t", j.title)
                                j.date?.let { put("d", it) }
                                j.detail?.let { put("x", it) }
                                if (j.by) put("by", true)
                                if (j.weekly.isNotEmpty()) put("wk", JSONArray(j.weekly))
                            }
                        },
                    ),
                )
            }
        }

    /** Today's card for a My list title: the morning check, else today's rows, else the copy saved when it was added. */
    suspend fun freshFor(
        saved: Entry,
        pageRows: List<Row>?,
    ): Entry {
        val list = rows(LIST)?.firstOrNull()?.items.orEmpty()
        val fresh =
            list.firstOrNull { it.tmdb == saved.tmdb && it.series == saved.series }
                ?: pageRows.orEmpty().asSequence().flatMap { it.items }.firstOrNull { it.tmdb == saved.tmdb && it.series == saved.series }
                ?: return saved
        return fresh.copy(
            rank = null,
            move = null,
            note = null,
            added = saved.added,
            from = saved.from,
            netflixId = fresh.netflixId ?: saved.netflixId,
            trailer = fresh.trailer ?: saved.trailer,
            // the Netflix / Prime pages leave service logos off their posters; keep the saved ones for My list
            ott = fresh.ott.ifEmpty { saved.ott },
            ottMore = if (fresh.ott.isEmpty()) saved.ottMore else fresh.ottMore,
            services = fresh.services.ifEmpty { saved.services },
            journey = fresh.journey.ifEmpty { saved.journey },
        )
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
        if (row.startsWith("my-list")) watchlistCards.add(id)
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
        val lib = libraryId(e)
        val base = when {
            e.rank != null -> "#${e.rank}" + (e.move?.takeIf { it != "=" }?.let { " $it" } ?: "") + if (lib != null) " ✓" else ""
            lib != null && lib in WantedStore.watched.value -> "Watched ✓"
            lib != null -> "✓"
            else -> e.badge
        }
        // 🔖: already on your Watchlist (not on the Watchlist's own cards)
        if (id !in watchlistCards && WantedStore.contains(e)) return base?.let { "$it 🔖" } ?: "🔖"
        return base
    }

    /** Streaming-service logos for a Theatre poster (empty elsewhere). */
    fun ottLogos(id: UUID?): List<String> = id?.let { entries[it]?.ott?.map { p -> p.second } }.orEmpty()

    fun ottMore(id: UUID?): Int = id?.let { entries[it]?.ottMore } ?: 0

    fun votes(id: UUID?): Int? = id?.let { entries[it]?.let { e -> e.imdbVotes ?: e.tmdbVotes } }

    private fun uuidFromHex(hex: String): UUID =
        UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }
}

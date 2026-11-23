package com.github.damontecres.wholphin.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.WatchlistGenreFilter
import com.github.damontecres.wholphin.data.filter.WatchlistTypeFilter
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.services.HistoryStore
import com.github.damontecres.wholphin.services.ServerAccess
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TraktMark
import com.github.damontecres.wholphin.ui.components.watchedLine
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NoBars: the History page: your Trakt watch history, as rows: Recently watched, then one row per year (newest
 * first). Each row shows 24 posters and "See all", a grid that loads as you scroll (Sort and Filter), so even years
 * of history open at once. Built on the Mac (posters and IMDb ratings added once per title) and handed to the TV one
 * row or one page at a time by the plugin.
 */
object HistoryShelf {
    data class Row(
        val id: String,
        val name: String,
        val total: Int,
        val items: List<StreamingStore.Entry>,
    )

    data class Page(
        val total: Int,
        val items: List<StreamingStore.Entry>,
        val genres: List<String>,
    )

    /** The rows as last seen, so coming back to the page shows them at once. */
    @Volatile var rows: List<Row>? = null

    private const val POSTER = "https://image.tmdb.org/t/p/w342"

    private fun entry(o: JSONObject): StreamingStore.Entry? {
        val key = o.optString("k")
        val series = key.startsWith("tv:")
        val tmdb = key.substringAfter(":").toIntOrNull() ?: return null
        val day = o.optString("d")
        val plays = o.optInt("n", 1)
        return StreamingStore.Entry(
            tmdb = tmdb,
            series = series,
            title = o.optString("t"),
            year = o.optInt("y").takeIf { it > 0 },
            poster = o.optString("p").takeIf { it.isNotBlank() }?.let { POSTER + it },
            // the header under the rows: when you watched it
            overview = watchedLine(day to plays) ?: "Watched",
            genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            runtime = null,
            seasons = null,
            imdbRating = o.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            imdbVotes = o.optInt("v").takeIf { it > 0 },
            libraryId = null,
            rank = null,
        )
    }

    private fun entries(a: JSONArray?): List<StreamingStore.Entry> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::entry) } }.orEmpty()

    suspend fun loadRows(): List<Row>? =
        withContext(WholphinDispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf") ?: return@withContext rows)
                val a = o.optJSONArray("rows") ?: JSONArray()
                (0 until a.length()).mapNotNull { i ->
                    val r = a.optJSONObject(i) ?: return@mapNotNull null
                    Row(r.optString("id"), r.optString("name"), r.optInt("total"), entries(r.optJSONArray("items")))
                }.also { rows = it }
            } catch (_: Exception) {
                rows
            }
        }

    suspend fun page(
        row: String,
        offset: Int,
        limit: Int,
        sort: String,
        reverse: Boolean,
        kind: String?,
        genres: List<String>,
    ): Page? =
        withContext(WholphinDispatchers.IO) {
            try {
                fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
                val q =
                    "row=${enc(row)}&offset=$offset&limit=$limit&sort=$sort&reverse=$reverse" +
                        (kind?.let { "&kind=$it" } ?: "") +
                        (if (genres.isNotEmpty()) "&genre=" + enc(genres.joinToString(",")) else "")
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf?$q") ?: return@withContext null)
                val g = o.optJSONArray("genres")
                Page(o.optInt("total"), entries(o.optJSONArray("items")), g?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty())
            } catch (_: Exception) {
                null
            }
        }
}

private const val HISTORY_ROW = "history"

/** OK: your copy when it's in the library, else the quick view. Hold OK: Watched just now / on release / Remove. */
private fun openHistory(
    viewModel: StreamingViewModel,
    item: BaseItem,
) {
    StreamingStore.entries[item.id]?.let { openTitle(viewModel, it) }
}

private fun historyMenu(item: BaseItem) {
    val e = StreamingStore.entries[item.id] ?: return
    TraktMark.ask(TraktMark.Target((if (e.series) "tv:" else "movie:") + e.tmdb, e.title))
}

@Composable
fun HistoryPage(
    modifier: Modifier,
    viewModel: StreamingViewModel,
) {
    var rows by remember { mutableStateOf(HistoryShelf.rows) }
    var loaded by remember { mutableStateOf(rows != null) }
    // a mark or removal from the TV changes your history: the rows follow
    val history by HistoryStore.state.collectAsState()
    LaunchedEffect(history) {
        HistoryShelf.loadRows()?.let { if (it != rows) rows = it }
        loaded = true
    }
    var seeAll by remember { mutableStateOf<HistoryShelf.Row?>(null) }
    val r = rows.orEmpty()
    val all = seeAll
    when {
        !loaded && rows == null -> LoadingPage(modifier)
        r.isEmpty() ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Your Trakt history is empty, or the server couldn't be reached. Titles you watch (or mark watched) appear here.",
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        all != null -> {
            androidx.activity.compose.BackHandler { seeAll = null }
            HistoryGrid(all, viewModel, modifier.fillMaxSize())
        }
        else -> {
            val homeRows =
                remember(r) {
                    r.map { row ->
                        HomeRowLoadingState.Success(
                            title = StringStringProvider(if (row.total > 24) "${row.name} · ${row.total}" else row.name),
                            items = row.items.map { StreamingStore.toBaseItem(it, "$HISTORY_ROW-${row.id}") },
                            showViewMore = row.total > 24,
                        )
                    }
                }
            var position by rememberPosition()
            HomePageContent(
                homeRows = homeRows,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = { p, item ->
                    position = p
                    openHistory(viewModel, item)
                },
                onLongClickItem = { _, item -> historyMenu(item) },
                onClickPlay = { _, item -> openHistory(viewModel, item) },
                showClock = false,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = false,
                showViewMore = true,
                takeFocus = true,
                modifier = modifier.fillMaxSize(),
                onClickViewMore = { p, _ ->
                    position = p
                    seeAll = r.getOrNull(p.row)
                },
            )
        }
    }
}

/** One row as a grid: loads 100 titles at a time around where you are, so thousands scroll smoothly. */
@Composable
private fun HistoryGrid(
    row: HistoryShelf.Row,
    viewModel: StreamingViewModel,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val sortOptions = remember { listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME, ItemSortBy.PREMIERE_DATE) }
    val filterOptions = remember { listOf(WatchlistTypeFilter, WatchlistGenreFilter) }
    var sort by remember(row.id) { mutableStateOf(SortAndDirection(ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING)) }
    var filter by remember(row.id) { mutableStateOf(GetItemsFilter()) }
    var genres by remember(row.id) { mutableStateOf<List<String>>(emptyList()) }
    // all the grid's places (null until loaded), and which pages of 100 have been asked for
    var cards by remember(row.id) { mutableStateOf<List<BaseItem?>>(row.items.map { StreamingStore.toBaseItem(it, "$HISTORY_ROW-${row.id}") }) }
    val asked = remember(row.id, sort, filter) { mutableSetOf<Int>() }
    val query: suspend (Int) -> HistoryShelf.Page? = { offset ->
        val key =
            when (sort.sort) {
                ItemSortBy.COMMUNITY_RATING -> "rating"
                ItemSortBy.SORT_NAME -> "title"
                ItemSortBy.PREMIERE_DATE -> "year"
                else -> "watched"
            }
        // the server gives watched / rating / year newest-highest first and titles A to Z: the other way is "reverse"
        val natural = if (key == "title") SortOrder.ASCENDING else SortOrder.DESCENDING
        val x = filter.extra
        val kind = x?.wlTypes?.takeIf { it.size == 1 }?.first()?.let { if (it == "shows") "tv" else "movie" }
        HistoryShelf.page(row.id, offset, 100, key, sort.direction != natural, kind, x?.wlGenres.orEmpty())
    }
    fun load(pageNo: Int) {
        if (!asked.add(pageNo)) return
        scope.launch {
            val p = query(pageNo * 100)
            if (p == null) {
                asked.remove(pageNo)
                return@launch
            }
            if (p.genres.isNotEmpty()) genres = p.genres
            val now = cards.toMutableList()
            if (now.size != p.total) {
                // the first page tells how many there are: the grid gets that many places
                while (now.size < p.total) now.add(null)
                while (now.size > p.total) now.removeAt(now.size - 1)
            }
            p.items.forEachIndexed { i, e -> if (pageNo * 100 + i < now.size) now[pageNo * 100 + i] = StreamingStore.toBaseItem(e, "$HISTORY_ROW-${row.id}") }
            cards = now
        }
    }
    LaunchedEffect(row.id, sort, filter) {
        cards = emptyList()
        load(0)
    }
    var focused by remember { mutableStateOf<BaseItem?>(null) }
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(row.id) {
        repeat(30) {
            kotlinx.coroutines.delay(60)
            if (gridFocus.tryRequestFocus()) return@LaunchedEffect
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(modifier = modifier) {
        com.github.damontecres.wholphin.ui.components.CollectionFolderHeader(
            listIsNotEmpty = cards.isNotEmpty(),
            showHeader = true,
            showTitle = true,
            playEnabled = false,
            title = "${row.name} · ${cards.size}",
            sortAndDirection = sort,
            onSortChange = { sort = it },
            sortOptions = sortOptions,
            getPossibleFilterValues = { option ->
                when (option) {
                    WatchlistTypeFilter -> listOf(FilterValueOption("Films", "films"), FilterValueOption("Shows", "shows"))
                    WatchlistGenreFilter -> genres.map { FilterValueOption(it, it) }
                    else -> emptyList()
                }
            },
            onClickShowViewOptions = {},
            onClickPlayAll = {},
            onClickRandom = { cards.filterNotNull().randomOrNull()?.let { openHistory(viewModel, it) } },
            currentFilter = filter,
            filterOptions = filterOptions,
            onFilterChange = { filter = it },
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        com.github.damontecres.wholphin.ui.main.HomePageHeader(
            item = focused,
            showLogo = false,
            modifier = Modifier.fillMaxWidth().height(HeaderUtils.browseHeight + 16.dp).padding(start = HeaderUtils.startPadding, top = 16.dp),
        )
        CardGrid(
            pager = cards,
            onClickItem = { _, item -> openHistory(viewModel, item) },
            onLongClickItem = { _, item -> historyMenu(item) },
            onClickPlay = { _, item -> openHistory(viewModel, item) },
            letterPosition = { 0 },
            gridFocusRequester = gridFocus,
            showJumpButtons = false,
            showLetterButtons = false,
            modifier = Modifier.fillMaxSize(),
            positionCallback = { _, pos ->
                focused = cards.getOrNull(pos)
                focused?.let(viewModel::updateBackdrop)
                // the page you're on and the next one
                load(pos / 100)
                if (pos % 100 > 60) load(pos / 100 + 1)
            },
            cardContent = { details ->
                val width = with(density) { details.widthPx.toDp() }
                com.github.damontecres.wholphin.ui.cards.BannerCard(
                    name = details.item?.name,
                    item = details.item,
                    onClick = details.onClick,
                    onLongClick = details.onLongClick,
                    aspectRatio = com.github.damontecres.wholphin.ui.AspectRatios.TALL,
                    cardHeight = if (details.widthPx > 0) width * 1.5f else 172.dp,
                    modifier = details.mod,
                )
            },
            columns = 6,
        )
    }
}

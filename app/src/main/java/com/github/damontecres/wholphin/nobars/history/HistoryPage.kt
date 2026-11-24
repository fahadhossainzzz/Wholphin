package com.github.damontecres.wholphin.nobars.history

import com.github.damontecres.wholphin.nobars.catalog.openTitle
import com.github.damontecres.wholphin.nobars.catalog.StreamingViewModel
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
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.nobars.ui.TraktMark
import com.github.damontecres.wholphin.nobars.ui.watchedLine
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder


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
    // box office and 🍅 for the posters' bands (the rest comes with the rows)
    LaunchedEffect(r) {
        com.github.damontecres.wholphin.nobars.data.PosterFacts.load(r.flatMap { row -> row.items.map { (if (it.series) "tv:" else "movie:") + it.tmdb } }.distinct())
    }
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
                            title = StringStringProvider("${row.name} · ${row.total}"),
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
    // Films and TV shows are Total with the kind fixed (no Films / Shows filter there)
    val fixedKind = when (row.id) {
        HistoryShelf.FILMS -> "movie"
        HistoryShelf.SHOWS -> "tv"
        else -> null
    }
    val filterOptions = remember(fixedKind) { if (fixedKind != null) listOf(WatchlistGenreFilter) else listOf(WatchlistTypeFilter, WatchlistGenreFilter) }
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
        val kind = fixedKind ?: x?.wlTypes?.takeIf { it.size == 1 }?.first()?.let { if (it == "shows") "tv" else "movie" }
        HistoryShelf.page(if (fixedKind != null) "recent" else row.id, offset, 100, key, sort.direction != natural, kind, x?.wlGenres.orEmpty())
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
    // the same grid as the Movies library (header, posters, View options); pages load as you scroll
    com.github.damontecres.wholphin.nobars.ui.CatalogGrid(
        title = "${row.name} · ${cards.size}",
        items = cards,
        sort = sort,
        onSortChange = { sort = it },
        sortOptions = sortOptions,
        filter = filter,
        onFilterChange = { filter = it },
        filterOptions = filterOptions,
        getPossibleFilterValues = { option ->
            when (option) {
                WatchlistTypeFilter -> listOf(FilterValueOption("Films", "films"), FilterValueOption("Shows", "shows"))
                WatchlistGenreFilter -> genres.map { FilterValueOption(it, it) }
                else -> emptyList()
            }
        },
        onClickItem = { _, item -> openHistory(viewModel, item) },
        onLongClickItem = { _, item -> historyMenu(item) },
        onFocusItem = viewModel::updateBackdrop,
        emptyText = "Nothing in this row matches this filter.",
        modifier = modifier,
        onPosition = { pos ->
            // the page you're on and the next one
            load(pos / 100)
            if (pos % 100 > 60) load(pos / 100 + 1)
        },
    )
}

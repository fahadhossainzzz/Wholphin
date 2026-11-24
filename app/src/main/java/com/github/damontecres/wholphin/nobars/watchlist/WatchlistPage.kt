package com.github.damontecres.wholphin.nobars.watchlist

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.nobars.ui.CatalogGrid
import com.github.damontecres.wholphin.data.filter.CommunityRatingFilter
import com.github.damontecres.wholphin.data.filter.DecadeFilter
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.nobars.data.PosterFacts
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.data.filter.WatchlistGenreFilter
import com.github.damontecres.wholphin.data.filter.WatchlistTypeFilter
import com.github.damontecres.wholphin.data.filter.WatchlistWhereFilter
import com.github.damontecres.wholphin.data.filter.YearFilter
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.catalog.MY_LIST_ROW
import com.github.damontecres.wholphin.nobars.catalog.StreamingPanel
import com.github.damontecres.wholphin.nobars.catalog.StreamingViewModel
import com.github.damontecres.wholphin.nobars.catalog.longPress
import com.github.damontecres.wholphin.nobars.catalog.openTitle
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.WantedStore
import com.github.damontecres.wholphin.nobars.data.WatchProviders
import com.github.damontecres.wholphin.nobars.history.HistoryPage
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** Where a Watchlist title can be watched today, for the rows and the "Where to watch" filter */
private fun whereOf(e: StreamingStore.Entry): Set<String> {
    val out = mutableSetOf<String>()
    val on = e.services + e.ott.map { it.first }
    if (StreamingStore.libraryId(e) != null) out += "library"
    if (on.any { it.startsWith("Netflix") } || e.status?.contains("On Netflix") == true) out += "netflix"
    if (on.any { it.contains("Prime") } || e.status?.contains("On Prime") == true) out += "prime"
    if (e.status?.contains("In cinemas") == true) out += "cinemas"
    if (out.isEmpty() && on.isNotEmpty()) out += "elsewhere"
    if (out.isEmpty()) out += "notyet"
    return out
}

private val WHERE_NAMES =
    listOf(
        "library" to "In your library",
        "netflix" to "On Netflix",
        "prime" to "On Prime Video",
        "cinemas" to "In cinemas",
        "elsewhere" to "Streaming elsewhere",
        "notyet" to "Not out yet",
    )

private fun releaseDate(e: StreamingStore.Entry): String =
    e.journey.firstOrNull { it.kind == "cinema" && it.date != null }?.date ?: e.year?.let { "$it-01-01" } ?: "0000"

/** NoBars: the Watchlist page in the menu, built like the Movies page: Overview (rows), All (grid with the same
 * Sort and Filter buttons), Genres (a row per genre). Everything you've saved from Netflix, Prime Video and Theatre. */
@Composable
internal fun WatchlistPage(
    modifier: Modifier,
    viewModel: StreamingViewModel,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Opens at once from the list already in memory (coming back from a title page shows the same rows, so the
    // remembered poster is still there); the server copy is refreshed quietly behind it.
    var ready by remember { mutableStateOf(WatchlistMemo.list != null) }
    LaunchedEffect(Unit) {
        WantedStore.load()
        StreamingStore.rows(StreamingStore.LIST)
        ready = true
    }
    val wanted by WantedStore.items.collectAsState()
    val watched by WantedStore.watched.collectAsState()
    var list by remember { mutableStateOf(WatchlistMemo.list.orEmpty()) }
    // after removing the poster you're on, focus goes back into the page (not to the menu)
    var refocusPending by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(0) }
    LaunchedEffect(wanted, ready) {
        if (ready) {
            val fresh = StreamingStore.freshForAll(wanted, null)
            if (fresh != list) list = fresh
            WatchlistMemo.list = fresh
            if (refocusPending) {
                refocusPending = false
                refocus++
            }
        }
    }
    var panel by remember { mutableStateOf<StreamingStore.Entry?>(null) }
    val pageFocus = remember { FocusRequester() }
    val toggle = { e: StreamingStore.Entry ->
        scope.launch {
            val added = WantedStore.toggle(e)
            if (!added) refocusPending = true
            android.widget.Toast
                .makeText(context, if (added) "Added to your Watchlist" else "Removed from your Watchlist", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        Unit
    }
    if (!ready) {
        LoadingPage(modifier)
        return
    }
    Box(modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Your Watchlist is empty. On the Netflix, Prime Video or Theatre page, hold OK on a poster " +
                        "(or press ＋ Watchlist in its panel) to save it here.",
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        } else {
            // one page of rows (24 posters each, then "See all"); See all opens that row as a grid, Back returns
            val history by HistoryStore.state.collectAsState()
            LaunchedEffect(Unit) { HistoryStore.loadIfOld() }
            val factsVersion by PosterFacts.version.collectAsState()
            LaunchedEffect(list) { PosterFacts.load(list.map { (if (it.series) "tv:" else "movie:") + it.tmdb }) }
            val rows = remember(list, history, factsVersion) { overviewRows(list, history.items.keys) }
            var seeAll by remember { mutableStateOf<String?>(WatchlistMemo.seeAll) }
            LaunchedEffect(seeAll) { WatchlistMemo.seeAll = seeAll }
            val content =
                Modifier
                    .fillMaxSize()
                    .focusRequester(pageFocus)
                    .focusRestorer()
            val openRow = rows.firstOrNull { it.first == seeAll }
            if (openRow != null) {
                androidx.activity.compose.BackHandler { seeAll = null }
                WatchlistAll(openRow.second, openRow.first, viewModel, { item -> StreamingStore.entries[item.id]?.let { openTitle(viewModel, it) } }, toggle, {}, true, refocus, content)
            } else {
                WatchlistRows(rows, viewModel, { item -> StreamingStore.entries[item.id]?.let { openTitle(viewModel, it) } }, toggle, { seeAll = it }, true, refocus, content)
            }
        }
        panel?.let { entry ->
            StreamingPanel(
                entry = entry,
                service = StreamingStore.WATCHLIST,
                fromList = true,
                onToggle = { toggle(entry) },
                onBack = { panel = null },
                onNavigate = {
                    panel = null
                    viewModel.navigationManager.navigateTo(it)
                },
                onRestoreFocus = { pageFocus.tryRequestFocus() },
            )
        }
    }
}

/** The Watchlist page's state kept while the app runs: its list (so a return opens instantly) and the All tab's Sort and Filter. */
private object WatchlistMemo {
    var list: List<StreamingStore.Entry>? = null
    var seeAll: String? = null
    val sorts = mutableMapOf<String, SortAndDirection>()
    val filters = mutableMapOf<String, GetItemsFilter>()
}

/** Each row's own order in its See all grid (until you change it). */
private fun defaultSort(row: String) =
    SortAndDirection(
        when (row) {
            "Coming soon" -> org.jellyfin.sdk.model.api.ItemSortBy.PREMIERE_DATE
            "Best rated" -> org.jellyfin.sdk.model.api.ItemSortBy.COMMUNITY_RATING
            else -> org.jellyfin.sdk.model.api.ItemSortBy.DATE_CREATED
        },
        if (row == "Coming soon") org.jellyfin.sdk.model.api.SortOrder.ASCENDING else org.jellyfin.sdk.model.api.SortOrder.DESCENDING,
    )

/** Rating order: titles with at least 1,000 IMDb votes first, then by rating (so 30 people's 9.4 doesn't top the row). */
private val byRating: Comparator<StreamingStore.Entry> =
    compareBy<StreamingStore.Entry>({ ((it.imdbVotes ?: it.tmdbVotes) ?: 0) >= 1000 }, { it.imdbRating ?: it.tmdbRating ?: 0f })

/** The exact release day when known (TMDB), else the cinema date, else 1 Jan of its year. */
private fun releaseDay(e: StreamingStore.Entry): String =
    PosterFacts.get((if (e.series) "tv:" else "movie:") + e.tmdb)?.releaseDate ?: releaseDate(e)

private fun overviewRows(
    list: List<StreamingStore.Entry>,
    watched: Set<String>,
): List<Pair<String, List<StreamingStore.Entry>>> {
    // a film in your Trakt history has left the Watchlist (Trakt takes it off too); series stay until you remove them
    val left = list.filterNot { !it.series && "movie:${it.tmdb}" in watched }
    fun placed(e: StreamingStore.Entry) = whereOf(e).let { "netflix" in it || "prime" in it || "library" in it }
    return listOf(
        "Total" to left,
        "On Netflix" to left.filter { "netflix" in whereOf(it) },
        "On Prime Video" to left.filter { "prime" in whereOf(it) },
        "In your library" to left.filter { "library" in whereOf(it) },
        "Others" to left.filterNot(::placed),
        "Best rated" to left.sortedWith(byRating.reversed()),
    ).filter { it.second.isNotEmpty() }
}

/** Rows of posters with the browsing header, like the other pages. */
@Composable
private fun WatchlistRows(
    rows: List<Pair<String, List<StreamingStore.Entry>>>,
    viewModel: StreamingViewModel,
    onOpen: (BaseItem) -> Unit,
    onToggle: (StreamingStore.Entry) -> Unit,
    onSeeAll: (String) -> Unit,
    takeFocus: Boolean,
    refocus: Int,
    modifier: Modifier,
) {
    val rowsFocus = remember { FocusRequester() }
    LaunchedEffect(refocus) {
        if (refocus > 0) {
            kotlinx.coroutines.delay(150)
            rowsFocus.tryRequestFocus()
        }
    }
    val homeRows =
        remember(rows) {
            rows.map { (title, items) ->
                // 24 posters, then "See all" (the whole row as a grid)
                HomeRowLoadingState.Success(
                    title = StringStringProvider("$title · ${items.size}"),
                    items = items.take(24).map { StreamingStore.toBaseItem(it, "$MY_LIST_ROW-$title") },
                    showViewMore = items.size > 24,
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
            onOpen(item)
        },
        onLongClickItem = { _, item -> StreamingStore.entries[item.id]?.let { longPress(it) { e -> onToggle(e) } } },
        onClickPlay = { _, item -> onOpen(item) },
        showClock = false,
        onUpdateBackdrop = viewModel::updateBackdrop,
        showLogo = false,
        showViewMore = true,
        takeFocus = takeFocus,
        modifier = modifier.focusRequester(rowsFocus),
        onClickViewMore = { p, _ ->
            position = p
            rows.getOrNull(p.row)?.first?.let(onSeeAll)
        },
    )
}

/** Everything in one grid, with the same Sort and Filter buttons as the Movies library. */
@Composable
private fun WatchlistAll(
    list: List<StreamingStore.Entry>,
    title: String,
    viewModel: StreamingViewModel,
    onOpen: (BaseItem) -> Unit,
    onToggle: (StreamingStore.Entry) -> Unit,
    onShowTabs: (Boolean) -> Unit,
    takeFocus: Boolean,
    refocus: Int,
    modifier: Modifier,
) {
    val sortOptions =
        remember {
            listOf(
                org.jellyfin.sdk.model.api.ItemSortBy.DATE_CREATED,
                org.jellyfin.sdk.model.api.ItemSortBy.SORT_NAME,
                org.jellyfin.sdk.model.api.ItemSortBy.COMMUNITY_RATING,
                org.jellyfin.sdk.model.api.ItemSortBy.PREMIERE_DATE,
                org.jellyfin.sdk.model.api.ItemSortBy.RUNTIME,
            )
        }
    val filterOptions =
        remember {
            listOf(
                WatchlistTypeFilter,
                WatchlistWhereFilter,
                WatchlistGenreFilter,
                YearFilter,
                DecadeFilter,
                CommunityRatingFilter,
            )
        }
    // kept while the app runs, so Sort and Filter are still set when you come back from a title
    var sort by remember(title) { mutableStateOf(WatchlistMemo.sorts[title] ?: defaultSort(title)) }
    var filter by remember(title) { mutableStateOf(WatchlistMemo.filters[title] ?: GetItemsFilter()) }
    LaunchedEffect(sort, filter) {
        WatchlistMemo.sorts[title] = sort
        WatchlistMemo.filters[title] = filter
    }
    val shown =
        remember(list, sort, filter) {
            val x = filter.extra
            val kept =
                list.filter { e ->
                    val year = e.year
                    (x?.wlTypes.isNullOrEmpty() || (if (e.series) "shows" else "films") in x!!.wlTypes!!) &&
                        (x?.wlWhere.isNullOrEmpty() || whereOf(e).any { it in x!!.wlWhere!! }) &&
                        (x?.wlGenres.isNullOrEmpty() || e.genres.any { it in x!!.wlGenres!! }) &&
                        (filter.years.isNullOrEmpty() || (year != null && year in filter.years!!)) &&
                        (filter.decades.isNullOrEmpty() || (year != null && (year / 10) * 10 in filter.decades!!)) &&
                        (filter.minCommunityRating == null || (e.imdbRating ?: e.tmdbRating ?: 0f) >= filter.minCommunityRating!!)
                }
            val ordered =
                when (sort.sort) {
                    org.jellyfin.sdk.model.api.ItemSortBy.SORT_NAME -> kept.sortedBy { it.title.lowercase(Locale.US).removePrefix("the ") }
                    org.jellyfin.sdk.model.api.ItemSortBy.COMMUNITY_RATING -> kept.sortedWith(byRating)
                    org.jellyfin.sdk.model.api.ItemSortBy.PREMIERE_DATE -> kept.sortedBy(::releaseDay)
                    org.jellyfin.sdk.model.api.ItemSortBy.RUNTIME -> kept.sortedBy { if (it.series) Int.MAX_VALUE else it.runtime ?: Int.MAX_VALUE - 1 }
                    else -> kept.reversed() // the list is kept newest first: ascending = oldest first
                }
            val directed = if (sort.direction == org.jellyfin.sdk.model.api.SortOrder.DESCENDING) ordered.reversed() else ordered
            directed.map { StreamingStore.toBaseItem(it, MY_LIST_ROW) }
        }
    val possible = { option: ItemFilterBy<*> ->
        when (option) {
            WatchlistTypeFilter ->
                listOf(
                    FilterValueOption("Films", "films"),
                    FilterValueOption("Shows", "shows"),
                )
            WatchlistWhereFilter ->
                WHERE_NAMES.map { FilterValueOption(it.second, it.first) }
            WatchlistGenreFilter ->
                list.flatMap { it.genres }.distinct().sorted().map { FilterValueOption(it, it) }
            YearFilter ->
                list.mapNotNull { it.year }.distinct().sorted().map { FilterValueOption("$it", it) }
            DecadeFilter ->
                list.mapNotNull { it.year?.div(10)?.times(10) }.distinct().sorted().map { FilterValueOption("${it}s", it) }
            CommunityRatingFilter ->
                (1..9).map { FilterValueOption("$it", it) }
            else -> emptyList()
        }
    }
    // the same grid as the Movies library (header, posters, View options)
    CatalogGrid(
        title = "$title · ${shown.size}",
        items = shown,
        sort = sort,
        onSortChange = { sort = it },
        sortOptions = sortOptions,
        filter = filter,
        onFilterChange = { filter = it },
        filterOptions = filterOptions,
        getPossibleFilterValues = { possible(it) },
        onClickItem = { _, item -> onOpen(item) },
        onLongClickItem = { _, item -> StreamingStore.entries[item.id]?.let { longPress(it) { e -> onToggle(e) } } },
        onFocusItem = viewModel::updateBackdrop,
        emptyText = "Nothing on your Watchlist matches this filter.",
        modifier = modifier,
    )
}

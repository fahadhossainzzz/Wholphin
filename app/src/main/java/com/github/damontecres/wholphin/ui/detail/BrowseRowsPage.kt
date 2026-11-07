package com.github.damontecres.wholphin.ui.detail

import com.github.damontecres.wholphin.services.BoxOffice
import com.github.damontecres.wholphin.services.ImdbVotesStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeGetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.BrowseOrder
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.yearsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import javax.inject.Inject

/** NoBars: which browse page to show. Films inside each row follow the "Years and Genres order" setting. */
enum class BrowseMode { YEARS, GENRES }

/**
 * NoBars: films grouped into horizontal rows, one per year (newest year first; before 2000 one row
 * per decade) or one per genre. Inside each row the newest release comes first. Reuses the home
 * page's rows, so cards, focus, details header and "view more" behave the same.
 */
@Composable
fun BrowseRowsPage(
    mode: BrowseMode,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: BrowseRowsViewModel = hiltViewModel(key = mode.name),
) {
    val order = preferences.appPreferences.interfacePreferences.browseOrder
    LaunchedEffect(mode, order) { viewModel.init(mode, order) }
    val loading by viewModel.loading.collectAsState()
    val rows by viewModel.rows.collectAsState()
    when (val state = loading) {
        is LoadingState.Error -> {
            ErrorMessage(state, modifier)
        }

        LoadingState.Loading,
        LoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            var position by rememberPosition()
            val onFocusPosition = remember { { it: RowColumn -> position = it } }
            val onClickItem =
                remember {
                    { clicked: RowColumn, item: BaseItem ->
                        position = clicked
                        viewModel.navigationManager.navigateTo(item.destination())
                    }
                }
            val onClickPlay =
                remember {
                    { _: RowColumn, item: BaseItem ->
                        viewModel.navigationManager.navigateTo(Destination.Playback(item))
                    }
                }
            val onClickViewMore =
                remember {
                    { _: RowColumn, row: HomeRowLoadingState.Success ->
                        row.rowType?.let {
                            viewModel.navigationManager.navigateTo(
                                Destination.MoreHomeRow(row.title, it, row.items.size),
                            )
                        }
                        Unit
                    }
                }
            HomePageContent(
                homeRows = rows,
                position = position,
                onFocusPosition = onFocusPosition,
                onClickItem = onClickItem,
                onLongClickItem = { _, _ -> },
                onClickPlay = onClickPlay,
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                showViewMore = true,
                onClickViewMore = onClickViewMore,
                modifier = modifier,
            )
        }
    }
}

@HiltViewModel
class BrowseRowsViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val backdropService: BackdropService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _loading = MutableStateFlow<LoadingState>(LoadingState.Loading)
        val loading: StateFlow<LoadingState> = _loading

        private val _rows = MutableStateFlow<List<HomeRowLoadingState>>(emptyList())
        val rows: StateFlow<List<HomeRowLoadingState>> = _rows

        private var started: BrowseOrder? = null
        private var order: BrowseOrder = BrowseOrder.BROWSE_HIGHEST_RATED

        fun init(
            mode: BrowseMode,
            order: BrowseOrder,
        ) {
            if (started == order) return
            started = order
            this.order = order
            _loading.value = LoadingState.Loading
            viewModelScope.launchIO {
                try {
                    val userId = serverRepository.currentUser?.id
                    val groups = if (mode == BrowseMode.YEARS) yearGroups(userId) else genreGroups(userId)
                    // NoBars: Years page opens with the library's biggest box office hits
                    val topRow = mode == BrowseMode.YEARS && BoxOffice.library.value.isNotEmpty()
                    val offset = if (topRow) 1 else 0
                    _rows.value =
                        (if (topRow) listOf(HomeRowLoadingState.Pending(StringStringProvider(boxOfficeTitle()))) else emptyList()) +
                        groups.map { HomeRowLoadingState.Pending(StringStringProvider(it.name)) }
                    _loading.value = LoadingState.Success
                    if (topRow) viewModelScope.launchIO { loadBoxOfficeRow(userId) }
                    // Rows fill in from the top, a few at a time
                    val semaphore = Semaphore(3)
                    groups
                        .mapIndexed { index, group ->
                            viewModelScope.launchIO {
                                semaphore.withPermit { loadRow(index + offset, group, userId) }
                            }
                        }
                } catch (e: Exception) {
                    Timber.e(e, "NoBars browse page failed")
                    _loading.value = LoadingState.Error(e)
                }
            }
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO { backdropService.submit(item) }
        }

        private suspend fun loadRow(
            index: Int,
            group: HomeRowConfig.GetItems,
            userId: java.util.UUID?,
        ) {
            val state =
                try {
                    // NoBars: "Highest rated" uses IMDb's weighted rating, so a film rated 8.6 by 300
                    // people no longer sits above a classic rated 8.5 by two million
                    val weighted = order == BrowseOrder.BROWSE_HIGHEST_RATED && ImdbVotesStore.votes.value.isNotEmpty()
                    val byMoney = boxOfficeOrder() && BoxOffice.library.value.isNotEmpty()
                    val request =
                        group.filter.asGetItemsRequest().copy(
                            userId = userId,
                            limit = ROW_LIMIT,
                            fields = HomeItemFields + listOf(ItemFields.CHILD_COUNT),
                            enableTotalRecordCount = true,
                        )
                    val result =
                        if (weighted || byMoney) {
                            // Light pass: ids and ratings only, then full details for the best 24
                            val pool =
                                GetItemsRequestHandler
                                    .execute(
                                        api,
                                        request.copy(limit = WEIGHTED_POOL, fields = emptyList(), enableImages = false, enableUserData = false),
                                    ).content
                            val best =
                                pool.items
                                    .sortedByDescending {
                                        if (byMoney) {
                                            moneyOf(it.id).toDouble()
                                        } else {
                                            ImdbVotesStore.weighted(it.communityRating, ImdbVotesStore.get(it.id)?.votes)
                                        }
                                    }
                                    .take(ROW_LIMIT)
                                    .map { it.id }
                            val full =
                                if (best.isEmpty()) {
                                    emptyList()
                                } else {
                                    GetItemsRequestHandler
                                        .execute(api, request.copy(ids = best, limit = null, enableTotalRecordCount = false))
                                        .content.items
                                        .sortedBy { best.indexOf(it.id) }
                                }
                            pool.copy(items = full)
                        } else {
                            GetItemsRequestHandler.execute(api, request).content
                        }
                    val rowItems = result.items
                    val total = result.totalRecordCount
                    val title = if (total > 0) "${group.name} · $total ${if (total == 1) "film" else "films"}" else group.name
                    HomeRowLoadingState.Success(
                        title = StringStringProvider(title),
                        items = rowItems.map { BaseItem(it, true) },
                        viewOptions = group.viewOptions,
                        rowType = group,
                        showViewMore = total > ROW_LIMIT,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "NoBars browse row %s failed", group.name)
                    HomeRowLoadingState.Error(StringStringProvider(group.name), exception = e)
                }
            _rows.update { list -> list.toMutableList().also { if (index < it.size) it[index] = state } }
        }

        private fun boxOfficeOrder() = order == BrowseOrder.BROWSE_BOX_OFFICE || order == BrowseOrder.BROWSE_BOX_OFFICE_TODAY

        private fun boxOfficeTitle() =
            if (order == BrowseOrder.BROWSE_BOX_OFFICE_TODAY) "Biggest box office · in today's money" else "Biggest box office · all time"

        /** Worldwide gross used for sorting: actual, or in today's money when that order is chosen. */
        private fun moneyOf(id: java.util.UUID): Long {
            val m = BoxOffice.library.value[id.toString().replace("-", "")] ?: return 0L
            return (if (order == BrowseOrder.BROWSE_BOX_OFFICE_TODAY) m.today ?: m.gross else m.gross) ?: 0L
        }

        /** The 24 biggest earners in the library, straight from the box office list (no pool pass needed). */
        private suspend fun loadBoxOfficeRow(userId: java.util.UUID?) {
            val state =
                try {
                    val today = order == BrowseOrder.BROWSE_BOX_OFFICE_TODAY
                    val best =
                        BoxOffice.library.value.entries
                            .mapNotNull { (k, m) -> ((if (today) m.today ?: m.gross else m.gross) ?: return@mapNotNull null) to k }
                            .sortedByDescending { it.first }
                            .take(ROW_LIMIT)
                            .map { java.util.UUID.fromString(it.second.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5")) }
                    val items =
                        if (best.isEmpty()) {
                            emptyList()
                        } else {
                            GetItemsRequestHandler
                                .execute(
                                    api,
                                    rowFor("", HomeGetItemsFilter()).filter.asGetItemsRequest().copy(
                                        userId = userId,
                                        ids = best,
                                        fields = HomeItemFields + listOf(ItemFields.CHILD_COUNT),
                                    ),
                                ).content.items
                                .sortedBy { best.indexOf(it.id) }
                        }
                    HomeRowLoadingState.Success(
                        title = StringStringProvider(boxOfficeTitle()),
                        items = items.map { BaseItem(it, true) },
                        viewOptions = HomeRowViewOptions(),
                        rowType = null,
                        showViewMore = false,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "NoBars box office row failed")
                    HomeRowLoadingState.Error(StringStringProvider(boxOfficeTitle()), exception = e)
                }
            _rows.update { list -> list.toMutableList().also { if (it.isNotEmpty()) it[0] = state } }
        }

        /** 2026, 2025 ... 2000, then the 1990s, 1980s ... */
        private suspend fun yearGroups(userId: java.util.UUID?): List<HomeRowConfig.GetItems> {
            val years =
                api.yearsApi
                    .getYears(
                        userId = userId,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.DESCENDING),
                    ).content.items
                    .mapNotNull { it.name?.toIntOrNull() }
                    .filter { it in 1880..2100 }
                    .distinct()
                    .sortedDescending()
            val recent = years.filter { it >= 2000 }.map { it.toString() to listOf(it) }
            val decades =
                years
                    .filter { it < 2000 }
                    .groupBy { it / 10 * 10 }
                    .toSortedMap(compareByDescending { it })
                    .map { (decade, list) -> "${decade}s" to list.sortedDescending() }
            return (recent + decades).map { (name, list) -> rowFor(name, HomeGetItemsFilter(years = list)) }
        }

        /** Every genre in the film libraries, alphabetical. */
        private suspend fun genreGroups(userId: java.util.UUID?): List<HomeRowConfig.GetItems> =
            api.genresApi
                .getGenres(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                ).content.items
                .filter { !it.name.isNullOrBlank() }
                .map { rowFor(it.name!!, HomeGetItemsFilter(genreIds = listOf(it.id))) }

        private fun rowFor(
            name: String,
            base: HomeGetItemsFilter,
        ) = HomeRowConfig.GetItems(
            name = name,
            filter =
                base.copy(
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    sortBy =
                        when (order) {
                            BrowseOrder.BROWSE_NEWEST -> listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
                            BrowseOrder.BROWSE_TOMATOES -> listOf(ItemSortBy.CRITIC_RATING, ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME)
                            else -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.CRITIC_RATING, ItemSortBy.SORT_NAME)
                        },
                    sortOrder =
                        if (order == BrowseOrder.BROWSE_NEWEST) {
                            listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
                        } else {
                            listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING)
                        },
                ),
            viewOptions = HomeRowViewOptions(),
        )

        companion object {
            private const val ROW_LIMIT = 24

            /** Films fetched per row to pick the best 24 by weighted rating (titles only, a few KB). */
            private const val WEIGHTED_POOL = 300
        }
    }

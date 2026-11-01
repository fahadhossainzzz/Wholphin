package com.github.damontecres.wholphin.ui.detail

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

/** NoBars: which browse page to show. */
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
    LaunchedEffect(mode) { viewModel.init(mode) }
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

        private var started = false

        fun init(mode: BrowseMode) {
            if (started) return
            started = true
            viewModelScope.launchIO {
                try {
                    val userId = serverRepository.currentUser?.id
                    val groups = if (mode == BrowseMode.YEARS) yearGroups(userId) else genreGroups(userId)
                    _rows.value = groups.map { HomeRowLoadingState.Pending(StringStringProvider(it.name)) }
                    _loading.value = LoadingState.Success
                    // Rows fill in from the top, a few at a time
                    val semaphore = Semaphore(3)
                    groups
                        .mapIndexed { index, group ->
                            viewModelScope.launchIO {
                                semaphore.withPermit { loadRow(index, group, userId) }
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
                    val result =
                        GetItemsRequestHandler
                            .execute(
                                api,
                                group.filter.asGetItemsRequest().copy(
                                    userId = userId,
                                    limit = ROW_LIMIT,
                                    fields = HomeItemFields + listOf(ItemFields.CHILD_COUNT),
                                    enableTotalRecordCount = true,
                                ),
                            ).content
                    val total = result.totalRecordCount
                    val title = if (total > 0) "${group.name} · $total ${if (total == 1) "film" else "films"}" else group.name
                    HomeRowLoadingState.Success(
                        title = StringStringProvider(title),
                        items = result.items.map { BaseItem(it, true) },
                        viewOptions = group.viewOptions,
                        rowType = group,
                        showViewMore = result.items.size >= ROW_LIMIT,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "NoBars browse row %s failed", group.name)
                    HomeRowLoadingState.Error(StringStringProvider(group.name), exception = e)
                }
            _rows.update { list -> list.toMutableList().also { if (index < it.size) it[index] = state } }
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
                    sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                ),
            viewOptions = HomeRowViewOptions(),
        )

        companion object {
            private const val ROW_LIMIT = 24
        }
    }

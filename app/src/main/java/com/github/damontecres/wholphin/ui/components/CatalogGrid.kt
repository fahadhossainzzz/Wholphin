package com.github.damontecres.wholphin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.services.PosterFacts
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.ui.cards.GridCard
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.main.HomePageHeader
import com.github.damontecres.wholphin.ui.playback.scale
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ScrollToTopBringIntoViewSpec
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.ItemSortBy

/**
 * NoBars: the "See all" grid of the Watchlist and History rows, made of the same pieces as the Movies library: the
 * header (Sort · Filter · View options), the same poster cards (rating band, title, "year · size" when you have it),
 * the same grid with its "1 / 577" counter, and the same View options dialog. Scrolls on to the end.
 * The View options are shared by these grids and kept on the TV.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CatalogGrid(
    title: String,
    items: List<BaseItem?>,
    sort: SortAndDirection,
    onSortChange: (SortAndDirection) -> Unit,
    sortOptions: List<ItemSortBy>,
    filter: GetItemsFilter,
    onFilterChange: (GetItemsFilter) -> Unit,
    filterOptions: List<ItemFilterBy<*>>,
    getPossibleFilterValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    onClickItem: (Int, BaseItem) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
    onFocusItem: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here matches this filter.",
    onPosition: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    var viewOptions by remember { mutableStateOf(CatalogGridOptions.load(context)) }
    var showViewOptions by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var showHeader by remember { mutableStateOf(true) }
    val gridFocus = remember { FocusRequester() }
    val landed = remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty() && !landed.value) {
            repeat(30) {
                kotlinx.coroutines.delay(50)
                if (gridFocus.tryRequestFocus()) {
                    landed.value = true
                    return@LaunchedEffect
                }
            }
        }
    }
    // the band's box office and 🍅 for titles you don't have: asked for the posters around where you are
    LaunchedEffect(position, items.size) {
        val keys =
            items
                .subList((position - 12).coerceAtLeast(0), (position + 48).coerceAtMost(items.size))
                .mapNotNull { it?.id?.let { id -> StreamingStore.entries[id] } }
                .map { (if (it.series) "tv:" else "movie:") + it.tmdb }
        if (keys.isNotEmpty()) PosterFacts.load(keys)
    }
    val focusedItem = items.getOrNull(position)
    val density = LocalDensity.current
    val defaultSpec = LocalBringIntoViewSpec.current
    Column(modifier = modifier) {
        CollectionFolderHeader(
            listIsNotEmpty = items.isNotEmpty(),
            showHeader = showHeader,
            showTitle = true,
            playEnabled = false,
            title = title,
            sortAndDirection = sort,
            onSortChange = onSortChange,
            sortOptions = sortOptions,
            getPossibleFilterValues = getPossibleFilterValues,
            onClickShowViewOptions = { showViewOptions = true },
            onClickPlayAll = {},
            onClickRandom = { items.withIndex().filter { it.value != null }.randomOrNull()?.let { onClickItem(it.index, it.value!!) } },
            currentFilter = filter,
            filterOptions = filterOptions,
            onFilterChange = onFilterChange,
        )
        AnimatedVisibility(viewOptions.showDetails) {
            HomePageHeader(
                item = focusedItem,
                showLogo = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(HeaderUtils.browseHeight + 32.dp)
                        .padding(top = 16.dp, bottom = 16.dp, start = HeaderUtils.startPadding),
            )
        }
        if (items.isEmpty()) {
            Text(emptyText, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        Box(Modifier.fillMaxSize()) {
            CardGrid(
                pager = items,
                onClickItem = { i, item ->
                    position = i
                    onClickItem(i, item)
                },
                onLongClickItem = { i, item ->
                    position = i
                    onLongClickItem(i, item)
                },
                onClickPlay = { i, item -> onClickItem(i, item) },
                letterPosition = { -1 },
                gridFocusRequester = gridFocus,
                showJumpButtons = false,
                showLetterButtons = false,
                modifier = Modifier.fillMaxSize(),
                initialPosition = 0,
                positionCallback = { columns, pos ->
                    showHeader = pos < columns
                    position = pos
                    items.getOrNull(pos)?.let(onFocusItem)
                    onPosition(pos)
                },
                cardContent = { (item, _, onClick, onLongClick, widthPx, mod) ->
                    GridCard(
                        item = item,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        imageContentScale = viewOptions.contentScale.scale,
                        imageAspectRatio = viewOptions.aspectRatio.ratio,
                        imageType = viewOptions.imageType,
                        showTitle = viewOptions.showTitles,
                        fillWidth = widthPx,
                        modifier = mod,
                    )
                },
                columns = viewOptions.columns,
                spacing = viewOptions.spacing.dp,
                bringIntoViewSpec =
                    remember(viewOptions, density) {
                        if (viewOptions.showDetails) ScrollToTopBringIntoViewSpec(with(density) { viewOptions.spacing.dp.toPx() }) else defaultSpec
                    },
            )
        }
    }
    AnimatedVisibility(showViewOptions) {
        ViewOptionsDialog(
            viewOptions = viewOptions,
            defaultViewOptions = ViewOptions(),
            onDismissRequest = {
                showViewOptions = false
                CatalogGridOptions.save(context, viewOptions)
            },
            onViewOptionsChange = {
                viewOptions = it
                CatalogGridOptions.save(context, it)
            },
        )
    }
}

/** The View options of the Watchlist and History grids, kept on the TV. */
private object CatalogGridOptions {
    private const val FILE = "nobars-grids"
    private const val KEY = "view-options"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: android.content.Context): ViewOptions =
        try {
            context
                .getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
                .getString(KEY, null)
                ?.let { json.decodeFromString(ViewOptions.serializer(), it) } ?: ViewOptions()
        } catch (_: Exception) {
            ViewOptions()
        }

    fun save(
        context: android.content.Context,
        options: ViewOptions,
    ) {
        try {
            context
                .getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json.encodeToString(ViewOptions.serializer(), options))
                .apply()
        } catch (_: Exception) {
        }
    }
}

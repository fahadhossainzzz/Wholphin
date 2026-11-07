package com.github.damontecres.wholphin.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.filter.CollectionMembershipFilter
import com.github.damontecres.wholphin.data.filter.GenreFilter
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.filter.NbFilterChoices
import com.github.damontecres.wholphin.data.filter.NbMovieFilterOptions
import com.github.damontecres.wholphin.data.filter.NbTvFilterOptions
import com.github.damontecres.wholphin.data.filter.StudioFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeGetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.services.HomeRowConfigDisplay
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.FilterByButton
import com.github.damontecres.wholphin.ui.data.CustomRowSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.ui.preferences.StringInput
import com.github.damontecres.wholphin.ui.preferences.StringInputDialog
import com.github.damontecres.wholphin.ui.search.SearchForDialog
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

private enum class Kinds(
    val label: String,
    val types: List<BaseItemKind>,
) {
    FILMS("Films", listOf(BaseItemKind.MOVIE)),
    SHOWS("Shows", listOf(BaseItemKind.SERIES)),
    BOTH("Films & shows", listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)),
}

private val ItemCounts = listOf(12, 24, 36, 50)

/** Which dialog is open in the builder */
private enum class Picker { NONE, NAME, SOURCE, COLLECTION, PEOPLE_SEARCH, PEOPLE_RESULTS, SORT }

/**
 * NoBars: builds a Home row from any filters (the library filter menu's, ours included), any order, any number of
 * items. Every change is applied at once, so the row on the right is a live preview.
 */
@Composable
fun CustomRowEditor(
    row: HomeRowConfigDisplay,
    libraries: List<Library>,
    viewModel: HomeSettingsViewModel,
    onOpenLook: () -> Unit,
    onDuplicate: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = row.config as? HomeRowConfig.GetItems ?: return
    val scope = rememberCoroutineScope()
    val f = config.filter
    val lib = f.libraryFilter ?: GetItemsFilter()
    val sort = f.sort ?: SortAndDirection(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
    var picker by remember { mutableStateOf(Picker.NONE) }
    var peopleResults by remember { mutableStateOf<List<Pair<UUID, String>>>(emptyList()) }

    // Names of ids the filters use, for the automatic row name
    val names = remember { mutableStateMapOf<Any, String>() }
    LaunchedEffect(Unit) {
        for (option in listOf<ItemFilterBy<*>>(GenreFilter, StudioFilter, CollectionMembershipFilter)) {
            viewModel.filterValues(option).forEach { v -> v.value?.let { names[it] = v.name } }
        }
    }

    val kind =
        when {
            f.includeItemTypes.orEmpty().containsAll(Kinds.BOTH.types) -> Kinds.BOTH
            f.includeItemTypes == Kinds.SHOWS.types -> Kinds.SHOWS
            else -> Kinds.FILMS
        }
    val library = libraries.firstOrNull { it.itemId == f.parentId }
    val sourceLabel =
        when {
            f.parentId == null -> "All libraries"
            library != null -> library.name
            else -> names[f.parentId] ?: "A collection"
        }
    val sortLabel = stringResource(getStringRes(sort.sort))
    val filterOptions =
        when (kind) {
            Kinds.FILMS -> NbMovieFilterOptions
            Kinds.SHOWS -> NbTvFilterOptions
            Kinds.BOTH -> (NbMovieFilterOptions + NbTvFilterOptions).distinct()
        }

    fun apply(
        newFilter: HomeGetItemsFilter,
        name: String? = null,
    ) {
        val wasAuto = config.name == HomeSettingsViewModel.NEW_CUSTOM_ROW || config.name == autoName(f, names, libraries)
        val newName = name ?: if (wasAuto) autoName(newFilter, names, libraries) else config.name
        viewModel.updateCustomRow(row.id, config.copy(name = newName, filter = newFilter))
    }

    Column(modifier = modifier) {
        TitleText("Custom row")
        Text(
            text = config.name,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 2,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        HomeSettingsLazyColumn(modifier = Modifier.fillMaxHeight()) {
            item {
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Name",
                    supportingContent = { Text(config.name, maxLines = 1) },
                    onClick = { picker = Picker.NAME },
                )
            }
            item {
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Show",
                    supportingContent = { Text(kind.label) },
                    onClick = {
                        val next = Kinds.entries[(kind.ordinal + 1) % Kinds.entries.size]
                        apply(f.copy(includeItemTypes = next.types))
                    },
                )
            }
            item {
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "From",
                    supportingContent = { Text(sourceLabel) },
                    onClick = { picker = Picker.SOURCE },
                )
            }
            item {
                // The library filter menu itself (genres, tags, years, quality, money, streaming, votes, 🍅 …)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.width(200.dp)) {
                        Text("Filters", style = MaterialTheme.typography.titleMedium)
                        val count = lib.countFilters(filterOptions)
                        Text(
                            if (count == 0) "None yet" else "$count active",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    FilterByButton(
                        filterOptions = filterOptions,
                        current = lib,
                        onFilterChange = { apply(f.copy(libraryFilter = it)) },
                        getPossibleValues = { viewModel.filterValues(it) },
                    )
                }
            }
            item {
                val people = lib.persons.orEmpty()
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "People (actor, director…)",
                    supportingContent = {
                        Text(
                            if (people.isEmpty()) "Anyone" else people.joinToString { names[it] ?: "1 person" },
                            maxLines = 2,
                        )
                    },
                    onClick = { picker = Picker.PEOPLE_SEARCH },
                    onLongClick = { if (people.isNotEmpty()) apply(f.copy(libraryFilter = lib.copy(persons = null))) },
                )
            }
            item {
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Order",
                    supportingContent = { Text(sortLabel) },
                    onClick = { picker = Picker.SORT },
                )
            }
            item {
                val down = sort.direction != SortOrder.ASCENDING
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Direction",
                    supportingContent = { Text(if (down) "Highest / newest first" else "Lowest / oldest first") },
                    onClick = { apply(f.copy(sort = sort.flip())) },
                )
            }
            item {
                val n = f.maxItems ?: 24
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Number of items",
                    supportingContent = { Text("$n") },
                    onClick = {
                        val next = ItemCounts[(ItemCounts.indexOf(n).coerceAtLeast(0) + 1) % ItemCounts.size]
                        apply(f.copy(maxItems = next))
                    },
                )
            }
            item {
                HomeSettingsListItem(
                    selected = false,
                    headlineText = "Card look (size, shape, titles)…",
                    onClick = onOpenLook,
                )
            }
            item {
                HomeSettingsListItem(selected = false, headlineText = "Duplicate this row", onClick = onDuplicate)
            }
            item {
                HomeSettingsListItem(selected = false, headlineText = "Done", onClick = onDone)
            }
            item {
                Text(
                    text = "Changes show in the row on the right straight away. Hold OK on People to clear them.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }

    when (picker) {
        Picker.NONE -> {}
        Picker.NAME ->
            StringInputDialog(
                input =
                    StringInput(
                        title = "Row name",
                        value = config.name,
                        keyboardOptions = KeyboardOptions.Default,
                        onSubmit = {},
                    ),
                onSave = {
                    picker = Picker.NONE
                    if (it.isNotBlank()) apply(f, name = it.trim())
                },
                onDismissRequest = { picker = Picker.NONE },
            )
        Picker.SOURCE -> {
            val usable =
                libraries.filter {
                    it.collectionType == CollectionType.MOVIES || it.collectionType == CollectionType.TVSHOWS ||
                        it.collectionType == CollectionType.HOMEVIDEOS
                }
            val options = listOf("All libraries") + usable.map { it.name } + "A collection…"
            val selected =
                when {
                    f.parentId == null -> 0
                    library != null -> usable.indexOf(library) + 1
                    else -> options.lastIndex
                }
            ChooseDialog("From", options, selected, onDismiss = { picker = Picker.NONE }) { i ->
                picker = Picker.NONE
                when (i) {
                    0 -> apply(f.copy(parentId = null))
                    options.lastIndex -> picker = Picker.COLLECTION
                    else -> apply(f.copy(parentId = usable[i - 1].itemId))
                }
            }
        }
        Picker.COLLECTION ->
            SearchForDialog(
                onDismissRequest = { picker = Picker.NONE },
                searchType = BaseItemKind.BOX_SET,
                onClick = {
                    picker = Picker.NONE
                    names[it.id] = it.name ?: "A collection"
                    apply(f.copy(parentId = it.id))
                },
            )
        Picker.PEOPLE_SEARCH ->
            StringInputDialog(
                input =
                    StringInput(
                        title = "Search for a person",
                        value = "",
                        keyboardOptions = KeyboardOptions.Default,
                        onSubmit = {},
                    ),
                onSave = { q ->
                    if (q.isBlank()) {
                        picker = Picker.NONE
                    } else {
                        scope.launch {
                            peopleResults = viewModel.searchPeople(q.trim())
                            picker = Picker.PEOPLE_RESULTS
                        }
                    }
                },
                onDismissRequest = { picker = Picker.NONE },
            )
        Picker.PEOPLE_RESULTS -> {
            val options = if (peopleResults.isEmpty()) listOf("Nobody found") else peopleResults.map { it.second }
            ChooseDialog("Add a person", options, -1, onDismiss = { picker = Picker.NONE }) { i ->
                picker = Picker.NONE
                peopleResults.getOrNull(i)?.let { (id, name) ->
                    names[id] = name
                    val list = (lib.persons.orEmpty() + id).distinct()
                    apply(f.copy(libraryFilter = lib.copy(persons = list)))
                }
            }
        }
        Picker.SORT -> {
            val labels = CustomRowSortOptions.map { stringResource(getStringRes(it)) }
            ChooseDialog("Order", labels, CustomRowSortOptions.indexOf(sort.sort), onDismiss = { picker = Picker.NONE }) { i ->
                picker = Picker.NONE
                val chosen = CustomRowSortOptions[i]
                val direction =
                    if (chosen == ItemSortBy.SORT_NAME) SortOrder.ASCENDING else SortOrder.DESCENDING
                apply(f.copy(sort = SortAndDirection(chosen, direction)))
            }
        }
    }
}

/** A simple list to pick one entry from. */
@Composable
private fun ChooseDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 3.dp,
    ) {
        Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(options) { index, label ->
                    HomeSettingsListItem(
                        selected = false,
                        headlineText = label,
                        leadingContent = {
                            if (index == selected) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        onClick = { onPick(index) },
                    )
                }
            }
        }
    }
}

/** "Films · Thriller · 4K · unwatched · ★7+ · by Box office" */
private fun autoName(
    f: HomeGetItemsFilter,
    names: Map<Any, String>,
    libraries: List<Library>,
): String {
    val lib = f.libraryFilter ?: GetItemsFilter()
    val x = lib.extra
    val parts = mutableListOf<String>()
    parts +=
        when {
            f.includeItemTypes.orEmpty().size > 1 -> "Films & shows"
            f.includeItemTypes == listOf(BaseItemKind.SERIES) -> "Shows"
            else -> "Films"
        }
    lib.genres?.mapNotNull { names[it] }?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" / ") }
    lib.tags?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" / ") }
    lib.persons?.mapNotNull { names[it] }?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" & ") }
    lib.studios?.mapNotNull { names[it] }?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" / ") }
    x?.collections?.mapNotNull { names[it] }?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" / ") { n -> n.substringAfter("• ") } }
    lib.decades?.takeIf { it.isNotEmpty() }?.let { parts += it.sorted().joinToString(" / ") { d -> "${d}s" } }
    lib.years?.takeIf { it.isNotEmpty() }?.let { y -> parts += if (y.size == 1) "${y.first()}" else "${y.min()}–${y.max()}" }
    lib.videoTypes?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" / ") { v -> v.readable } }
    when (lib.played) {
        false -> parts += "unwatched"
        true -> parts += "watched"
        null -> {}
    }
    if (lib.favorite == true) parts += "favourites"
    lib.minCommunityRating?.let { parts += "★${it.toInt()}+" }
    x?.minVotes?.let { v -> parts += NbFilterChoices.minVotes.firstOrNull { it.first == v }?.second?.let { "$it votes" } ?: "" }
    x?.minTomatoes?.let { parts += "🍅$it+" }
    x?.minGrossMillions?.let { m -> parts += NbFilterChoices.boxOffice.firstOrNull { it.first == m }?.second ?: "" }
    x?.verdict?.let { parts += it + "s" }
    x?.maxYearRank?.let { parts += if (it == 1) "#1 of their year" else "top $it of their year" }
    x?.streamingRelease?.let { parts += if (it == "only") "streaming films" else "cinema releases" }
    x?.onService?.let { s -> parts += NbFilterChoices.onService.firstOrNull { it.first == s }?.second?.lowercase() ?: "" }
    x?.runtime?.let { r -> parts += NbFilterChoices.runtime.firstOrNull { it.first == r }?.second?.lowercase() ?: "" }
    x?.addedWithinDays?.let { d -> parts += "added " + (NbFilterChoices.addedWithin.firstOrNull { it.first == d }?.second?.lowercase() ?: "") }
    x?.releasedWithinDays?.let { d -> parts += "released " + (NbFilterChoices.releasedWithin.firstOrNull { it.first == d }?.second?.lowercase() ?: "") }
    if (x?.inProgress == true) parts += "in progress"
    if (x?.wantedOnly == true) parts += "from my list"
    libraries.firstOrNull { it.itemId == f.parentId }?.let { parts += "in ${it.name}" }
    val sortName =
        when (f.sort?.sort) {
            ItemSortBy.DATE_CREATED, null -> null
            ItemSortBy.PREMIERE_DATE -> "newest"
            ItemSortBy.COMMUNITY_RATING, ItemSortBy.NAME -> "best rated"
            ItemSortBy.CRITIC_RATING -> "by Tomatoes"
            ItemSortBy.START_DATE -> "by box office"
            ItemSortBy.AIR_TIME -> "by box office (today's money)"
            ItemSortBy.VIDEO_BIT_RATE -> "by budget"
            ItemSortBy.STUDIO -> if (f.sort?.direction == SortOrder.ASCENDING) "biggest flops first" else "best return"
            ItemSortBy.IS_FOLDER -> "by year rank"
            ItemSortBy.RUNTIME -> "by length"
            ItemSortBy.RANDOM -> "shuffled"
            else -> null
        }
    sortName?.let { parts += it }
    return parts.filter { it.isNotBlank() }.joinToString(" · ").take(70)
}

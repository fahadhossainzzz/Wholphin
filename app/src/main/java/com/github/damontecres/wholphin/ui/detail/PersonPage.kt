package com.github.damontecres.wholphin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import com.github.damontecres.wholphin.ui.enableMarquee
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.ItemRowFields
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.LoadingRow
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.RowLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID

@HiltViewModel(assistedFactory = PersonViewModel.Factory::class)
class PersonViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        val navigationManager: NavigationManager,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val seerrService: SeerrService,
        @Assisted val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): PersonViewModel
        }

        private val _state = MutableStateFlow(PersonState())
        val state: StateFlow<PersonState> = _state

        private suspend fun updatePerson(): BaseItem {
            val person =
                api.userLibraryApi
                    .getItem(itemId)
                    .content
                    .let { BaseItem(it) }
            _state.update {
                it.copy(
                    person = DataLoadingState.Success(person),
                )
            }
            return person
        }

        init {
            viewModelScope.launchIO {
                try {
                    val person = updatePerson()
                    val dto = person.data
                    if ((dto.movieCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.MOVIE) { state, row ->
                            state.copy(movies = row)
                        }
                    } else {
                        _state.update { it.copy(movies = RowLoadingState.Success(emptyList())) }
                    }
                    if ((dto.seriesCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.SERIES) { state, row ->
                            state.copy(series = row)
                        }
                    } else {
                        _state.update { it.copy(series = RowLoadingState.Success(emptyList())) }
                    }
                    if ((dto.episodeCount ?: 0) > 0) {
                        fetchRow(person.id, BaseItemKind.EPISODE) { state, row ->
                            state.copy(episodes = row)
                        }
                    } else {
                        _state.update { it.copy(episodes = RowLoadingState.Success(emptyList())) }
                    }
                    // NoBars: their whole career (from our plugin, which keeps it 30 days on the server)
                    viewModelScope.launchIO {
                        try {
                            if (com.github.damontecres.wholphin.services.StreamingStore.owned.isEmpty()) {
                                com.github.damontecres.wholphin.services.StreamingStore.loadLibrary()
                            }
                            val json =
                                dto.providerIds?.get("Tmdb")
                                    ?.let { com.github.damontecres.wholphin.services.ServerAccess.get("/Plugins/ImdbRatings/Person/$it") }
                            val credits = json?.let { com.github.damontecres.wholphin.ui.playback.overlay.parseCredits(it) }.orEmpty()
                            _state.update { it.copy(work = credits, profile = json?.let(::parsePersonProfile)) }
                        } catch (e: Exception) {
                            Timber.w(e, "NoBars: all work unavailable")
                        } finally {
                            _state.update { it.copy(profileReady = true) }
                        }
                    }
                    viewModelScope.launchIO {
                        val results = seerrService.similar(person).orEmpty()
                        _state.update {
                            it.copy(
                                discovered = results,
                                discoverPerson = seerrService.discoverPerson(person),
                            )
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching person %s", itemId)
                    _state.update { it.copy(person = DataLoadingState.Error(ex)) }
                }
            }
        }

        private fun fetchRow(
            itemId: UUID,
            type: BaseItemKind,
            update: (PersonState, RowLoadingState) -> PersonState,
        ) {
            viewModelScope.launchIO {
                _state.update {
                    update.invoke(it, RowLoadingState.Loading)
                }
                try {
                    val request =
                        GetItemsRequest(
                            personIds = listOf(itemId),
                            includeItemTypes = listOf(type),
                            fields = ItemRowFields,
                            recursive = true,
                            sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING),
                        )
                    val pager =
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            viewModelScope,
                            pageSize = 15,
                            useSeriesForPrimary = false,
                        ).init()
                    _state.update {
                        update.invoke(it, RowLoadingState.Success(pager))
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching $type for $itemId")
                    _state.update {
                        update.invoke(it, RowLoadingState.Error(ex))
                    }
                }
            }
        }

        /** NoBars: a title from "All work": yours opens its library page; any other opens its full title page. */
        fun openCredit(c: com.github.damontecres.wholphin.ui.playback.overlay.Credit) {
            viewModelScope.launchIO {
                try {
                    val hex = com.github.damontecres.wholphin.services.StreamingStore.owned[c.ownedKey]
                    if (hex != null && hex.length == 32) {
                        val id = UUID.fromString(hex.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                        val item = BaseItem(api.userLibraryApi.getItem(id).content, false)
                        navigationManager.navigateTo(item.destination())
                        return@launchIO
                    }
                    // any other title: the quick view (its buttons; "View details" for the whole title page)
                    com.github.damontecres.wholphin.ui.components.QuickView.open(c.tmdb, c.tv, c.title, c.posterLarge)
                    return@launchIO
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: could not open %s", c.title)
                }
                _state.update { it.copy(credit = c) }
            }
        }

        fun closeCredit() = _state.update { it.copy(credit = null) }

        fun setFavorite(favorite: Boolean) {
            viewModelScope.launchIO {
                favoriteWatchManager.setFavorite(itemId, favorite)
                updatePerson()
            }
        }
    }

data class PersonState(
    val person: DataLoadingState<BaseItem> = DataLoadingState.Pending,
    val movies: RowLoadingState = RowLoadingState.Pending,
    val series: RowLoadingState = RowLoadingState.Pending,
    val episodes: RowLoadingState = RowLoadingState.Pending,
    val discovered: List<DiscoverItem> = emptyList(),
    val discoverPerson: DiscoverItem? = null,
    val work: List<com.github.damontecres.wholphin.ui.playback.overlay.Credit> = emptyList(),
    val credit: com.github.damontecres.wholphin.ui.playback.overlay.Credit? = null,
    /** NoBars: height, known for, other names and photos (TMDB / Trakt, via our plugin) */
    val profile: PersonProfile? = null,
    /** NoBars: the profile/career answer has come back (or failed), so the header won't change any more */
    val profileReady: Boolean = false,
)

@Composable
fun PersonPage(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PersonViewModel =
        hiltViewModel<PersonViewModel, PersonViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
) {
    val state by viewModel.state.collectAsState()
    when (val st = state.person) {
        is DataLoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Success<BaseItem> -> {
            // NoBars: show the page in one go: wait (at most a second) for the profile, so the extra header lines and
            // the photos don't pop in and push everything down after it appears
            var waited by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1_000)
                waited = true
            }
            if (!state.profileReady && !waited) {
                LoadingPage(modifier)
                return
            }
            val person = st.data
            var showOverviewDialog by remember { mutableStateOf(false) }
            val name = remember(person) { person.name ?: person.id.toString() }
            val imageUrlService = LocalImageUrlService.current
            val imageUrl =
                remember {
                    imageUrlService.getItemImageUrl(
                        itemId = person.id,
                        imageType = ImageType.PRIMARY,
                        tag = person.data.imageTags?.get(ImageType.PRIMARY),
                    )
                }
            // A long filmography is hard to navigate as a row, so it is cut at the same
            // limit as the home page rows and the rest is left to the full grid
            val maxItems = preferences.appPreferences.homePagePreferences.maxItemsPerRow
            val discovered =
                remember(state.discovered, maxItems) { state.discovered.take(maxItems) }

            PersonPageContent(
                preferences = preferences,
                name = name,
                overview = person.data.overview,
                imageUrl = imageUrl,
                birthdate = person.data.premiereDate?.toLocalDate() ?: state.profile?.born,
                deathdate = person.data.endDate?.toLocalDate() ?: state.profile?.died,
                birthPlace = person.data.productionLocations?.firstOrNull() ?: state.profile?.place,
                favorite = person.favorite,
                movies = state.movies,
                series = state.series,
                episodes = state.episodes,
                onClickItem = { index, item ->
                    viewModel.navigationManager.navigateTo(item.destination())
                },
                overviewOnClick = { showOverviewDialog = true },
                favoriteOnClick = {
                    viewModel.setFavorite(!person.favorite)
                },
                discovered = discovered,
                onClickDiscover = { index, item ->
                    viewModel.navigationManager.navigateTo(item.destination)
                },
                onClickViewMoreDiscover = {
                    state.discoverPerson?.let {
                        viewModel.navigationManager.navigateTo(
                            Destination.DiscoveredItem(it, maxItems),
                        )
                    }
                },
                enableViewMoreDiscover = state.discovered.size > discovered.size,
                work = state.work,
                onClickCredit = viewModel::openCredit,
                extraLines = remember(state.profile, state.work) { personExtraLines(state.profile, state.work) },
                photos = state.profile?.photos.orEmpty(),
                modifier = modifier,
            )
            state.credit?.let { c ->
                androidx.compose.ui.window.Dialog(onDismissRequest = viewModel::closeCredit) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .width(520.dp)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .padding(20.dp),
                    ) {
                        com.github.damontecres.wholphin.ui.playback.overlay.CreditSummary(c, viewModel::closeCredit)
                    }
                }
            }
            AnimatedVisibility(showOverviewDialog) {
                ItemDetailsDialog(
                    info =
                        ItemDetailsDialogInfo(
                            title = name,
                            overview = person.data.overview,
                            genres = listOf(),
                            files = listOf(),
                        ),
                    showFilePath = false,
                    onDismissRequest = { showOverviewDialog = false },
                )
            }
        }
    }
}

private const val HEADER_ROW = 0
private const val MOVIE_ROW = 1
private const val SERIES_ROW = 2
private const val EPISODE_ROW = 3
private const val DISCOVER_ROW = EPISODE_ROW + 1
private const val WORK_ROW = DISCOVER_ROW + 1
private const val MINE_ROW = WORK_ROW + 50

@Composable
fun PersonPageContent(
    preferences: UserPreferences,
    name: String,
    overview: String?,
    imageUrl: String?,
    birthdate: LocalDate?,
    deathdate: LocalDate?,
    birthPlace: String?,
    favorite: Boolean,
    movies: RowLoadingState,
    series: RowLoadingState,
    episodes: RowLoadingState,
    discovered: List<DiscoverItem>,
    onClickItem: (Int, BaseItem) -> Unit,
    overviewOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
    onClickViewMoreDiscover: () -> Unit,
    enableViewMoreDiscover: Boolean,
    modifier: Modifier = Modifier,
    work: List<com.github.damontecres.wholphin.ui.playback.overlay.Credit> = emptyList(),
    onClickCredit: (com.github.damontecres.wholphin.ui.playback.overlay.Credit) -> Unit = {},
    extraLines: List<String> = emptyList(),
    photos: List<String> = emptyList(),
    showFavorite: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val headerFocusRequester = remember { FocusRequester() }
    var position by rememberPosition()
    LaunchedEffect(Unit) {
        if (position.row > HEADER_ROW) {
            focusRequester.tryRequestFocus()
        } else {
            headerFocusRequester.tryRequestFocus()
        }
    }
    var focusedOnHeader by remember { mutableStateOf(true) }
    val workRows = remember(work) { careerRows(work) }
    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
    // your Trakt history: checked live when the page opens, and updated at once when you mark something on the TV
    val historyState by com.github.damontecres.wholphin.services.HistoryStore.state.collectAsState()
    val history = historyState.items
    LaunchedEffect(Unit) {
        com.github.damontecres.wholphin.services.HistoryStore.load()
    }
    val pageRows =
        remember(work, wanted, history) {
            val library = libraryRow(work)
            val career = careerRows(work)
            val mine = personalRows(work, wanted, history)
            buildList {
                if (library.isNotEmpty()) add(PageRow("In your library (${library.size})", library, mixed = true))
                career.forEach { add(PageRow(it.title, it.items, it.mixed)) }
                mine.forEach { add(PageRow(it.title, it.items, mixed = true, note = it.note)) }
            }
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // NoBars: always on. With scrolling off while the header had focus, the list couldn't build the next row when
        // it was just below the screen (a tall header, the photo row), so Down from the header did nothing.
        userScrollEnabled = true,
        modifier = modifier,
    ) {
        item {
            PersonHeader(
                name = name,
                overview = overview,
                imageUrl = imageUrl,
                birthdate = birthdate,
                birthPlace = birthPlace,
                deathdate = deathdate,
                favorite = favorite,
                overviewOnClick = overviewOnClick,
                favoriteOnClick = favoriteOnClick,
                extraLines = extraLines,
                showFavorite = showFavorite,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .padding(top = 16.dp, bottom = 40.dp)
                        .focusRequester(headerFocusRequester)
                        .onFocusChanged {
                            focusedOnHeader = it.hasFocus
                        },
            )
        }
        // NoBars: six rows at most, each only when it has something: In your library, Known for, All films, All TV,
        // On your watchlist, Your history. (Discover and the separate Movies / TV Shows / Episodes rows are gone.)
        val rows = pageRows
        if (rows.isEmpty()) {
            // no career from TMDB (a person without a TMDB number): your library's rows, as Jellyfin has them
            item {
                LoadingRow(
                    title = rowTitle(stringResource(R.string.movies_title), movies),
                    state = movies,
                    rowIndex = MOVIE_ROW,
                    position = position,
                    focusRequester = focusRequester,
                    onClickItem = onClickItem,
                    onClickPosition = { position = it },
                    showIfEmpty = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LoadingRow(
                    title = rowTitle(stringResource(R.string.tv_shows_title), series),
                    state = series,
                    rowIndex = SERIES_ROW,
                    position = position,
                    focusRequester = focusRequester,
                    onClickItem = onClickItem,
                    onClickPosition = { position = it },
                    showIfEmpty = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        rows.forEachIndexed { g, row ->
            item(key = "row-${row.title}") {
                WorkRow(
                    title = row.title,
                    work = row.items,
                    showKind = row.mixed,
                    rowIndex = WORK_ROW + g,
                    position = position,
                    focusRequester = focusRequester,
                    onClick = { index, c ->
                        position = RowColumn(WORK_ROW + g, index)
                        onClickCredit(c)
                    },
                    note = row.note,
                    watched = history.keys,
                )
            }
        }
    }
}

private typealias WorkCredit = com.github.damontecres.wholphin.ui.playback.overlay.Credit

/** One career row: its title, its posters, and whether it mixes films and series (then each says which). */
internal class CareerRow(
    val title: String,
    val items: List<WorkCredit>,
    val mixed: Boolean,
)

private val SELF_ROLE = Regex("""^\s*(self|himself|herself|themselves|themself)\b|archive footage""", RegexOption.IGNORE_CASE)

/** The acting part of a role, for line 2: "as Benoit Blanc", "Himself" / "Guest" (as themselves), or nothing. */
private fun actingText(c: WorkCredit): String? {
    val r = c.role.trim()
    if (r.isBlank()) return null
    if (SELF_ROLE.containsMatchIn(r) && !r.contains("archive", ignoreCase = true)) {
        val rest = r.replace(Regex("""^\s*(self|himself|herself|themselves|themself)\s*[-–:,]?\s*""", RegexOption.IGNORE_CASE), "").trim()
        return rest.ifBlank { r.substringBefore(" ").replaceFirstChar { it.uppercase() } }
    }
    return "as $r"
}

/**
 * NoBars: one poster per title, with every part they had in it on line 2 ("as James Bond", "Director · Writer",
 * "as Benoit Blanc · Producer", "Himself"). The result is marked crew so line 2 shows the text as it is.
 */
private fun mergedCareer(work: List<WorkCredit>): List<WorkCredit> =
    work
        .filter { it.poster != null }
        .groupBy { it.ownedKey }
        .map { (_, cs) ->
            val acting = cs.firstOrNull { !it.crew }
            val jobs = cs.filter { it.crew }.flatMap { it.role.split(",", "/") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val base = acting ?: cs.first()
            val parts = listOfNotNull(acting?.let(::actingText)) + jobs
            base.copy(crew = true, role = parts.joinToString(" · "))
        }

/** Titles where they only appeared as themselves (talk shows, award shows, documentaries about others). */
private fun selfOnlyKeys(work: List<WorkCredit>): Set<String> =
    work
        .groupBy { it.ownedKey }
        .filter { (_, cs) -> cs.none { it.crew } && cs.all { SELF_ROLE.containsMatchIn(it.role) } }
        .keys

/**
 * NoBars: the person's career in three rows. Known for: the titles that stand out (at least a quarter of the votes
 * of their most-voted title; 5 to 20; only for a career longer than 12 titles). All films / All TV: everything, any
 * role (acting, as themselves, behind the camera), one poster per title; upcoming first, then newest; in TV, real
 * roles before one- or two-episode guest spots. Titles you have stay in (marked ✓), so the lists are complete.
 */
internal fun careerRows(work: List<WorkCredit>): List<CareerRow> {
    val merged = mergedCareer(work)
    val selfOnly = selfOnlyKeys(work)
    val today = java.time.LocalDate.now().toString()
    val order = compareByDescending<WorkCredit> { it.date.isEmpty() || it.date > today }.thenByDescending { it.date }
    val films = merged.filter { !it.tv }.sortedWith(order)
    val tvAll = merged.filter { it.tv }.sortedWith(order)
    val guest = { c: WorkCredit -> c.ownedKey in selfOnly || (!c.role.contains(" · ") && (c.episodes ?: 3) < 3) }
    val tv = tvAll.filterNot(guest) + tvAll.filter(guest)
    // known for: real work (not talk-show spots), by votes (how many people rated it: fame over years, not this week)
    val main = merged.filter { it.ownedKey !in selfOnly && it.votes > 0 }
    val top = main.maxOfOrNull { it.votes } ?: 0
    val known =
        if (main.size <= 12 || top == 0) {
            emptyList()
        } else {
            val byVotes = main.sortedByDescending { it.votes }
            byVotes.filter { it.votes * 4 >= top }.take(20).let { if (it.size < 5) byVotes.take(5) else it }
        }
    return listOfNotNull(
        CareerRow("Known for", known, mixed = true).takeIf { known.isNotEmpty() },
        CareerRow("All films (${films.size})", films, mixed = false).takeIf { films.isNotEmpty() },
        CareerRow("All TV (${tv.size})", tv, mixed = false).takeIf { tv.isNotEmpty() },
    )
}

/** NoBars: their titles that you have (your library), newest first. */
internal fun libraryRow(work: List<WorkCredit>): List<WorkCredit> {
    val today = java.time.LocalDate.now().toString()
    return mergedCareer(work)
        .filter { com.github.damontecres.wholphin.services.StreamingStore.owned.containsKey(it.ownedKey) }
        .sortedWith(compareByDescending<WorkCredit> { it.date.isEmpty() || it.date > today }.thenByDescending { it.date })
}

/** One row of the person page: its name, posters, whether films and series mix, and an optional line 2. */
internal class PageRow(
    val title: String,
    val items: List<WorkCredit>,
    val mixed: Boolean,
    val note: ((WorkCredit) -> String?)? = null,
)

/** A personal row: its titles and the second line under each poster. */
internal class PersonalRow(
    val title: String,
    val items: List<WorkCredit>,
    val note: (WorkCredit) -> String?,
)

/**
 * NoBars: the person's titles that are yours: on your Watchlist (newest first, as on the list), and in your watch
 * history (your Trakt history; the last time you watched, most recent first).
 */
internal fun personalRows(
    work: List<WorkCredit>,
    wanted: List<com.github.damontecres.wholphin.services.StreamingStore.Entry>,
    history: Map<String, Pair<String, Int>>,
): List<PersonalRow> {
    val titles = mergedCareer(work)
    val onList = wanted.map { (if (it.series) "tv:" else "movie:") + it.tmdb }
    val listed = titles.filter { it.ownedKey in onList }.sortedBy { onList.indexOf(it.ownedKey) }
    val watched = titles.filter { it.ownedKey in history }.sortedByDescending { history[it.ownedKey]?.first.orEmpty() }
    fun role(c: WorkCredit) = c.role.ifBlank { null }
    return listOfNotNull(
        PersonalRow("On your watchlist (${listed.size})", listed, ::role).takeIf { listed.isNotEmpty() },
        PersonalRow("Your history (${watched.size})", watched) { c ->
            history[c.ownedKey]?.let { (date, plays) ->
                val d = runCatching { java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)) }.getOrNull()
                listOfNotNull(d?.let { "Watched $it" } ?: "Watched", if (plays > 1) "${plays}×" else null).joinToString(" · ")
            }
        }.takeIf { watched.isNotEmpty() },
    )
}

/** NoBars: one row of the person's career as posters; ✓ marks what you have, 🔖 what is on your Watchlist. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkRow(
    title: String,
    work: List<WorkCredit>,
    showKind: Boolean,
    rowIndex: Int,
    position: RowColumn,
    focusRequester: FocusRequester,
    onClick: (Int, WorkCredit) -> Unit,
    note: ((WorkCredit) -> String?)? = null,
    watched: Set<String> = emptySet(),
) {
    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
    val shown = work
    // NoBars: IMDb ★ and votes, box office and 🍅 for the band at the bottom of each poster (the whole row at once)
    val factsVersion by com.github.damontecres.wholphin.services.PosterFacts.version.collectAsState()
    LaunchedEffect(work) { com.github.damontecres.wholphin.services.PosterFacts.load(work.map { it.ownedKey }) }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp),
        )
        // NoBars: scrolls by whole posters, so none is ever left half under the menu strip
        val density = androidx.compose.ui.platform.LocalDensity.current
        val spec = remember(density) { with(density) { com.github.damontecres.wholphin.ui.util.WholeItemsScroll(24.dp.toPx(), (120.dp + 16.dp).toPx()) } }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            items(shown.size, key = { shown[it].key }) { index ->
                val c = shown[index]
                val owned = com.github.damontecres.wholphin.services.StreamingStore.owned.containsKey(c.ownedKey)
                val onList = wanted.any { it.tmdb == c.tmdb && it.series == c.tv }
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                // NoBars: stopped on a title outside your library: fetch its card now, so OK opens the quick view complete
                if (!owned) {
                    LaunchedEffect(focused) {
                        if (focused) {
                            kotlinx.coroutines.delay(330)
                            com.github.damontecres.wholphin.services.StreamingStore.prefetch(c.tmdb, c.tv)
                        }
                    }
                }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.width(120.dp),
                ) {
                    androidx.tv.material3.Surface(
                        onClick = { onClick(index, c) },
                        // NoBars: long press: "Mark watched…" (your Trakt history)
                        onLongClick = {
                            if (owned) {
                                com.github.damontecres.wholphin.ui.components.TraktMark.ask(
                                    com.github.damontecres.wholphin.ui.components.TraktMark.Target(c.ownedKey, c.title),
                                )
                            } else {
                                com.github.damontecres.wholphin.ui.components.QuickView.open(c.tmdb, c.tv, c.title, c.posterLarge)
                            }
                        },
                        interactionSource = interaction,
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        modifier =
                            Modifier
                                .width(120.dp)
                                .height(180.dp)
                                .ifElse(
                                    position.row == rowIndex && position.column == index,
                                    Modifier.focusRequester(focusRequester),
                                ),
                    ) {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = c.posterLarge,
                                contentDescription = c.title,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (c.tv && showKind) {
                                Text(
                                    text = "TV",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            // the same band as every other poster: ★ IMDb + votes · box office · 🍅
                            val f = remember(c.ownedKey, factsVersion) { com.github.damontecres.wholphin.services.PosterFacts.get(c.ownedKey) }
                            if (f != null) {
                                com.github.damontecres.wholphin.ui.cards.RatingBand(
                                    stars = f.rating,
                                    votes = f.votes,
                                    money = f.money,
                                    tomatoes = f.rt?.toFloat(),
                                    popcorn = null,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                            val seen = c.ownedKey in watched
                            if (owned || onList || seen) {
                                Text(
                                    // ✓ yours, 🔖 on your watchlist, 👁 you've watched it
                                    text = listOfNotNull(if (owned) "✓" else null, if (onList) "🔖" else null, if (seen) "👁" else null).joinToString(" "),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    // NoBars: like every other poster row: the text slides down when the poster grows (never covered),
                    // centred, and a long title scrolls on the selected poster
                    com.github.damontecres.wholphin.ui.cards.SlidingCardText(focused) {
                    Text(
                        c.title,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().enableMarquee(focused),
                    )
                    // NoBars: plus your copy's size on disk, in light blue, for titles you have
                    val sizes by com.github.damontecres.wholphin.services.SizeStore.sizes
                        .collectAsState()
                    androidx.compose.runtime.LaunchedEffect(Unit) { com.github.damontecres.wholphin.services.SizeStore.refreshSoon() }
                    val mine =
                        com.github.damontecres.wholphin.services.StreamingStore
                            .ownedId(c.ownedKey)
                            ?.let { sizes[it] }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(
                                listOfNotNull(
                                    c.year.ifBlank { "Upcoming" },
                                    if (!showKind) null else if (c.tv) "TV" else "Film",
                                    c.episodes?.let { "$it ep" },
                                    // (the rating is in the band now: IMDb's, as on every other poster)
                                ).joinToString(" · "),
                            )
                            if (mine != null) {
                                append(" · ")
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        color = com.github.damontecres.wholphin.ui.cards.PosterSizeColor,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    ),
                                ) { append(com.github.damontecres.wholphin.services.SizeStore.label(mine, withEpisodes = false)) }
                            }
                        },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                        modifier = Modifier.fillMaxWidth().enableMarquee(focused),
                    )
                    // "Self - Guest" → "Guest"; a plain "Himself" needs no second line
                    val roleLine =
                        when {
                            note != null -> note(c).orEmpty()
                            c.crew -> c.role
                            SELF_ROLE.containsMatchIn(c.role) && !c.role.contains("archive", ignoreCase = true) ->
                                c.role.replace(Regex("""^\s*(self|himself|herself|themselves|themself)\s*[-–:,]?\s*""", RegexOption.IGNORE_CASE), "").trim()
                            else -> if (c.role.isBlank()) "" else "as ${c.role}"
                        }
                    if (roleLine.isNotBlank()) {
                        Text(
                            roleLine,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                            modifier = Modifier.fillMaxWidth().enableMarquee(focused),
                        )
                    }
                    }
                }
            }
        }
        }
    }
}

fun rowTitle(
    prefix: String,
    state: RowLoadingState,
): String =
    if (state is RowLoadingState.Success) {
        "$prefix (${state.items.size})"
    } else {
        prefix
    }

@Composable
fun PersonHeader(
    name: String,
    overview: String?,
    imageUrl: String?,
    birthdate: LocalDate?,
    deathdate: LocalDate?,
    birthPlace: String?,
    favorite: Boolean,
    overviewOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraLines: List<String> = emptyList(),
    showFavorite: Boolean = true,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        modifier = modifier.bringIntoViewRequester(bringIntoViewRequester),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = name,
            modifier =
                Modifier
//                    .fillMaxWidth(.25f)
                    .weight(1f)
                    .clip(RoundedCornerShape(10)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
//                    .fillMaxWidth(.7f)
                    .weight(3f)
                    .padding(top = 16.dp, end = 32.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        RoundedCornerShape(10),
                    ).padding(16.dp),
        ) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.displaySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            birthdate?.let {
                val age = if (deathdate == null) birthdate.until(LocalDate.now())?.years else null
                val text =
                    if (age != null) {
                        stringResource(R.string.born) + ": ${formatDate(it)} (${
                            pluralStringResource(
                                R.plurals.years_old,
                                age,
                                age,
                            )
                        })"
                    } else {
                        stringResource(R.string.born) + ": ${formatDate(it)}"
                    }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (birthPlace.isNotNullOrBlank()) {
                Text(
                    text = stringResource(R.string.birthplace) + ": $birthPlace",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            deathdate?.let {
                val age = birthdate?.until(it)?.years
                val text =
                    if (age != null) {
                        stringResource(R.string.died) + ": ${formatDate(it)} (${
                            pluralStringResource(
                                R.plurals.years_old,
                                age,
                                age,
                            )
                        })"
                    } else {
                        stringResource(R.string.died) + ": ${formatDate(it)}"
                    }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // NoBars: known for, height, other names, years active and counts
            extraLines.forEach { line ->
                Text(
                    text = line,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val interactionSource = remember { MutableInteractionSource() }
            val buttonInteractionSource = remember { MutableInteractionSource() }
            val focused =
                interactionSource.collectIsFocusedAsState().value ||
                    buttonInteractionSource.collectIsFocusedAsState().value
            LaunchedEffect(focused) {
                if (focused) {
                    bringIntoViewRequester.bringIntoView()
                }
            }
            OverviewText(
                overview = overview ?: "",
                maxLines = 3,
                onClick = overviewOnClick,
                interactionSource = interactionSource,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (showFavorite) ExpandableFaButton(
                title = if (favorite) R.string.remove_favorite else R.string.add_favorite,
                iconStringRes = R.string.fa_heart,
                onClick = favoriteOnClick,
                iconColor = if (favorite) Color.Red else Color.Unspecified,
                interactionSource = buttonInteractionSource,
                modifier = Modifier,
            )
        }
    }
}

@PreviewTvSpec
@Composable
private fun PersonPreview() {
    WholphinTheme {
        PersonHeader(
            name = "John Smith",
            overview = "John Smith is an actor",
            imageUrl = null,
            birthdate = LocalDate.of(1975, 3, 22),
            birthPlace = "Phoenix, Arizona, USA",
            deathdate = LocalDate.of(2025, 2, 1),
            overviewOnClick = {},
            favorite = true,
            favoriteOnClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

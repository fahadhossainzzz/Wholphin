package com.github.damontecres.wholphin.ui.detail

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusRequester
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
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.services.WantedStore
import com.github.damontecres.wholphin.services.WatchProviders
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

@HiltViewModel
class StreamingViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val backdropService: com.github.damontecres.wholphin.services.BackdropService,
    ) : ViewModel() {
        init {
            viewModelScope.launch { backdropService.clearBackdrop() }
        }

        /** NoBars: the banner follows the selected poster, as in the library (TMDB's background picture). */
        fun updateBackdrop(item: com.github.damontecres.wholphin.data.model.BaseItem) {
            val url = StreamingStore.entries[item.id]?.backdrop ?: return
            viewModelScope.launch { backdropService.submit(item.id.toString(), url) }
        }
    }

/**
 * NoBars: the Netflix, Prime Video or Theatre page. Rows come from the daily list on the Mac. Titles you have
 * open your own copy; the rest open a side panel (trailer, open in the app, Watchlist). The Watchlist has its own
 * page in the menu (service = WATCHLIST) and is also the last row of all three pages: one list, kept on the
 * Jellyfin server, emptied only by you.
 */
@Composable
fun StreamingPage(
    service: String,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: StreamingViewModel = hiltViewModel(),
) {
    if (service == StreamingStore.WATCHLIST) {
        WatchlistPage(modifier, viewModel)
        return
    }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadedRows by remember(service) { mutableStateOf<List<HomeRowLoadingState>?>(null) }
    var rawRows by remember(service) { mutableStateOf<List<StreamingStore.Row>?>(null) }
    LaunchedEffect(service) {
        WantedStore.load()
        val loaded = StreamingStore.rows(service)
        StreamingStore.rows(StreamingStore.LIST)
        rawRows = loaded
        loadedRows =
            loaded.orEmpty().map { row ->
                HomeRowLoadingState.Success(
                    title = StringStringProvider(row.title),
                    items = row.items.map { StreamingStore.toBaseItem(it, row.title) },
                    showViewMore = false,
                )
            }
    }
    val wanted by WantedStore.items.collectAsState()
    val watched by WantedStore.watched.collectAsState()
    // today's card for each title on the list (the saved copy when it's gone from everywhere)
    var myList by remember(service) { mutableStateOf<List<StreamingStore.Entry>>(emptyList()) }
    LaunchedEffect(wanted, rawRows) {
        myList = wanted.map { StreamingStore.freshFor(it, rawRows) }
    }
    var showGrid by remember { mutableStateOf(false) }
    val rows =
        remember(loadedRows, myList, watched, service) {
            val base = loadedRows
            if (base == null || myList.isEmpty()) {
                base
            } else {
                val inLibrary = myList.count { StreamingStore.libraryId(it) != null }
                val seen = myList.count { e -> StreamingStore.libraryId(e)?.let { it in watched } == true }
                base +
                    HomeRowLoadingState.Success(
                        title =
                            StringStringProvider(
                                "Watchlist · ${myList.size}" +
                                    (if (inLibrary > 0) " · $inLibrary in your library" else "") +
                                    (if (seen > 0) " · $seen watched" else ""),
                            ),
                        items = myList.map { StreamingStore.toBaseItem(it, MY_LIST_ROW) },
                        showViewMore = true,
                    )
            }
        }
    var panel by remember { mutableStateOf<Pair<StreamingStore.Entry, Boolean>?>(null) }
    var position by rememberPosition()
    val toggle = { entry: StreamingStore.Entry ->
        scope.launch {
            val added = WantedStore.toggle(entry, service)
            android.widget.Toast
                .makeText(context, if (added) "Added to your Watchlist" else "Removed from your Watchlist", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        Unit
    }
    val current = rows
    when {
        current == null -> {
            LoadingPage(modifier)
        }

        current.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("The ${StreamingStore.displayName(service)} list isn't ready yet. It's built every morning after 7:30.")
            }
        }

        else -> {
            val onClick = { clicked: RowColumn, item: com.github.damontecres.wholphin.data.model.BaseItem ->
                position = clicked
                val entry = StreamingStore.entries[item.id]
                val libraryId = entry?.let { StreamingStore.libraryId(it) }
                if (entry != null && libraryId != null) {
                    viewModel.navigationManager.navigateTo(
                        Destination.MediaItem(libraryId, if (entry.series) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                    )
                } else if (entry != null) {
                    panel = entry to (myList.isNotEmpty() && clicked.row == current.lastIndex)
                }
                Unit
            }
            HomePageContent(
                homeRows = current,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = onClick,
                // hold OK on any poster to add it to the Watchlist, or take it off
                onLongClickItem = { _, item -> StreamingStore.entries[item.id]?.let { toggle(it) } },
                onClickPlay = onClick,
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = false,
                showViewMore = true,
                onClickViewMore = { _, _ -> showGrid = true },
                modifier = modifier,
            )
        }
    }
    if (showGrid) {
        MyListGrid(
            items = myList,
            watched = watched,
            onOpen = { e ->
                val libraryId = StreamingStore.libraryId(e)
                if (libraryId != null) {
                    showGrid = false
                    viewModel.navigationManager.navigateTo(
                        Destination.MediaItem(libraryId, if (e.series) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                    )
                } else {
                    panel = e to true
                }
            },
            onToggle = { toggle(it) },
            onBack = { showGrid = false },
        )
    }
    panel?.let { (entry, fromList) ->
        StreamingPanel(entry = entry, service = service, fromList = fromList, onToggle = { toggle(entry) }, onBack = { panel = null })
    }
}

private const val MY_LIST_ROW = "my-list"

@Composable
private fun StreamingPanel(
    entry: StreamingStore.Entry,
    service: String,
    fromList: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val name = StreamingStore.displayName(service)
    val first = remember { FocusRequester() }
    LaunchedEffect(entry) { first.tryRequestFocus() }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.let {
                it.setGravity(Gravity.END or Gravity.FILL_VERTICAL)
                it.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.setDimAmount(0.35f)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .width(520.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(horizontal = 22.dp, vertical = 22.dp)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Back || it.key == Key.ButtonB) {
                            if (it.type == KeyEventType.KeyUp) onBack()
                            true
                        } else {
                            false
                        }
                    },
        ) {
            // 1. poster, title, ratings
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = entry.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(96.dp, 144.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val facts =
                        listOfNotNull(
                            entry.year?.toString(),
                            if (entry.series) entry.seasons?.let { "$it season${if (it == 1) "" else "s"}" } else entry.runtime?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                        ).joinToString("  ·  ")
                    if (facts.isNotEmpty()) Text(facts, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        entry.imdbRating?.let { r ->
                            Text(
                                "★ " + String.format(Locale.US, "%.1f", r) + (entry.imdbVotes?.let { " (${com.github.damontecres.wholphin.services.ImdbVotesStore.short(it)} votes)" } ?: ""),
                                fontSize = 15.sp,
                                color = com.github.damontecres.wholphin.ui.components.FilledStarColor,
                            )
                        } ?: entry.tmdbRating?.let { r ->
                            // NoBars: IMDb hasn't rated it yet
                            Text(
                                "TMDB ★ " + String.format(Locale.US, "%.1f", r) + (entry.tmdbVotes?.let { " (${com.github.damontecres.wholphin.services.ImdbVotesStore.short(it)} votes)" } ?: ""),
                                fontSize = 15.sp,
                                color = com.github.damontecres.wholphin.ui.components.FilledStarColor,
                            )
                        }
                        entry.rt?.let { Text("🍅 $it%", fontSize = 15.sp, color = Color.White) }
                            ?: entry.pop?.let { Text("🍿 $it% audience", fontSize = 15.sp, color = Color.White) }
                    }
                    if (entry.genres.isNotEmpty()) Text(entry.genres.joinToString(" · "), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            // 2. the buttons, always on screen (they wrap onto a second line rather than run off the edge):
            //    the same on every page: Trailer · Open in … · Watchlist · Back
            val pageSvc = if (service == StreamingStore.NETFLIX || service == StreamingStore.PRIME) name else null
            val openSvc =
                if (pageSvc != null && !fromList) {
                    pageSvc
                } else {
                    (entry.services + entry.ott.map { it.first }).firstOrNull { WatchProviders.canOpen(it) }
                        ?: entry.from
                            ?.takeIf { (it == StreamingStore.NETFLIX || it == StreamingStore.PRIME) && entry.status?.contains("No longer") != true }
                            ?.let { StreamingStore.displayName(it) }
                }
            val wanted by WantedStore.items.collectAsState()
            val onList = wanted.any { it.tmdb == entry.tmdb && it.series == entry.series }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var firstUsed = false
                fun Modifier.firstOne(): Modifier =
                    if (!firstUsed) {
                        firstUsed = true
                        this.focusRequester(first)
                    } else {
                        this
                    }
                if (entry.trailer != null) {
                    Button(onClick = { openTrailer(context, entry.trailer) }, modifier = Modifier.firstOne()) {
                        Text("▶  Trailer")
                    }
                }
                openSvc?.let { svc ->
                    Button(
                        onClick = { WatchProviders.open(context, WatchProviders.Provider(0, svc, null), entry.title, netflixId = entry.netflixId) },
                        modifier = Modifier.firstOne(),
                    ) { Text("▶  " + (if (svc == pageSvc) "Open in $svc" else shortService(svc))) }
                }
                Button(onClick = onToggle, modifier = Modifier.firstOne()) {
                    Text(if (onList) "🔖  On Watchlist" else "＋  Watchlist")
                }
                Button(onClick = onBack) { Text("Back") }
            }
            // 3. everything else, scrolled with ▼ / ▲ (▲ at the top goes back to the buttons)
            val scroll = androidx.compose.foundation.rememberScrollState()
            val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
            var detailsFocused by remember { mutableStateOf(false) }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged { detailsFocused = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.DirectionDown -> {
                                    scrollScope.launch { scroll.animateScrollTo((scroll.value + 260).coerceAtMost(scroll.maxValue)) }
                                    true
                                }

                                Key.DirectionUp -> {
                                    if (scroll.value > 0) {
                                        scrollScope.launch { scroll.animateScrollTo((scroll.value - 260).coerceAtLeast(0)) }
                                        true
                                    } else {
                                        false
                                    }
                                }

                                else -> {
                                    false
                                }
                            }
                        }.focusable()
                        .background(
                            if (detailsFocused) Color.White.copy(alpha = 0.06f) else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        ),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(scroll).padding(6.dp),
                ) {
                    entry.status?.let {
                        Text(it, fontSize = 15.sp, color = if (it.contains("No longer")) Color(0xFFEF9A9A) else Color(0xFFA5D6A7))
                    }
                    entry.note?.takeIf { it.startsWith("#") }?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFAB91)) }
                    entry.facts?.let { Text(it, fontSize = 14.sp, color = Color(0xFFA5D6A7)) }
                    if (entry.gross != null || entry.budget != null) {
                        com.github.damontecres.wholphin.services.BoxOffice
                            .line(
                                com.github.damontecres.wholphin.services.BoxOffice
                                    .Money(entry.gross, entry.budget, entry.year, entry.grossToday, grossLocal = entry.grossLocal),
                            )?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }
                    }
                    entry.streamOn?.let { Text("Streaming on  $it", fontSize = 14.sp, color = Color(0xFFFFE082)) }
                    entry.freeOn?.let { Text("Free on  $it", fontSize = 14.sp, color = Color(0xFFFFCC80)) }
                    entry.rentBuy?.let { Text("Rent or buy  $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
                    entry.date?.let { Text(it, fontSize = 14.sp, color = Color(0xFF90CAF9)) }
                    entry.note?.takeIf { !it.startsWith("#") }?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }
                    entry.cast?.let { Text("With $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
                    if (entry.overview.isNotBlank()) Text(entry.overview, fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f))
                    entry.added?.let { added ->
                        val page = entry.from?.let { " from the ${StreamingStore.displayName(it)} page" } ?: ""
                        Text("On your Watchlist since ${shortDate(added)}$page", fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                    }
                }
                if (scroll.maxValue > 0 && scroll.value < scroll.maxValue) {
                    Text(
                        "▼ more",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** NoBars: short platform names for buttons ("Prime Video", not "Amazon Prime Video") */
private fun shortService(name: String): String = name.removePrefix("Amazon ").removeSuffix(" Plus").take(22)

/** NoBars: plays a YouTube trailer in the TV's YouTube app. The TV app wants a normal watch link addressed to it by
 * name (a "vnd.youtube:" link just opens its home screen). */
private fun openTrailer(
    context: android.content.Context,
    key: String,
) {
    val watch = android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
    val tries =
        listOf(
            android.content.Intent(android.content.Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv"),
            android.content.Intent(android.content.Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube"),
            android.content.Intent(android.content.Intent.ACTION_VIEW, watch),
        )
    for (intent in tries) {
        try {
            context.startActivity(
                intent
                    .putExtra("force_fullscreen", true)
                    .putExtra("finish_on_ended", true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (e: Exception) {
            timber.log.Timber.w(e, "NoBars: trailer hand-off failed")
        }
    }
    android.widget.Toast.makeText(context, "Couldn't open YouTube for this trailer", android.widget.Toast.LENGTH_LONG).show()
}

/** "2026-11-10" -> "10 Nov 2026" */
private fun shortDate(iso: String): String =
    try {
        java.time.LocalDate
            .parse(iso)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
    } catch (_: Exception) {
        iso
    }

private enum class ListSort(
    val label: String,
) {
    ADDED("Newest added"),
    RATING("Best rated"),
    SHORT("Shortest first"),
}

private enum class ListFilter(
    val label: String,
) {
    ALL("All"),
    FILMS("Films"),
    SHOWS("Shows"),
    NETFLIX("Netflix"),
    PRIME("Prime Video"),
    LIBRARY("In my library"),
    NOT_WATCHED("Not watched"),
}

/** NoBars: the whole Watchlist as a grid, with a sort and a filter. OK opens a title, hold OK takes it off the list.
 * A page of its own from the menu, or a full-screen view from a page's Watchlist row ("View more"). */
@Composable
private fun MyListGrid(
    items: List<StreamingStore.Entry>,
    watched: Set<java.util.UUID>,
    onOpen: (StreamingStore.Entry) -> Unit,
    onToggle: (StreamingStore.Entry) -> Unit,
    onBack: () -> Unit,
    asDialog: Boolean = true,
) {
    var sort by remember { mutableStateOf(ListSort.ADDED) }
    var filter by remember { mutableStateOf(ListFilter.ALL) }
    val shown =
        remember(items, watched, sort, filter) {
            val kept =
                items.filter { e ->
                    val lib = StreamingStore.libraryId(e)
                    val on = e.services + e.ott.map { it.first }
                    when (filter) {
                        ListFilter.ALL -> true
                        ListFilter.FILMS -> !e.series
                        ListFilter.SHOWS -> e.series
                        ListFilter.NETFLIX -> on.any { it.startsWith("Netflix") } || e.status?.contains("On Netflix") == true
                        ListFilter.PRIME -> on.any { it.contains("Prime") } || e.status?.contains("On Prime") == true
                        ListFilter.LIBRARY -> lib != null
                        ListFilter.NOT_WATCHED -> lib == null || lib !in watched
                    }
                }
            when (sort) {
                ListSort.ADDED -> kept
                ListSort.RATING -> kept.sortedByDescending { it.imdbRating ?: it.tmdbRating ?: 0f }
                ListSort.SHORT -> kept.sortedBy { if (it.series) Int.MAX_VALUE else it.runtime ?: Int.MAX_VALUE - 1 }
            }
        }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstButton.tryRequestFocus() }
    val content: @Composable () -> Unit = {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .ifElse(asDialog, Modifier.background(Color(0xF2101010)))
                    .padding(horizontal = 40.dp, vertical = 24.dp),
        ) {
            Text(
                "🔖  Watchlist · ${shown.size}" + if (shown.size != items.size) " of ${items.size}" else "",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                ListSort.entries.forEachIndexed { i, s ->
                    Button(
                        onClick = { sort = s },
                        modifier = Modifier.ifElse(i == 0, Modifier.focusRequester(firstButton)),
                    ) { Text((if (s == sort) "● " else "") + s.label, fontSize = 13.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                ListFilter.entries.forEach { f ->
                    Button(onClick = { filter = f }) { Text((if (f == filter) "● " else "") + f.label, fontSize = 13.sp) }
                }
            }
            if (shown.isEmpty()) {
                Text("Nothing here with this filter.", color = Color.White.copy(alpha = 0.7f))
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns =
                    androidx.compose.foundation.lazy.grid.GridCells
                        .Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(shown.size, key = { i -> "${shown[i].series}-${shown[i].tmdb}" }) { i ->
                    val e = shown[i]
                    val lib = StreamingStore.libraryId(e)
                    val label =
                        when {
                            lib != null && lib in watched -> "Watched ✓"
                            lib != null -> "✓"
                            e.badge == "Gone" -> "Gone"
                            else -> null
                        }
                    androidx.tv.material3.Card(
                        onClick = { onOpen(e) },
                        onLongClick = { onToggle(e) },
                    ) {
                        Box {
                            AsyncImage(
                                model = e.poster,
                                contentDescription = e.title,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .androidxAspect()
                                        .background(Color.White.copy(alpha = 0.08f)),
                            )
                            label?.let {
                                Text(
                                    it,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .background(
                                                if (it == "Gone") Color(0xFF616161) else Color(0xFF2E7D32),
                                                RoundedCornerShape(4.dp),
                                            ).padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                Text(
                    "Your Watchlist is empty. On the Netflix, Prime Video or Theatre page, hold OK on a poster " +
                        "(or press ＋ Watchlist in its panel) to save it here.",
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Text(
                "OK opens a title · hold OK to take it off your Watchlist" + if (asDialog) " · Back to close" else "",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
    if (asDialog) {
        Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
    } else {
        content()
    }
}

/** NoBars: the Watchlist page in the menu: everything you've saved from Netflix, Prime Video and Theatre. */
@Composable
private fun WatchlistPage(
    modifier: Modifier,
    viewModel: StreamingViewModel,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        WantedStore.load()
        StreamingStore.rows(StreamingStore.LIST)
        ready = true
    }
    val wanted by WantedStore.items.collectAsState()
    val watched by WantedStore.watched.collectAsState()
    var list by remember { mutableStateOf<List<StreamingStore.Entry>>(emptyList()) }
    LaunchedEffect(wanted, ready) { if (ready) list = wanted.map { StreamingStore.freshFor(it, null) } }
    var panel by remember { mutableStateOf<StreamingStore.Entry?>(null) }
    val toggle = { e: StreamingStore.Entry ->
        scope.launch {
            val added = WantedStore.toggle(e)
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
        MyListGrid(
            items = list,
            watched = watched,
            onOpen = { e ->
                val libraryId = StreamingStore.libraryId(e)
                if (libraryId != null) {
                    viewModel.navigationManager.navigateTo(
                        Destination.MediaItem(libraryId, if (e.series) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                    )
                } else {
                    panel = e
                }
            },
            onToggle = { toggle(it) },
            onBack = {},
            asDialog = false,
        )
    }
    panel?.let { entry ->
        StreamingPanel(entry = entry, service = StreamingStore.WATCHLIST, fromList = true, onToggle = { toggle(entry) }, onBack = { panel = null })
    }
}

private fun Modifier.androidxAspect(): Modifier = this.then(Modifier.aspectRatio(2f / 3f))

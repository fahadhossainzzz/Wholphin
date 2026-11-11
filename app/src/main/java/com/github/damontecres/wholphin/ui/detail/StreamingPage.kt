package com.github.damontecres.wholphin.ui.detail

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
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
    val pageFocus = remember { FocusRequester() }
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
    Box(Modifier.fillMaxSize()) {
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
                // NoBars: always the quick-look panel; for a title you own it leads with Play / Resume and Details
                StreamingStore.entries[item.id]?.let { entry ->
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
                // "View more" on the Watchlist row: the Watchlist page (tabs, sort and filter, like Movies)
                onClickViewMore = { _, _ -> viewModel.navigationManager.navigateTo(Destination.Streaming(StreamingStore.WATCHLIST)) },
                // remembers the last poster with the focus, so closing the panel can hand it back
                modifier = modifier.focusRequester(pageFocus).focusRestorer(),
            )
        }
    }
    panel?.let { (entry, fromList) ->
        StreamingPanel(
            entry = entry,
            service = service,
            fromList = fromList,
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

private const val MY_LIST_ROW = "my-list"

@Composable
private fun StreamingPanel(
    entry: StreamingStore.Entry,
    service: String,
    fromList: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    onRestoreFocus: () -> Boolean = { false },
) {
    val context = LocalContext.current
    // NoBars: your own copy, when you have one: resume point, next episode, what the file is
    val libraryId = remember(entry) { StreamingStore.libraryId(entry) }
    var owned by remember(entry) { mutableStateOf<OwnedCopy?>(null) }
    LaunchedEffect(libraryId) {
        if (libraryId != null) {
            kotlinx.coroutines.delay(240) // after the slide-in, so it never holds up the animation
            owned = loadOwnedCopy(libraryId, entry.series)
        }
    }
    val name = StreamingStore.displayName(service)
    val first = remember { FocusRequester() }
    LaunchedEffect(entry) {
        // keep trying for a moment until the first button is ready, so the remote works inside the panel at once
        repeat(20) {
            kotlinx.coroutines.delay(30)
            if (first.tryRequestFocus()) return@LaunchedEffect
        }
    }
    // NoBars: drawn inside the page (not a pop-up window that is first shown, then moved): it slides in from the
    // right already in place, over a dimmed page, and slides out on Back
    val panelScope = androidx.compose.runtime.rememberCoroutineScope()
    var shown by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(entry) { shown = true }
    // NoBars: on close, hand the focus back to the poster you came from *before* the panel disappears; otherwise the
    // focus has nowhere to go, falls to the main menu on the left, and the menu opens by itself
    var letFocusGo by remember(entry) { mutableStateOf(false) }
    val close = {
        letFocusGo = true
        onRestoreFocus()
        panelScope.launch {
            shown = false
            kotlinx.coroutines.delay(170)
            onBack()
        }
        Unit
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        androidx.compose.animation.AnimatedVisibility(
            visible = shown,
            enter =
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit =
                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(170)) { it } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .width(520.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .padding(horizontal = 22.dp, vertical = 22.dp)
                    .focusGroup()
                    .focusProperties { onExit = { if (!letFocusGo) cancelFocusChange() } }
                    .onPreviewKeyEvent {
                        if (it.key == Key.Back || it.key == Key.ButtonB) {
                            if (it.type == KeyEventType.KeyUp) close()
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
                    // ratings as small coloured chips
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val votes = { v: Int? -> v?.let { " · ${com.github.damontecres.wholphin.services.ImdbVotesStore.short(it)}" } ?: "" }
                        entry.imdbRating?.let { r -> Chip("IMDb " + String.format(Locale.US, "%.1f", r) + votes(entry.imdbVotes), Color(0xFFF5C518), Color.Black) }
                            ?: entry.tmdbRating?.let { r -> Chip("TMDB " + String.format(Locale.US, "%.1f", r) + votes(entry.tmdbVotes), Color(0xFF01B4E4), Color.Black) }
                        entry.rt?.let { Chip("🍅 $it%", Color(0xFFFA320A).copy(alpha = 0.85f), Color.White) }
                            ?: entry.pop?.let { Chip("🍿 $it% audience", Color.White.copy(alpha = 0.15f), Color.White) }
                    }
                    if (entry.genres.isNotEmpty()) Text(entry.genres.joinToString(" · "), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            owned?.let { o ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (o.progress in 0.01f..0.97f) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(o.progress).fillMaxHeight().background(Color(0xFFB39DDB), RoundedCornerShape(2.dp)))
                        }
                    }
                    Text(
                        listOfNotNull("In your library", o.facts, if (o.played) "watched ✓" else null).joinToString("  ·  "),
                        fontSize = 13.sp,
                        color = Color(0xFFA5D6A7),
                    )
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
                if (libraryId != null) {
                    val o = owned
                    val kind = if (entry.series) BaseItemKind.SERIES else BaseItemKind.MOVIE
                    // one button whose label follows your copy once it's loaded (so the focus stays on it)
                    Button(
                        onClick = {
                            val id = o?.playId
                            onNavigate(if (id != null) Destination.Playback(id, o.resumeMs) else Destination.MediaItem(libraryId, kind))
                        },
                        modifier = Modifier.firstOne(),
                    ) { Text("▶  " + (o?.playLabel ?: if (entry.series) "Episodes" else "Play")) }
                    Button(onClick = { onNavigate(Destination.MediaItem(libraryId, kind)) }) { Text("Details") }
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
                Button(onClick = close) { Text("Back") }
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
                    // the journey: build-up, cinemas, home release, charts, and you
                    val wantedNow by WantedStore.items.collectAsState()
                    val watchedNow by WantedStore.watched.collectAsState()
                    val steps =
                        remember(entry, wantedNow, watchedNow) {
                            val list = entry.journey.toMutableList()
                            val added = entry.added ?: wantedNow.firstOrNull { it.tmdb == entry.tmdb && it.series == entry.series }?.added
                            added?.let { list += StreamingStore.JourneyStep("you", "Added to your Watchlist", it, entry.from?.let { f -> "from ${StreamingStore.displayName(f)}" }) }
                            val lib = StreamingStore.libraryId(entry)
                            if (lib != null && lib in watchedNow && list.none { it.title == "Watched" }) {
                                list += StreamingStore.JourneyStep("you", "Watched", null, null)
                            }
                            val undated = list.filter { it.date == null && it.kind != "you" }
                            val dated = list.filter { it.date != null }
                            val known = dated.filter { !it.by && it.kind != "you" }.mapNotNull { it.date }.maxOrNull()
                            undated +
                                dated.sortedBy { if (it.by && known != null) known else it.date } +
                                list.filter { it.date == null && it.kind == "you" }
                        }
                    if (steps.isNotEmpty()) {
                        SectionLabel("Journey")
                        JourneyTimeline(steps)
                    } else {
                        entry.date?.let { Text(it, fontSize = 14.sp, color = Color(0xFF90CAF9)) }
                    }
                    if (entry.gross != null || entry.budget != null) {
                        com.github.damontecres.wholphin.services.BoxOffice
                            .line(
                                com.github.damontecres.wholphin.services.BoxOffice
                                    .Money(entry.gross, entry.budget, entry.year, entry.grossToday, grossLocal = entry.grossLocal),
                            )?.let {
                                SectionLabel("Box office")
                                Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                            }
                    }
                    if (entry.streamOn != null || entry.freeOn != null || entry.rentBuy != null) {
                        SectionLabel("Where to watch")
                        entry.streamOn?.let { Text("Streaming  $it", fontSize = 14.sp, color = Color(0xFFFFE082)) }
                        entry.freeOn?.let { Text("Free  $it", fontSize = 14.sp, color = Color(0xFFFFCC80)) }
                        entry.rentBuy?.let { Text("Rent or buy  $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
                    }
                    entry.note?.takeIf { !it.startsWith("#") }?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }
                    if (entry.cast != null || entry.overview.isNotBlank()) SectionLabel("Story")
                    entry.cast?.let { Text("With $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
                    if (entry.overview.isNotBlank()) Text(entry.overview, fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f))
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
    val pageFocus = remember { FocusRequester() }
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
    val open = { item: com.github.damontecres.wholphin.data.model.BaseItem ->
        StreamingStore.entries[item.id]?.let { panel = it }
        Unit
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
            val tabs =
                remember {
                    listOf(
                        com.github.damontecres.wholphin.ui.components.TabDetails(StringStringProvider("Overview")),
                        com.github.damontecres.wholphin.ui.components.TabDetails(StringStringProvider("All")),
                        com.github.damontecres.wholphin.ui.components.TabDetails(StringStringProvider("Genres")),
                    )
                }
            var showTabs by remember { mutableStateOf(true) }
            com.github.damontecres.wholphin.ui.components.TabbedPage(
                itemId = "nobars-watchlist",
                tabs = tabs,
                showTabs = showTabs,
            ) { tabIndex, tab ->
                val content =
                    Modifier
                        .fillMaxSize()
                        .focusRequester(tab.contentFocusRequester)
                        .focusRequester(pageFocus)
                        .focusRestorer()
                when (tabIndex) {
                    0 -> WatchlistRows(overviewRows(list, watched), viewModel, open, toggle, { showTabs = it }, content)
                    1 -> WatchlistAll(list, watched, viewModel, open, toggle, { showTabs = it }, content)
                    else -> WatchlistRows(genreRows(list), viewModel, open, toggle, { showTabs = it }, content)
                }
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

private fun overviewRows(
    list: List<StreamingStore.Entry>,
    watched: Set<java.util.UUID>,
): List<Pair<String, List<StreamingStore.Entry>>> {
    fun seen(e: StreamingStore.Entry) = StreamingStore.libraryId(e)?.let { it in watched } == true
    val unseen = list.filterNot(::seen)
    return listOf(
        "Recently added" to list,
        "Films" to unseen.filter { !it.series },
        "Shows" to unseen.filter { it.series },
        "On Netflix" to unseen.filter { "netflix" in whereOf(it) },
        "On Prime Video" to unseen.filter { "prime" in whereOf(it) },
        "Streaming elsewhere" to unseen.filter { "elsewhere" in whereOf(it) },
        "In cinemas or coming soon" to unseen.filter { "cinemas" in whereOf(it) || "notyet" in whereOf(it) },
        "In your library" to unseen.filter { "library" in whereOf(it) },
        "Best rated" to unseen.filter { (it.imdbRating ?: it.tmdbRating ?: 0f) >= 7f }.sortedByDescending { it.imdbRating ?: it.tmdbRating ?: 0f },
        "Short ones · under 1 h 45" to unseen.filter { !it.series && (it.runtime ?: 999) < 105 },
        "Watched" to list.filter(::seen),
    ).filter { it.second.isNotEmpty() }
}

private fun genreRows(list: List<StreamingStore.Entry>): List<Pair<String, List<StreamingStore.Entry>>> =
    list
        .flatMap { e -> e.genres.map { it to e } }
        .groupBy({ it.first }, { it.second })
        .toList()
        .sortedByDescending { it.second.size }
        .map { (g, items) -> "$g · ${items.size}" to items }

/** Rows of posters with the browsing header, like the other pages. */
@Composable
private fun WatchlistRows(
    rows: List<Pair<String, List<StreamingStore.Entry>>>,
    viewModel: StreamingViewModel,
    onOpen: (com.github.damontecres.wholphin.data.model.BaseItem) -> Unit,
    onToggle: (StreamingStore.Entry) -> Unit,
    onShowTabs: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val homeRows =
        remember(rows) {
            rows.map { (title, items) ->
                HomeRowLoadingState.Success(
                    title = StringStringProvider(title),
                    items = items.map { StreamingStore.toBaseItem(it, "$MY_LIST_ROW-$title") },
                    showViewMore = false,
                )
            }
        }
    var position by rememberPosition()
    HomePageContent(
        homeRows = homeRows,
        position = position,
        onFocusPosition = {
            position = it
            onShowTabs(it.row < 1)
        },
        onClickItem = { p, item ->
            position = p
            onOpen(item)
        },
        onLongClickItem = { _, item -> StreamingStore.entries[item.id]?.let(onToggle) },
        onClickPlay = { _, item -> onOpen(item) },
        showClock = false,
        onUpdateBackdrop = viewModel::updateBackdrop,
        showLogo = false,
        showViewMore = false,
        takeFocus = false,
        modifier = modifier,
    )
}

/** Everything in one grid, with the same Sort and Filter buttons as the Movies library. */
@Composable
private fun WatchlistAll(
    list: List<StreamingStore.Entry>,
    watched: Set<java.util.UUID>,
    viewModel: StreamingViewModel,
    onOpen: (com.github.damontecres.wholphin.data.model.BaseItem) -> Unit,
    onToggle: (StreamingStore.Entry) -> Unit,
    onShowTabs: (Boolean) -> Unit,
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
                com.github.damontecres.wholphin.data.filter.WatchlistTypeFilter,
                com.github.damontecres.wholphin.data.filter.WatchlistWhereFilter,
                com.github.damontecres.wholphin.data.filter.WatchlistGenreFilter,
                com.github.damontecres.wholphin.data.filter.PlayedFilter,
                com.github.damontecres.wholphin.data.filter.YearFilter,
                com.github.damontecres.wholphin.data.filter.DecadeFilter,
                com.github.damontecres.wholphin.data.filter.CommunityRatingFilter,
            )
        }
    var sort by remember {
        mutableStateOf(
            com.github.damontecres.wholphin.ui.data.SortAndDirection(
                org.jellyfin.sdk.model.api.ItemSortBy.DATE_CREATED,
                org.jellyfin.sdk.model.api.SortOrder.DESCENDING,
            ),
        )
    }
    var filter by remember { mutableStateOf(com.github.damontecres.wholphin.data.model.GetItemsFilter()) }
    val shown =
        remember(list, watched, sort, filter) {
            val x = filter.extra
            val kept =
                list.filter { e ->
                    val lib = StreamingStore.libraryId(e)
                    val year = e.year
                    (x?.wlTypes.isNullOrEmpty() || (if (e.series) "shows" else "films") in x!!.wlTypes!!) &&
                        (x?.wlWhere.isNullOrEmpty() || whereOf(e).any { it in x!!.wlWhere!! }) &&
                        (x?.wlGenres.isNullOrEmpty() || e.genres.any { it in x!!.wlGenres!! }) &&
                        (filter.played == null || (lib != null && lib in watched) == filter.played) &&
                        (filter.years.isNullOrEmpty() || (year != null && year in filter.years!!)) &&
                        (filter.decades.isNullOrEmpty() || (year != null && (year / 10) * 10 in filter.decades!!)) &&
                        (filter.minCommunityRating == null || (e.imdbRating ?: e.tmdbRating ?: 0f) >= filter.minCommunityRating!!)
                }
            val ordered =
                when (sort.sort) {
                    org.jellyfin.sdk.model.api.ItemSortBy.SORT_NAME -> kept.sortedBy { it.title.lowercase(Locale.US).removePrefix("the ") }
                    org.jellyfin.sdk.model.api.ItemSortBy.COMMUNITY_RATING -> kept.sortedBy { it.imdbRating ?: it.tmdbRating ?: 0f }
                    org.jellyfin.sdk.model.api.ItemSortBy.PREMIERE_DATE -> kept.sortedBy(::releaseDate)
                    org.jellyfin.sdk.model.api.ItemSortBy.RUNTIME -> kept.sortedBy { if (it.series) Int.MAX_VALUE else it.runtime ?: Int.MAX_VALUE - 1 }
                    else -> kept.reversed() // the list is kept newest first: ascending = oldest first
                }
            val directed = if (sort.direction == org.jellyfin.sdk.model.api.SortOrder.DESCENDING) ordered.reversed() else ordered
            directed.map { StreamingStore.toBaseItem(it, MY_LIST_ROW) }
        }
    val possible = { option: com.github.damontecres.wholphin.data.filter.ItemFilterBy<*> ->
        when (option) {
            com.github.damontecres.wholphin.data.filter.WatchlistTypeFilter ->
                listOf(
                    com.github.damontecres.wholphin.data.filter.FilterValueOption("Films", "films"),
                    com.github.damontecres.wholphin.data.filter.FilterValueOption("Shows", "shows"),
                )
            com.github.damontecres.wholphin.data.filter.WatchlistWhereFilter ->
                WHERE_NAMES.map { com.github.damontecres.wholphin.data.filter.FilterValueOption(it.second, it.first) }
            com.github.damontecres.wholphin.data.filter.WatchlistGenreFilter ->
                list.flatMap { it.genres }.distinct().sorted().map { com.github.damontecres.wholphin.data.filter.FilterValueOption(it, it) }
            com.github.damontecres.wholphin.data.filter.PlayedFilter ->
                listOf(
                    com.github.damontecres.wholphin.data.filter.FilterValueOption("True", null),
                    com.github.damontecres.wholphin.data.filter.FilterValueOption("False", null),
                )
            com.github.damontecres.wholphin.data.filter.YearFilter ->
                list.mapNotNull { it.year }.distinct().sorted().map { com.github.damontecres.wholphin.data.filter.FilterValueOption("$it", it) }
            com.github.damontecres.wholphin.data.filter.DecadeFilter ->
                list.mapNotNull { it.year?.div(10)?.times(10) }.distinct().sorted().map { com.github.damontecres.wholphin.data.filter.FilterValueOption("${it}s", it) }
            com.github.damontecres.wholphin.data.filter.CommunityRatingFilter ->
                (1..9).map { com.github.damontecres.wholphin.data.filter.FilterValueOption("$it", it) }
            else -> emptyList()
        }
    }
    var focused by remember { mutableStateOf<com.github.damontecres.wholphin.data.model.BaseItem?>(null) }
    val gridFocus = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(modifier = modifier) {
        com.github.damontecres.wholphin.ui.components.CollectionFolderHeader(
            listIsNotEmpty = shown.isNotEmpty(),
            showHeader = true,
            showTitle = false,
            playEnabled = false,
            title = "Watchlist",
            sortAndDirection = sort,
            onSortChange = { sort = it },
            sortOptions = sortOptions,
            getPossibleFilterValues = { possible(it) },
            onClickShowViewOptions = {},
            onClickPlayAll = {},
            onClickRandom = { shown.randomOrNull()?.let(onOpen) },
            currentFilter = filter,
            filterOptions = filterOptions,
            onFilterChange = { filter = it },
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        com.github.damontecres.wholphin.ui.main.HomePageHeader(
            item = focused,
            showLogo = false,
            modifier = Modifier.fillMaxWidth().height(170.dp).padding(start = 16.dp, top = 28.dp),
        )
        if (shown.isEmpty()) {
            Text("Nothing on your Watchlist matches this filter.", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
        }
        com.github.damontecres.wholphin.ui.detail.CardGrid(
            pager = shown,
            onClickItem = { _, item -> onOpen(item) },
            onLongClickItem = { _, item -> StreamingStore.entries[item.id]?.let(onToggle) },
            onClickPlay = { _, item -> onOpen(item) },
            letterPosition = { c -> shown.indexOfFirst { it.name?.uppercase(Locale.US)?.removePrefix("THE ")?.startsWith(c) == true }.coerceAtLeast(0) },
            gridFocusRequester = gridFocus,
            showJumpButtons = false,
            showLetterButtons = false,
            modifier = Modifier.fillMaxSize(),
            positionCallback = { columns, pos ->
                onShowTabs(pos < columns)
                focused = shown.getOrNull(pos)
                shown.getOrNull(pos)?.let(viewModel::updateBackdrop)
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Chip(
    text: String,
    background: Color,
    textColor: Color,
) {
    Text(
        text,
        fontSize = 13.sp,
        color = textColor,
        modifier = Modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun stepColor(kind: String): Color =
    when (kind) {
        "cinema" -> Color(0xFF64B5F6)
        "home" -> Color(0xFF81C784)
        "chart" -> Color(0xFFFFB74D)
        "you" -> Color(0xFFB39DDB)
        else -> Color(0xFF9E9E9E)
    }

/** NoBars: the vertical timeline: a dot per step on one line (hollow dots are still to come), date and detail beside it. */
@Composable
private fun JourneyTimeline(steps: List<StreamingStore.JourneyStep>) {
    val today = remember { java.time.LocalDate.now().toString() }
    Column {
        steps.forEachIndexed { i, step ->
            val future = step.date != null && step.date > today
            val color = stepColor(step.kind)
            Row(modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(modifier = Modifier.width(22.dp).fillMaxHeight()) {
                    // the line through the dots
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (i == 0) 9.dp else 0.dp, bottom = if (i == steps.lastIndex) 0.dp else 0.dp)
                            .width(2.dp)
                            .fillMaxHeight(if (i == steps.lastIndex) 0.25f else 1f)
                            .background(Color.White.copy(alpha = 0.18f)),
                    )
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .align(Alignment.TopCenter)
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (future) Color.Black else color)
                            .border(2.dp, color, androidx.compose.foundation.shape.CircleShape),
                    )
                }
                Column(modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)) {
                    val whenText =
                        step.date?.let { d ->
                            val text = shortDate(d)
                            when {
                                step.by -> "by $text"
                                future -> {
                                    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(d))
                                    "$text · in $days day${if (days == 1L) "" else "s"}"
                                }
                                else -> text
                            }
                        }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                            append(step.title)
                            pop()
                            whenText?.let {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
                                append("  ·  $it")
                                pop()
                            }
                        },
                        fontSize = 14.sp,
                    )
                    step.detail?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f)) }
                    if (step.weekly.size >= 2) {
                        val max = step.weekly.max().coerceAtLeast(1)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(top = 4.dp).height(30.dp),
                        ) {
                            step.weekly.forEach { w ->
                                Box(
                                    Modifier
                                        .width(12.dp)
                                        .fillMaxHeight((w.toFloat() / max).coerceIn(0.06f, 1f))
                                        .background(color, RoundedCornerShape(2.dp)),
                                )
                            }
                            Text(
                                "  week by week · last " + com.github.damontecres.wholphin.services.BoxOffice.short(step.weekly.last()),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}



/** NoBars: what the panel needs about your own copy. [playId]: the film, or the next episode of a show. */
private data class OwnedCopy(
    val playId: java.util.UUID?,
    val playLabel: String,
    val resumeMs: Long,
    val progress: Float,
    val played: Boolean,
    val facts: String?,
)

private suspend fun loadOwnedCopy(
    id: java.util.UUID,
    series: Boolean,
): OwnedCopy? =
    kotlinx.coroutines.withContext(com.github.damontecres.wholphin.util.WholphinDispatchers.IO) {
        try {
            val user = com.github.damontecres.wholphin.services.ServerAccess.userId ?: return@withContext null
            val hex = id.toString().replace("-", "")
            if (series) {
                val show = org.json.JSONObject(com.github.damontecres.wholphin.services.ServerAccess.get("/Users/$user/Items/$hex") ?: return@withContext null)
                val next =
                    com.github.damontecres.wholphin.services.ServerAccess
                        .get("/Shows/NextUp?userId=$user&seriesId=$hex&limit=1&enableResumable=true")
                        ?.let { org.json.JSONObject(it).optJSONArray("Items")?.optJSONObject(0) }
                val played = show.optJSONObject("UserData")?.optBoolean("Played") == true
                if (next == null) return@withContext OwnedCopy(null, "Episodes", 0, 0f, played, null)
                val ticks = next.optJSONObject("UserData")?.optLong("PlaybackPositionTicks") ?: 0L
                val runTicks = next.optLong("RunTimeTicks").takeIf { it > 0 }
                val label = "S${next.optInt("ParentIndexNumber")}E${next.optInt("IndexNumber")}"
                OwnedCopy(
                    playId = parseId(next.optString("Id")),
                    playLabel = if (ticks > 0) "Resume $label" else "Play $label",
                    resumeMs = ticks / 10_000,
                    progress = runTicks?.let { ticks.toFloat() / it } ?: 0f,
                    played = played,
                    facts = null,
                )
            } else {
                val o = org.json.JSONObject(com.github.damontecres.wholphin.services.ServerAccess.get("/Users/$user/Items/$hex?fields=MediaSources") ?: return@withContext null)
                val ud = o.optJSONObject("UserData")
                val ticks = ud?.optLong("PlaybackPositionTicks") ?: 0L
                val runTicks = o.optLong("RunTimeTicks").takeIf { it > 0 }
                OwnedCopy(
                    playId = id,
                    playLabel = if (ticks > 0) "Resume " + clockText(ticks / 10_000) else "Play",
                    resumeMs = ticks / 10_000,
                    progress = runTicks?.let { ticks.toFloat() / it } ?: 0f,
                    played = ud?.optBoolean("Played") == true,
                    facts = o.optJSONArray("MediaSources")?.optJSONObject(0)?.let(::fileFacts),
                )
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "NoBars: your copy's details unavailable")
            null
        }
    }

private fun parseId(hex: String): java.util.UUID? =
    hex.replace("-", "").takeIf { it.length == 32 }?.let {
        java.util.UUID.fromString("${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}")
    }

private fun clockText(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** "4K · Dolby Vision · Atmos · 41 GB" */
private fun fileFacts(src: org.json.JSONObject): String? {
    val streams = src.optJSONArray("MediaStreams") ?: return null
    val all = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }
    val v = all.firstOrNull { it.optString("Type") == "Video" }
    val a = all.firstOrNull { it.optString("Type") == "Audio" && it.optBoolean("IsDefault") } ?: all.firstOrNull { it.optString("Type") == "Audio" }
    val parts = mutableListOf<String>()
    v?.let {
        val h = it.optInt("Height")
        val w = it.optInt("Width")
        parts += when {
            w >= 3200 || h >= 2000 -> "4K"
            w >= 1800 || h >= 1000 -> "1080p"
            h > 0 -> "${h}p"
            else -> ""
        }
        val range = it.optString("VideoRangeType")
        parts +=
            when {
                range.startsWith("DOVI") -> "Dolby Vision"
                range.contains("HDR10Plus") -> "HDR10+"
                range.contains("HDR10") -> "HDR10"
                range.contains("HLG") -> "HLG"
                else -> ""
            }
    }
    a?.let {
        val p = it.optString("Profile")
        val c = it.optString("Codec").lowercase(Locale.US)
        parts +=
            when {
                p.contains("Atmos", true) -> "Atmos"
                c == "truehd" -> "TrueHD"
                c.startsWith("dts") -> if (p.contains("MA", true)) "DTS-HD MA" else "DTS"
                c == "eac3" -> "DD+"
                c == "ac3" -> "Dolby Digital"
                else -> ""
            }
        it.optString("ChannelLayout").takeIf { l -> l.isNotBlank() }?.let { l -> parts += l }
    }
    src.optLong("Size").takeIf { it > 0 }?.let { parts += String.format(Locale.US, "%.0f GB", it / 1e9) }
    return parts.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
}

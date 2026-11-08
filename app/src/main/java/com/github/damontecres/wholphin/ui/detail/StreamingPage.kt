package com.github.damontecres.wholphin.ui.detail

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * NoBars: the Netflix or Prime Video page. Rows come from the daily list on the Mac. Titles you have
 * open your own copy; the rest open a side panel with "Open in Netflix / Prime Video".
 */
@Composable
fun StreamingPage(
    service: String,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: StreamingViewModel = hiltViewModel(),
) {
    var loadedRows by remember(service) { mutableStateOf<List<HomeRowLoadingState>?>(null) }
    var rawRows by remember(service) { mutableStateOf<List<StreamingStore.Row>?>(null) }
    LaunchedEffect(service) {
        if (service == StreamingStore.CINEMA) com.github.damontecres.wholphin.services.WantedStore.load()
        val loaded = StreamingStore.rows(service)
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
    // NoBars: the Theatre page opens with your "Want it" list (✓ once a film has arrived in the library)
    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
    val rows =
        remember(loadedRows, rawRows, wanted, service) {
            val base = loadedRows
            if (base == null || service != StreamingStore.CINEMA || wanted.isEmpty()) {
                base
            } else {
                val arrived = wanted.count { StreamingStore.libraryId(it) != null }
                listOf(
                    HomeRowLoadingState.Success(
                        title = StringStringProvider("Your list · ${wanted.size}" + if (arrived > 0) " · $arrived arrived ✓" else ""),
                        // today's card for each film when the page has one (fresh dates, money, countdown badge)
                        items =
                            wanted.map { w ->
                                val fresh =
                                    rawRows
                                        .orEmpty()
                                        .asSequence()
                                        .flatMap { it.items }
                                        .firstOrNull { it.tmdb == w.tmdb && it.series == w.series }
                                StreamingStore.toBaseItem(fresh?.copy(rank = null) ?: w, "wanted")
                            },
                        showViewMore = false,
                    ),
                ) + base
            }
        }
    var panel by remember { mutableStateOf<StreamingStore.Entry?>(null) }
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
            var position by rememberPosition()
            val onClick = { clicked: RowColumn, item: com.github.damontecres.wholphin.data.model.BaseItem ->
                position = clicked
                val entry = StreamingStore.entries[item.id]
                val libraryId = entry?.let { StreamingStore.libraryId(it) }
                if (entry != null && libraryId != null) {
                    viewModel.navigationManager.navigateTo(
                        Destination.MediaItem(libraryId, if (entry.series) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                    )
                } else {
                    panel = entry
                }
                Unit
            }
            HomePageContent(
                homeRows = current,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = onClick,
                onLongClickItem = { _, _ -> },
                onClickPlay = onClick,
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = false,
                showViewMore = false,
                modifier = modifier,
            )
        }
    }
    panel?.let { entry ->
        StreamingPanel(entry = entry, service = service, onBack = { panel = null })
    }
}

@Composable
private fun StreamingPanel(
    entry: StreamingStore.Entry,
    service: String,
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
            // 2. the buttons, always on screen (they wrap onto a second line rather than run off the edge)
            val openSvc = (entry.services + entry.ott.map { it.first }).firstOrNull { WatchProviders.canOpen(it) }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (service == StreamingStore.CINEMA) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
                    val onList = wanted.any { it.tmdb == entry.tmdb && it.series == entry.series }
                    if (entry.trailer != null) {
                        Button(onClick = { openTrailer(context, entry.trailer) }, modifier = Modifier.focusRequester(first)) {
                            Text("▶  Trailer")
                        }
                    }
                    openSvc?.let { svc ->
                        Button(
                            onClick = { WatchProviders.open(context, WatchProviders.Provider(0, svc, null), entry.title, netflixId = entry.netflixId) },
                            modifier = Modifier.ifElse(entry.trailer == null, Modifier.focusRequester(first)),
                        ) { Text("▶  " + shortService(svc)) }
                    }
                    Button(
                        onClick = { scope.launch { com.github.damontecres.wholphin.services.WantedStore.toggle(entry) } },
                        modifier = Modifier.ifElse(entry.trailer == null && openSvc == null, Modifier.focusRequester(first)),
                    ) { Text(if (onList) "✓  On your list" else "＋  Want it") }
                } else {
                    Button(
                        onClick = { WatchProviders.open(context, WatchProviders.Provider(0, name, null), entry.title, netflixId = entry.netflixId) },
                        modifier = Modifier.focusRequester(first),
                    ) { Text("▶  Open in $name") }
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
                    entry.note?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }
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

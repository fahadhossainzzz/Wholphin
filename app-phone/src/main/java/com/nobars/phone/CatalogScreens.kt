package com.nobars.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.data.BoxOffice
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore.Entry
import com.github.damontecres.wholphin.nobars.data.WantedStore
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Discover: Netflix · Prime Video · Theatre

private val SERVICES = listOf(StreamingStore.NETFLIX to "Netflix", StreamingStore.PRIME to "Prime Video", StreamingStore.CINEMA to "Theatre")

@Composable
fun DiscoverScreen(nav: Nav) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<StreamingStore.Row>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val service = SERVICES[tab].first
    LaunchedEffect(service) {
        rows = null
        failed = false
        val r = StreamingStore.rows(service)
        rows = r
        failed = r == null
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            SERVICES.forEachIndexed { i, (_, name) -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(name) }) }
        }
        val list = rows
        when {
            list == null && !failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isNullOrEmpty() -> Text("Today's lists aren't available. The Mac makes them each morning.", modifier = Modifier.padding(16.dp))
            else ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(list, key = { it.title }) { row ->
                        EntryShelf(row.title, row.items, onSeeAll = if (row.items.size > 6) ({ nav.open(Screen.Grid(row.title, row.items)) }) else null) { e ->
                            nav.open(Screen.Catalog(e.tmdb, e.series))
                        }
                    }
                }
        }
    }
}

/** "See all": every poster of a row. */
@Composable
fun EntryGridScreen(
    title: String,
    items: List<Entry>,
    nav: Nav,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.back() }) { Text("‹") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${items.size}  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.key() }) { e -> EntryCard(e, 110.dp) { nav.open(Screen.Catalog(e.tmdb, e.series)) } }
        }
    }
}

// ---------------------------------------------------------------- a catalogue title's own page

@Composable
fun CatalogScreen(
    tmdb: Int,
    series: Boolean,
    nav: Nav,
) {
    var entry by remember(tmdb, series) { mutableStateOf(StreamingStore.cachedEntry(tmdb, series)) }
    var page by remember(tmdb, series) { mutableStateOf<StreamingStore.TitlePage?>(null) }
    var loaded by remember(tmdb, series) { mutableStateOf(false) }
    LaunchedEffect(tmdb, series) {
        HistoryStore.loadIfOld()
        WantedStore.loadIfStale()
        (StreamingStore.findEntry(tmdb, series) ?: StreamingStore.title(tmdb, series))?.let { entry = it }
        page = StreamingStore.titlePage(tmdb, series)
        loaded = true
    }
    val e = entry
    if (e == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loaded) Text("Couldn't load this title.") else CircularProgressIndicator()
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Banner(e.backdrop ?: e.poster) { nav.back() } }
        item { Header(e) }
        item { EntryActions(e, nav) }
        nbSections(e, page, nav)
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
fun Banner(
    image: String?,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        TextButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Text("‹ Back", color = Color.White) }
    }
}

@Composable
private fun Header(e: Entry) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AsyncImage(
            model = e.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(e.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val facts =
                listOfNotNull(
                    e.year?.toString(),
                    if (e.series) e.seasons?.let { "$it season" + if (it > 1) "s" else "" } ?: "Series" else e.runtime?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                    e.genres.take(3).joinToString(", ").takeIf { it.isNotBlank() },
                )
            Text(facts.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            EntryChips(e)
        }
    }
}

/** Watch / Watchlist / Trailer / Open in the service, then where it is today. */
@Composable
private fun EntryActions(
    e: Entry,
    nav: Nav,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wanted by WantedStore.items.collectAsState()
    val onList = wanted.any { it.tmdb == e.tmdb && it.series == e.series }
    val lib = StreamingStore.libraryId(e)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lib != null) Button(onClick = { nav.open(Screen.Title(lib)) }) { Text("▶ In your library") }
            OutlinedButton(onClick = { scope.launch { WantedStore.toggle(e, "phone") } }) { Text(if (onList) "✓ On Watchlist" else "+ Watchlist") }
            e.trailer?.let { k -> OutlinedButton(onClick = { openTrailer(context, k) }) { Text("Trailer") } }
            e.services.firstOrNull { it.startsWith("Netflix") || it.contains("Prime") }?.let { s ->
                OutlinedButton(onClick = { openService(context, s, e) }) { Text("Open in ${if (s.startsWith("Netflix")) "Netflix" else "Prime Video"}") }
            }
        }
        if (HistoryStore.watched(e.key())) Text("👁 In your history", color = GoodGreen, fontSize = 14.sp)
        e.status?.let { Text(it, fontSize = 14.sp, color = if (it.contains("No longer")) WarnRed else GoodGreen) }
        e.note?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFAB91)) }
        e.date?.let { Text(it, fontSize = 14.sp, color = DateBlue) }
        e.facts?.let { Text(it, fontSize = 13.sp, color = GoodGreen) }
        e.streamOn?.let { Text("Streaming  $it", fontSize = 14.sp, color = StreamGold) }
        e.freeOn?.let { Text("Free  $it", fontSize = 14.sp, color = FreeOrange) }
        e.rentBuy?.let { Text("Rent or buy  $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!e.series && (e.gross != null || e.budget != null)) {
            BoxOffice.line(BoxOffice.Money(e.gross, e.budget, e.year, today = e.grossToday, grossLocal = e.grossLocal))?.let { Text(it, fontSize = 14.sp) }
        }
    }
}

/** The TV's detail sections: story, cast, crew, trailers, about, more like this. Used by both title pages. */
fun LazyListScope.nbSections(
    e: Entry?,
    page: StreamingStore.TitlePage?,
    nav: Nav,
    showStory: Boolean = true,
) {
    if (showStory) {
        page?.tagline?.let { item { Text("“$it”", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) } }
        e?.overview?.takeIf { it.isNotBlank() }?.let { item { Text(it, modifier = Modifier.padding(16.dp), lineHeight = 20.sp) } }
    }
    val cast = page?.cast.orEmpty()
    if (cast.isNotEmpty()) {
        item {
            Section("Cast") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cast) { (name, role, photo) ->
                        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = photo,
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Text(name, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                            role?.let { Text(it, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    } else {
        e?.cast?.let { c -> item { Section("Cast") { Text("With $c", modifier = Modifier.padding(horizontal = 16.dp)) } } }
    }
    val crew = page?.crew.orEmpty()
    if (crew.isNotEmpty()) {
        item {
            Section("Crew") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    crew.forEach { (job, names) ->
                        Row {
                            Text(job, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                            Text(names.joinToString(", "), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    val videos = page?.videos.orEmpty()
    if (videos.isNotEmpty()) {
        item {
            val context = LocalContext.current
            Section("Trailers and clips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(videos) { (name, key, date) ->
                        OutlinedButton(onClick = { openTrailer(context, key) }) { Text("▶ " + name.take(36) + (date?.let { " · " + it.take(4) } ?: ""), maxLines = 1) }
                    }
                }
            }
        }
    }
    if (page != null) {
        val about =
            listOfNotNull(
                page.originalTitle?.let { "Original title" to it },
                page.language?.let { "Language" to it },
                page.countries.takeIf { it.isNotEmpty() }?.let { "Country" to it.joinToString(", ") },
                page.studios.takeIf { it.isNotEmpty() }?.let { "Studio" to it.take(3).joinToString(", ") },
                page.networks.takeIf { it.isNotEmpty() }?.let { "Network" to it.joinToString(", ") },
                page.cert?.let { "Certificate" to it },
                page.status?.let { "Status" to it },
                page.episodes?.let { "Episodes" to "$it" },
                page.collection?.let { "Collection" to it },
            )
        if (about.isNotEmpty()) {
            item {
                Section("About") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        about.forEach { (k, v) ->
                            Row {
                                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                                Text(v, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    val like = page?.like.orEmpty()
    if (like.isNotEmpty()) {
        item { EntryShelf("More like this", like) { x -> nav.open(Screen.Catalog(x.tmdb, x.series)) } }
    }
}

@Composable
fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        content()
    }
}

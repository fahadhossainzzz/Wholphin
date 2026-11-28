package com.nobars.phone

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.nobars.playback.NoBarsLog
import com.nobars.jellyfin.NbJellyfin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.UUID

fun play(
    context: Context,
    id: UUID,
    fromStart: Boolean,
) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ID, id.toString())
            .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart),
    )
}

private suspend fun <T> safe(
    what: String,
    block: suspend () -> List<T>,
): List<T> =
    try {
        block()
    } catch (e: Exception) {
        Timber.w(e, "NoBars: %s failed", what)
        emptyList()
    }

private data class Shelf(
    val title: String,
    val items: List<BaseItemDto>,
    val wide: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val lib = NbJellyfin.current.library
    var shelves by remember { mutableStateOf<List<Shelf>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        refreshing = shelves != null
        shelves =
            coroutineScope {
                val resume = async { safe("resume") { lib.resume() } }
                val next = async { safe("next up") { lib.nextUp() } }
                val films = async { safe("latest films") { lib.latest(BaseItemKind.MOVIE) } }
                val eps = async { safe("latest episodes") { lib.latest(BaseItemKind.EPISODE) } }
                listOf(
                    Shelf("Continue watching", resume.await(), wide = true),
                    Shelf("Next up", next.await(), wide = true),
                    Shelf("Recently added films", films.await(), wide = false),
                    Shelf("Recently added episodes", eps.await(), wide = true),
                ).filter { it.items.isNotEmpty() }
            }
        refreshing = false
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { round++ }, modifier = Modifier.fillMaxSize()) {
        val list = shelves
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                item { Text("NoBars", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NbRed, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (list.isEmpty()) {
                    item { Text("Nothing to show. Pull down to try again.", modifier = Modifier.padding(16.dp)) }
                }
                list.forEach { shelf ->
                    item(key = shelf.title) {
                        Column(Modifier.padding(top = 16.dp)) {
                            Text(shelf.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shelf.items, key = { it.id }) { item ->
                                    if (shelf.wide) {
                                        WideCard(item, 220.dp) { nav.open(Screen.Title(item.id)) }
                                    } else {
                                        PosterCard(item, 112.dp) { nav.open(Screen.Title(item.id)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Sort(
    val label: String,
    val by: ItemSortBy,
    val descending: Boolean,
) {
    ADDED("Date added", ItemSortBy.DATE_CREATED, true),
    NAME("Name", ItemSortBy.SORT_NAME, false),
    RATING("Rating", ItemSortBy.COMMUNITY_RATING, true),
}

@Composable
fun LibraryScreen(nav: Nav) {
    val lib = NbJellyfin.current.library
    var films by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf(Sort.ADDED) }
    var menu by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<BaseItemDto>() }
    var total by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val kind = if (films) BaseItemKind.MOVIE else BaseItemKind.SERIES

    fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        scope.launch {
            try {
                val page = lib.items(kind, sort.by, sort.descending, items.size)
                items.addAll(page.items)
                total = page.total
            } catch (e: Exception) {
                Timber.w(e, "NoBars: library page failed")
                if (total < 0) total = items.size
            }
            loading = false
        }
    }

    LaunchedEffect(films, sort) {
        items.clear()
        total = -1
        loading = false
        grid.scrollToItem(0)
        loadMore()
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 18 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) loadMore() }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = if (films) 0 else 1) {
            Tab(selected = films, onClick = { films = true }, text = { Text("Films") })
            Tab(selected = !films, onClick = { films = false }, text = { Text("Shows") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (total >= 0) "$total titles" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text("Sort: ${sort.label} ▾") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = {
                            sort = s
                            menu = false
                        })
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            state = grid,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item -> PosterCard(item, 110.dp) { nav.open(Screen.Title(item.id)) } }
        }
    }
}

@Composable
fun SearchScreen(nav: Nav) {
    val lib = NbJellyfin.current.library
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        results = safe("search") { lib.search(q) }
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search your library") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (searching) Text("Searching…", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!searching && query.trim().length >= 2 && results.isEmpty()) {
            Text("Nothing in the library matches.", modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.id }) { item -> PosterCard(item, 110.dp) { nav.open(Screen.Title(item.id)) } }
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav) {
    val nb = NbJellyfin.current
    val s by nb.session.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Line("Server", "${s?.serverName.orEmpty()}  ·  ${s?.serverUrl.orEmpty()}")
        Line("User", s?.userName.orEmpty())
        Line("App version", BuildConfig.VERSION_NAME)
        Button(onClick = {
            sending = true
            scope.launch {
                val ok = nb.uploadLog("NoBars phone log\nversion=${BuildConfig.VERSION_NAME}\n\n" + NoBarsLog.tail(400_000))
                sending = false
                Toast.makeText(context, if (ok) "Log sent to the server" else "The log could not be sent", Toast.LENGTH_SHORT).show()
            }
        }, enabled = !sending, modifier = Modifier.fillMaxWidth()) { Text(if (sending) "Sending…" else "Send log to server") }
        OutlinedButton(onClick = {
            nb.signOut()
            nav.signedOut()
        }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun Line(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp)
    }
}

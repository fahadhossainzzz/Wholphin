package com.nobars.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.WantedStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** My lists: Watchlist · History. */
@Composable
fun ListsScreen(nav: Nav) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Watchlist") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) WatchlistTab(nav) else HistoryTab(nav)
    }
}

@Composable
private fun WatchlistTab(nav: Nav) {
    val items by WantedStore.items.collectAsState()
    val history by HistoryStore.state.collectAsState()
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        WantedStore.load()
        HistoryStore.loadIfOld()
        loading = false
    }
    val rows = remember(items, history) { watchlistRows(items, history.items.keys) }
    when {
        rows.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        rows.isEmpty() -> Text("Your Watchlist is empty. Add titles from Netflix, Prime Video or Theatre.", modifier = Modifier.padding(16.dp))
        else ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows, key = { it.first }) { (name, list) ->
                    EntryShelf(name, list, onSeeAll = if (list.size > 6) ({ nav.open(Screen.Grid("Watchlist · $name", list)) }) else null) { e ->
                        nav.open(Screen.Catalog(e.tmdb, e.series))
                    }
                }
            }
    }
}

@Composable
private fun HistoryTab(nav: Nav) {
    var rows by remember { mutableStateOf(PhoneHistory.rows) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        rows = PhoneHistory.load()
        loading = false
    }
    val list = rows
    when {
        list == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isNullOrEmpty() -> Text("No history yet. It comes from Trakt.", modifier = Modifier.padding(16.dp))
        else ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { row ->
                    EntryShelf(row.name, row.items, count = row.total, onSeeAll = if (row.total > row.items.size || row.items.size > 6) ({
                        scope.launch { nav.open(Screen.Grid("History · ${row.name}", PhoneHistory.all(row))) }
                    }) else null) { e -> nav.open(Screen.Catalog(e.tmdb, e.series)) }
                }
            }
    }
}

// ---------------------------------------------------------------- Calendar: a month as a list of days

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

@Composable
fun CalendarScreen(nav: Nav) {
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val m = YearMonth.parse(month)
    var days by remember(month) { mutableStateOf(PhoneCalendar.cached(m)) }
    var loading by remember(month) { mutableStateOf(true) }
    var show by rememberSaveable { mutableStateOf("all") }
    LaunchedEffect(month) {
        days = PhoneCalendar.load(m)
        loading = false
    }
    val today = LocalDate.now()
    val list = state(days, show)
    val listState = rememberLazyListState()
    LaunchedEffect(list.isNotEmpty(), month) {
        // open at today (or the first day after it) in the current month
        if (m == YearMonth.now()) {
            val i = list.indexOfFirst { !it.first.isBefore(today) }
            if (i > 0) listState.scrollToItem(i)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = m.minusMonths(1).toString() }) { Text("‹") }
            Text(m.format(MONTH), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (m != YearMonth.now()) TextButton(onClick = { month = YearMonth.now().toString() }) { Text("Today") }
            TextButton(onClick = { month = m.plusMonths(1).toString() }) { Text("›") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "yours" to "Yours", "watched" to "Watched", "world" to "Releases").forEach { (k, label) ->
                FilterChip(selected = show == k, onClick = { show = k }, label = { Text(label) })
            }
        }
        when {
            days == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(if (days == null) "The calendar isn't available right now." else "Nothing this month.", modifier = Modifier.padding(16.dp))
            else ->
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    list.forEach { (day, items) ->
                        item(key = day.toString()) {
                            Text(
                                (if (day == today) "Today · " else "") + day.format(DAY),
                                fontWeight = FontWeight.Bold,
                                color = if (day == today) NbRed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(items, key = { day.toString() + it.kind + it.title + it.line }) { c -> CalRow(c, nav) }
                    }
                }
        }
    }
}

private fun state(
    days: Map<LocalDate, List<CalItem>>?,
    show: String,
): List<Pair<LocalDate, List<CalItem>>> =
    days.orEmpty().map { (d, l) -> d to l.filter { show == "all" || it.kind == show } }.filter { it.second.isNotEmpty() }

@Composable
private fun CalRow(
    c: CalItem,
    nav: Nav,
) {
    val key = c.key
    val color =
        when (c.kind) {
            "yours" -> NbRed
            "watched" -> GoodGreen
            else -> DateBlue
        }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = key != null) {
            key?.let { k -> k.substringAfter(":").toIntOrNull()?.let { nav.open(Screen.Catalog(it, k.startsWith("tv:"))) } }
        }.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = c.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.line.isNotBlank()) Text(c.line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.tag.isNotBlank()) Text(c.tag, fontSize = 11.sp, color = color)
                c.rating?.let { Text("★ " + rating(it), fontSize = 11.sp, color = ImdbYellow) }
            }
        }
    }
}

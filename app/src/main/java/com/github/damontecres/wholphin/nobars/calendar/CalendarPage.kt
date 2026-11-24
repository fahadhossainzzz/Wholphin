package com.github.damontecres.wholphin.nobars.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.ui.QuickView
import com.github.damontecres.wholphin.nobars.ui.TraktMark
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The colour strip under a poster: purple yours, green watched, blue the world. */
internal fun kindColor(kind: CalItem.Kind): Color =
    when (kind) {
        CalItem.Kind.YOURS -> Color(0xFFA78BFA)
        CalItem.Kind.WATCHED -> Color(0xFF4ADE80)
        CalItem.Kind.WORLD -> Color(0xFF60A5FA)
    }

/** Whether a Calendar item is in your library. */
internal fun CalItem.owned(): Boolean = key?.let { StreamingStore.ownedId(it) } != null

/** OK on a Calendar item: your copy when it's in the library, else the quick view. */
internal fun openCalItem(
    navigationManager: NavigationManager,
    item: CalItem,
) {
    val key = item.key ?: return
    val tmdb = item.tmdb ?: return
    val lib = StreamingStore.ownedId(key)
    if (lib != null) {
        navigationManager.navigateTo(Destination.MediaItem(lib, if (item.series) BaseItemKind.SERIES else BaseItemKind.MOVIE))
    } else {
        QuickView.open(tmdb, item.series, item.title, item.image)
    }
}

/** Hold OK: for yours or watched, Watched just now / on release / Remove (that very episode); for the world, the quick view. */
internal fun holdCalItem(item: CalItem) {
    val key = item.key ?: return
    if (item.kind == CalItem.Kind.WORLD) {
        item.tmdb?.let { QuickView.open(it, item.series, item.title, item.image) }
    } else {
        TraktMark.ask(TraktMark.Target(key, item.title, season = item.season, episode = item.episode))
    }
}

/**
 * NoBars: the Calendar page. The month grid on the left (Monday first; each day up to three small posters with a
 * colour strip: yours, watched, the world; "+N" for more) and, always beside it, the pane of the day you're on.
 * Nothing opens or closes: OK (or Right from Sunday) moves into the pane, Back (or Left) returns to the same day.
 * ‹ › change month; Today comes back. Where you were is kept when you open a title and come back.
 */
@Composable
fun CalendarPage(
    navigationManager: NavigationManager,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(CalendarMemo.month) }
    var data by remember(month) { mutableStateOf(CalendarStore.cached(month)) }
    var failed by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month) {
        CalendarMemo.month = month
        failed = false
        val got = CalendarStore.load(month)
        if (got != null) data = got else failed = data == null
    }
    // the next month too, so ‹ › are quick
    LaunchedEffect(month) { CalendarStore.load(month.plusMonths(1)) }
    val today = LocalDate.now()
    // the day you're on: the one you were on (this month), else today, else the 1st
    var selected by remember(month) {
        mutableStateOf(
            CalendarMemo.day?.takeIf { YearMonth.from(it) == month }
                ?: if (YearMonth.from(today) == month) today else month.atDay(1),
        )
    }
    val dayRequesters = remember(month) { HashMap<LocalDate, FocusRequester>() }
    fun dayRequester(day: LocalDate) = dayRequesters.getOrPut(day) { FocusRequester() }
    val items = data?.days?.get(selected).orEmpty()
    val itemRequesters = remember(month, selected, items.size) { List(items.size) { FocusRequester() } }
    var paneHasFocus by remember { mutableStateOf(false) }
    val focusPane = {
        val i = CalendarMemo.item.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        itemRequesters.getOrNull(i)?.tryRequestFocus() == true
    }
    // the first frame: focus straight onto the day (or the item you were on), so nothing else takes it first
    LaunchedEffect(month, data != null) {
        repeat(10) {
            val done = if (CalendarMemo.inPane && items.isNotEmpty()) focusPane() else dayRequester(selected).tryRequestFocus()
            if (done) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    BackHandler(enabled = paneHasFocus) {
        CalendarMemo.inPane = false
        dayRequester(selected).tryRequestFocus()
    }
    Row(modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Locale.UK) + " " + month.year,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.width(200.dp),
                )
                Button(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Button(onClick = { month = month.plusMonths(1) }) { Text("›") }
                Button(onClick = {
                    CalendarMemo.day = today
                    month = YearMonth.now()
                }) { Text("Today") }
                Box(Modifier.weight(1f))
                Legend("Yours", kindColor(CalItem.Kind.YOURS))
                Legend("Watched", kindColor(CalItem.Kind.WATCHED))
                Legend("World", kindColor(CalItem.Kind.WORLD))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Text(
                        d.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(Locale.UK),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
            }
            if (data == null && failed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This month couldn't be loaded. Check that the server is on, then try again.", color = Color.White.copy(alpha = 0.8f))
                }
            } else {
                // six weeks at most, Monday first, starting on the Monday on or before the 1st
                val start = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                val weeks = if (start.plusDays(35) > month.atEndOfMonth()) 5 else 6
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusGroup()
                            .focusProperties {
                                // coming in from the menu or the pane: the day you're on
                                onEnter = { dayRequester(selected).tryRequestFocus() }
                            },
                ) {
                    for (w in 0 until weeks) {
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (d in 0 until 7) {
                                val day = start.plusDays((w * 7 + d).toLong())
                                val inMonth = YearMonth.from(day) == month
                                DayCell(
                                    day = day,
                                    items = if (inMonth) data?.days?.get(day).orEmpty() else emptyList(),
                                    inMonth = inMonth,
                                    isToday = day == today,
                                    isSelected = day == selected,
                                    loading = data == null,
                                    onClick = {
                                        if (inMonth) {
                                            CalendarMemo.item = 0
                                            focusPane()
                                        } else {
                                            CalendarMemo.day = day
                                            month = YearMonth.from(day)
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .focusRequester(dayRequester(day))
                                            .onFocusChanged {
                                                if (it.isFocused && inMonth) {
                                                    if (selected != day) CalendarMemo.item = 0
                                                    selected = day
                                                    CalendarMemo.day = day
                                                    CalendarMemo.inPane = false
                                                }
                                            },
                                )
                            }
                        }
                    }
                }
            }
        }
        CalendarDayPane(
            day = selected,
            items = items,
            requesters = itemRequesters,
            onOpen = { openCalItem(navigationManager, it) },
            onHold = ::holdCalItem,
            onFocusIndex = {
                CalendarMemo.item = it
                CalendarMemo.inPane = true
            },
            modifier =
                Modifier
                    .width(330.dp)
                    .fillMaxHeight()
                    .onFocusChanged { paneHasFocus = it.hasFocus }
                    .focusGroup()
                    .focusProperties {
                        onEnter = { focusPane() }
                        // Left goes back to the day, not to whichever cell is nearest
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Left) {
                                CalendarMemo.inPane = false
                                dayRequester(selected).tryRequestFocus()
                            }
                        }
                    },
        )
    }
}

/** Where you were, kept while the app runs: so coming back from a title shows the same month, day and item. */
private object CalendarMemo {
    var month: YearMonth = YearMonth.now()
    var day: LocalDate? = null
    var item: Int = 0
    var inPane: Boolean = false
}

@Composable
private fun Legend(
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    items: List<CalItem>,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (isSelected) Color(0xFF22222E) else if (inMonth) Color(0xFF17171F) else Color(0xFF101016),
                focusedContainerColor = Color(0xFF2A2A38),
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (isToday) Color(0xFF8B5CF6) else Color.Transparent),
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (inMonth) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                Box(Modifier.weight(1f))
                if (items.size > 3) Text("+${items.size - 3}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                if (loading && inMonth && day.dayOfMonth == 1) Text("Loading…", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.take(3).forEach { item: CalItem ->
                    Box(Modifier.fillMaxHeight().aspectRatio(0.68f, matchHeightConstraintsFirst = true)) {
                        Column(Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = item.image,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(Color(0xFF2A2A36)),
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).background(kindColor(item.kind)))
                        }
                        // ✓ you have it
                        if (item.owned()) {
                            Text(
                                "✓",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF2E7D32))
                                        .padding(horizontal = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Wed 25 November" */
internal val PANEL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.UK)

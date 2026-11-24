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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * NoBars: the Calendar page. A month grid (Monday first): each day shows up to three small posters with a colour
 * strip (yours, watched, the world) and "+N" for more. The arrow keys move day by day; OK opens the day's panel with
 * everything on it. ‹ › change month; Today comes back.
 */
@Composable
fun CalendarPage(
    navigationManager: NavigationManager,
    modifier: Modifier = Modifier,
) {
    var month by remember { mutableStateOf(CalendarMemo.month) }
    var data by remember(month) { mutableStateOf(CalendarStore.cached(month)) }
    var failed by remember(month) { mutableStateOf(false) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(month) {
        CalendarMemo.month = month
        failed = false
        val got = CalendarStore.load(month)
        if (got != null) data = got else failed = data == null
    }
    // the next month too, so ‹ › are quick
    LaunchedEffect(month) { CalendarStore.load(month.plusMonths(1)) }
    val today = LocalDate.now()
    val focusDay = remember(month) { if (YearMonth.from(today) == month) today else month.atDay(1) }
    val dayFocus = remember(month) { FocusRequester() }
    LaunchedEffect(month, data != null) {
        repeat(20) {
            kotlinx.coroutines.delay(50)
            if (openDay == null && dayFocus.tryRequestFocus()) return@LaunchedEffect
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Locale.UK) + " " + month.year,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.width(220.dp),
                )
                Button(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Button(onClick = { month = month.plusMonths(1) }) { Text("›") }
                Button(onClick = { month = YearMonth.now() }) { Text("Today") }
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
            when {
                data == null && failed ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("This month couldn't be loaded. Check that the server is on, then try again.", color = Color.White.copy(alpha = 0.8f))
                    }
                else -> {
                    // six weeks, Monday first, starting on the Monday on or before the 1st
                    val start = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                    val weeks = if (start.plusDays(35) > month.atEndOfMonth()) 5 else 6
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                        loading = data == null,
                                        onClick = {
                                            if (inMonth) {
                                                openDay = day
                                            } else {
                                                month = YearMonth.from(day)
                                            }
                                        },
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .then(if (day == focusDay) Modifier.focusRequester(dayFocus) else Modifier),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        openDay?.let { day ->
            BackHandler { openDay = null }
            CalendarDayPanel(
                day = day,
                items = data?.days?.get(day).orEmpty(),
                onOpen = { openCalItem(navigationManager, it) },
                onHold = ::holdCalItem,
                onClose = { openDay = null },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** The month you were on, so coming back from a title shows it again. */
private object CalendarMemo {
    var month: YearMonth = YearMonth.now()
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
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (inMonth) Color(0xFF17171F) else Color(0xFF101016),
                focusedContainerColor = Color(0xFF2A2A38),
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
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
                items.take(3).forEach { it: CalItem ->
                    Column(Modifier.fillMaxHeight().aspectRatio(0.68f, matchHeightConstraintsFirst = true)) {
                        AsyncImage(
                            model = it.image,
                            contentDescription = it.title,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(Color(0xFF2A2A36)),
                        )
                        Box(Modifier.fillMaxWidth().height(3.dp).background(kindColor(it.kind)))
                    }
                }
            }
        }
    }
}

/** "Wed 25 November" */
internal val PANEL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.UK)

package com.github.damontecres.wholphin.nobars.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.data.ImdbVotesStore
import com.github.damontecres.wholphin.ui.tryRequestFocus
import java.time.LocalDate
import java.util.Locale

/**
 * NoBars Calendar: OK on a day slides in this panel: everything that day (yours first, then what you watched, then the
 * world), each with its poster, what it is, where and when, and its IMDb rating. OK opens it; hold OK: Watched /
 * Watchlist; Back returns to the month.
 */
@Composable
fun CalendarDayPanel(
    day: LocalDate,
    items: List<CalItem>,
    onOpen: (CalItem) -> Unit,
    onHold: (CalItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember(day) { FocusRequester() }
    LaunchedEffect(day) {
        repeat(20) {
            kotlinx.coroutines.delay(40)
            if (first.tryRequestFocus()) return@LaunchedEffect
        }
    }
    Box(
        modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(Color(0xF214141C))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(day.format(PANEL_DATE), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Box(Modifier.weight(1f))
                Text("${items.size} item${if (items.size == 1) "" else "s"}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
            }
            if (items.isEmpty()) {
                Surface(
                    onClick = onClose,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    modifier = Modifier.fillMaxWidth().focusRequester(first),
                ) {
                    Text("Nothing on this day.", modifier = Modifier.padding(12.dp), color = Color.White)
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { i, it ->
                    Surface(
                        onClick = { onOpen(it) },
                        onLongClick = { onHold(it) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors =
                            ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF1B1B25),
                                focusedContainerColor = Color(0xFF2A2A3A),
                            ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(40.dp)) {
                                AsyncImage(
                                    model = it.image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .size(40.dp, 57.dp)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(Color(0xFF2A2A36)),
                                )
                                Box(Modifier.fillMaxWidth().height(3.dp).background(kindColor(it.kind)))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                Text(it.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(it.line, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        it.tag,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(kindColor(it.kind).copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                    it.rating?.let { r ->
                                        Text(
                                            "★ " + String.format(Locale.US, "%.1f", r) + (it.votes?.let { v -> "  " + ImdbVotesStore.short(v) } ?: ""),
                                            fontSize = 11.sp,
                                            color = Color(0xFFF5C518),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "OK  details     Hold OK  Watched · Watchlist     Back  the month",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

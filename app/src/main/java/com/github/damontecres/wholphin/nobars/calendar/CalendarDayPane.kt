package com.github.damontecres.wholphin.nobars.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import java.time.LocalDate
import java.util.Locale

/**
 * NoBars Calendar: the pane beside the month, always there, showing the day you're on: everything that day (yours
 * first, then what you watched, then the world), each with its poster, what it is, where and when, its IMDb rating
 * and ✓ when you have it. It never opens or closes, so the focus only ever moves between the month and this list.
 */
@Composable
fun CalendarDayPane(
    day: LocalDate,
    items: List<CalItem>,
    requesters: List<FocusRequester>,
    onOpen: (CalItem) -> Unit,
    onHold: (CalItem) -> Unit,
    onFocusIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14141C))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(day.format(PANEL_DATE), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Box(Modifier.weight(1f))
            Text("${items.size} item${if (items.size == 1) "" else "s"}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
        }
        if (items.isEmpty()) {
            Text("Nothing on this day.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            items.forEachIndexed { i, item ->
                Surface(
                    onClick = { onOpen(item) },
                    onLongClick = { onHold(item) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF1B1B25),
                            focusedContainerColor = Color(0xFF2E2E40),
                        ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(requesters.getOrNull(i)?.let { Modifier.focusRequester(it) } ?: Modifier)
                            .onFocusChanged { f -> if (f.isFocused) onFocusIndex(i) },
                ) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(38.dp)) {
                            AsyncImage(
                                model = item.image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .size(38.dp, 54.dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFF2A2A36)),
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).background(kindColor(item.kind)))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.line, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.tag,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(kindColor(item.kind).copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                                if (item.owned()) {
                                    Text(
                                        "✓ In your library",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E7D32)).padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                                item.rating?.let { r ->
                                    Text(
                                        "★ " + String.format(Locale.US, "%.1f", r) + (item.votes?.let { v -> " " + ImdbVotesStore.short(v) } ?: ""),
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
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

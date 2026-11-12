package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.BoxOffice
import com.github.damontecres.wholphin.services.CriticFallback
import com.github.damontecres.wholphin.services.ImdbVotesStore
import com.github.damontecres.wholphin.services.StreamingStore
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * NoBars: the library pages' layout, in the same visual language as the side panel:
 *  - Glance (header): rating chips next to the "Also on" logos
 *  - Explore: the Journey across the screen, box office, where else to watch
 *  - Reference: "About" with director, writers, studio, collection, file, drive, date added, subtitles
 */

private fun key(item: BaseItem) = item.id.toString().replace("-", "")

@Composable
fun NbChip(
    text: String,
    background: Color,
    textColor: Color,
) {
    Text(
        text,
        fontSize = 13.sp,
        color = textColor,
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** IMDb · 🍅 · "Rating 8+" chips, then the "Also on" logos (which open the service). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NbRatingChips(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val rating = item.data.communityRating
    val votes = ImdbVotesStore.get(item.id)?.votes ?: StreamingStore.votes(item.id)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        rating?.let { r ->
            NbChip(
                "IMDb " + String.format(Locale.US, "%.1f", r) + (votes?.let { " · ${ImdbVotesStore.short(it)}" } ?: ""),
                Color(0xFFF5C518),
                Color.Black,
            )
        }
        val critic = item.data.criticRating?.let { "🍅 ${it.toInt()}%" } ?: CriticFallback.label(item.id)
        critic?.let { NbChip(it, if (it.startsWith("🍅")) Color(0xCCFA320A) else Color.White.copy(alpha = 0.15f), Color.White) }
        if (rating != null && rating >= 8f) NbChip("Rating 8+", Color(0xFF7E57C2).copy(alpha = 0.75f), Color.White)
        WatchProvidersLine(item, Modifier.align(Alignment.CenterVertically))
    }
}

/** A section below the header: a small heading, and a frame when it has the focus (so ▼ can reach it). */
@Composable
fun NbSection(
    title: String,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = if (inset) HeaderUtils.startPadding + 8.dp else 0.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .border(1.dp, if (focused) Color.White.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(10.dp))
                .background(if (focused) Color.White.copy(alpha = 0.05f) else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(12.dp),
    ) {
        Text(
            title.uppercase(Locale.US),
            fontSize = 12.sp,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        content()
    }
}

fun nbStepColor(kind: String): Color =
    when (kind) {
        "cinema" -> Color(0xFF64B5F6)
        "home" -> Color(0xFF81C784)
        "chart" -> Color(0xFFFFB74D)
        "you" -> Color(0xFFB39DDB)
        else -> Color(0xFF9E9E9E)
    }

fun nbDate(iso: String): String =
    try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
    } catch (_: Exception) {
        iso
    }

/** The journey laid out across the screen: dots on one line, title, date and detail under each. */
@Composable
fun NbJourneyAcross(steps: List<StreamingStore.JourneyStep>) {
    val today = remember { LocalDate.now().toString() }
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            steps.forEach { step ->
                val color = nbStepColor(step.kind)
                val future = step.date != null && step.date > today
                // a few steps sit side by side from the left; many share the width
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = if (steps.size >= 5) Modifier.weight(1f) else Modifier.width(210.dp),
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (future) Color.Black else color)
                            .border(2.dp, color, CircleShape),
                    )
                    Text(step.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2)
                    step.date?.let {
                        Text((if (step.by) "by " else "") + nbDate(it), fontSize = 13.sp, color = color, maxLines = 1)
                    }
                    step.detail?.let {
                        Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Box office: the two money lines and, while it's in cinemas, the week-by-week bars. */
@Composable
fun NbBoxOffice(
    item: BaseItem,
    weekly: List<Long>,
): Boolean {
    val money by BoxOffice.library.collectAsState()
    val streaming by StreamingStore.library.collectAsState()
    val m = money[key(item)] ?: return false
    val first = BoxOffice.line(m, streaming[key(item)].orEmpty()) ?: return false
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(first, fontSize = 15.sp, color = Color.White)
        BoxOffice.detailLine(m)?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)) }
        if (weekly.size >= 2) {
            val max = weekly.max().coerceAtLeast(1)
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 4.dp).height(30.dp),
            ) {
                weekly.forEach { w ->
                    Box(
                        Modifier
                            .width(12.dp)
                            .fillMaxHeight((w.toFloat() / max).coerceIn(0.06f, 1f))
                            .background(nbStepColor("cinema"), RoundedCornerShape(2.dp)),
                    )
                }
                Text("  week by week", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
    return true
}

/** Where else to watch: Netflix / Prime here, streaming and free worldwide, rent or buy. */
@Composable
fun NbWhereToWatch(item: BaseItem): Boolean {
    val streaming by StreamingStore.library.collectAsState()
    val here = streaming[key(item)].orEmpty().map { StreamingStore.displayName(it) }
    val f = StreamingStore.libraryFacts[key(item)]
    if (here.isEmpty() && f?.streamOn == null && f?.freeOn == null && f?.rentBuy == null) return false
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (here.isNotEmpty()) Text("Also on " + here.joinToString(" and ") + " (press its logo at the top to open it)", fontSize = 14.sp, color = Color(0xFFA5D6A7))
        f?.streamOn?.let { Text("Streaming  $it", fontSize = 14.sp, color = Color(0xFFFFE082)) }
        f?.freeOn?.let { Text("Free  $it", fontSize = 14.sp, color = Color(0xFFFFCC80)) }
        f?.rentBuy?.let { Text("Rent or buy  $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
    }
    return true
}

/** About: the reference facts, in a grid of label and value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NbAbout(item: BaseItem) {
    val dto = item.data
    val facts =
        remember(item) {
            val out = mutableListOf<Pair<String, String>>()
            fun people(kind: PersonKind) = dto.people?.filter { it.type == kind }?.mapNotNull { it.name }?.distinct().orEmpty()
            people(PersonKind.DIRECTOR).takeIf { it.isNotEmpty() }?.let { out += "Director" to it.take(3).joinToString(", ") }
            people(PersonKind.WRITER).takeIf { it.isNotEmpty() }?.let { out += "Writers" to it.take(3).joinToString(", ") }
            people(PersonKind.CREATOR).takeIf { it.isNotEmpty() }?.let { out += "Created by" to it.take(3).joinToString(", ") }
            dto.studios?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() }?.let { out += "Studio" to it.take(2).joinToString(", ") }
            StreamingStore.libraryFacts[key(item)]?.collection?.let { out += "Collection" to it }
            dto.taglines?.firstOrNull()?.let { out += "Tagline" to it }
            val src = dto.mediaSources?.firstOrNull()
            src?.let { s ->
                listOfNotNull(
                    s.container?.uppercase(Locale.US),
                    s.size?.let { String.format(Locale.US, "%.1f GB", it / 1e9) },
                    s.bitrate?.let { String.format(Locale.US, "%.0f Mbps", it / 1e6) },
                ).takeIf { it.isNotEmpty() }?.let { out += "File" to it.joinToString(" · ") }
                s.path?.let { p -> Regex("/Volumes/([^/]+)/").find(p)?.groupValues?.get(1)?.let { out += "Drive" to it } }
                s.mediaStreams
                    ?.filter { it.type == MediaStreamType.SUBTITLE }
                    ?.map { listOfNotNull(it.language ?: "unknown", it.codec?.uppercase(Locale.US), if (it.isExternal) "file" else null).joinToString(" ") }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out += "Subtitles" to it.take(5).joinToString(", ") + if (it.size > 5) " +${it.size - 5}" else "" }
            }
            dto.dateCreated?.let { out += "Added" to nbDate(it.toLocalDate().toString()) }
            out
        }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        facts.forEach { (label, value) ->
            Column(modifier = Modifier.width(260.dp)) {
                Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                Text(value, fontSize = 14.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Explore and Reference sections for a library title; used by the film and show pages. */
@Composable
fun NbJourneySection(item: BaseItem) {
    val steps = remember(item) { StreamingStore.libraryJourneys[key(item)].orEmpty() }
    if (steps.isEmpty()) return
    NbSection("Journey") { NbJourneyAcross(steps) }
}

@Composable
fun NbMoneyAndWhereSection(item: BaseItem) {
    val weekly = remember(item) { StreamingStore.libraryJourneys[key(item)].orEmpty().firstOrNull { it.weekly.isNotEmpty() }?.weekly.orEmpty() }
    val streaming by StreamingStore.library.collectAsState()
    val money by BoxOffice.library.collectAsState()
    val hasMoney = money[key(item)] != null
    val f = StreamingStore.libraryFacts[key(item)]
    val hasWhere = streaming[key(item)].orEmpty().isNotEmpty() || f?.streamOn != null || f?.freeOn != null || f?.rentBuy != null
    if (!hasMoney && !hasWhere) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HeaderUtils.startPadding + 8.dp),
    ) {
        if (hasMoney) {
            Box(Modifier.weight(1f)) { NbSection("Box office", inset = false) { NbBoxOffice(item, weekly) } }
        }
        if (hasWhere) {
            Box(Modifier.weight(1f)) { NbSection("Where else to watch", inset = false) { NbWhereToWatch(item) } }
        }
    }
}

@Composable
fun NbAboutSection(
    item: BaseItem,
    title: String,
) {
    NbSection(title) { NbAbout(item) }
}

/** "· Sci-fi, Adventure" after the meta line */
@Composable
fun NbGenresInline(item: BaseItem) {
    item.data.genres?.takeIf { it.isNotEmpty() }?.let {
        Text(
            buildAnnotatedString {
                pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)))
                append("  ·  " + it.take(3).joinToString(", "))
                pop()
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}


/** Header: the box office in one line ("💰 $714M worldwide · budget $190M · Hit (3.8×) · #6 of 2024"). */
@Composable
fun NbBoxOfficeLine(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val money by BoxOffice.library.collectAsState()
    val streaming by StreamingStore.library.collectAsState()
    val m = money[key(item)] ?: return
    val line = BoxOffice.line(m, streaming[key(item)].orEmpty()) ?: return
    Text(line, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Header: the file itself: "MKV · 29.4 GB · 27 Mbps", the drive it lives on, and when it was added. */
@Composable
fun NbFileChips(
    source: org.jellyfin.sdk.model.api.MediaSourceInfo?,
    added: java.time.LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    val file =
        listOfNotNull(
            source?.container?.uppercase(Locale.US)?.substringBefore(","),
            source?.size?.let { String.format(Locale.US, "%.1f GB", it / 1e9) },
            source?.bitrate?.let { String.format(Locale.US, "%.0f Mbps", it / 1e6) },
        ).joinToString(" · ")
    val drive = source?.path?.let { p -> Regex("/Volumes/([^/]+)/").find(p)?.groupValues?.get(1) }
    if (file.isEmpty() && drive == null && added == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        if (file.isNotEmpty()) NbChip("🗎 $file", Color.White.copy(alpha = 0.12f), Color.White)
        drive?.let { NbChip("💾 $it", Color(0xFF26A69A).copy(alpha = 0.8f), Color.White) }
        added?.let { NbChip("added " + nbDate(it.toLocalDate().toString()), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.8f)) }
    }
}


/**
 * NoBars: the browsing header's facts in three small labelled columns: RATINGS · BOX OFFICE · YOUR COPY
 * (or WHERE TO WATCH for a Netflix / Prime / Theatre title you don't have). Empty columns are left out.
 * Everything comes from lists already on the TV, so it keeps up with fast scrolling.
 */
@Composable
fun NbBrowseFacts(item: BaseItem?) {
    item ?: return
    val id = item.id
    val entry = StreamingStore.entries[id]
    val libId = entry?.let { StreamingStore.libraryId(it) } ?: id
    val libKey = libId.toString().replace("-", "")
    val columns =
        remember(id) {
            val out = mutableListOf<Triple<String, Color, Pair<String, String?>>>()
            // ratings
            val rating = item.data.communityRating
            val votes = ImdbVotesStore.get(id)?.votes ?: StreamingStore.votes(id)
            val critic =
                item.data.criticRating?.let { "🍅 ${it.toInt()}%" }
                    ?: CriticFallback.label(id)
                    ?: entry?.rt?.let { "🍅 $it%" }
                    ?: entry?.pop?.let { "🍿 $it%" }
            if (rating != null || critic != null) {
                val first = rating?.let { "IMDb " + String.format(Locale.US, "%.1f", it) + (votes?.let { v -> " · ${ImdbVotesStore.short(v)}" } ?: "") }
                val second = listOfNotNull(critic, if (rating != null && rating >= 8f) "8+" else null).joinToString(" · ").ifBlank { null }
                out += Triple("RATINGS", Color(0xFFF5C518), (first ?: second!!) to (if (first != null) second else null))
            }
            // box office
            BoxOffice.forItem(id)?.let { m ->
                val world = m.gross?.let { g -> (m.grossLocal ?: BoxOffice.short(g)) + " worldwide" }
                val rest =
                    listOfNotNull(
                        BoxOffice.verdict(m)?.let { v -> "$v " + String.format(Locale.US, "%.1f", BoxOffice.multiple(m) ?: 0.0) + "×" },
                        if (m.rank != null && m.year != null) "#${m.rank} of ${m.year}" else null,
                    ).joinToString(" · ").ifBlank { null }
                when {
                    world != null -> out += Triple("BOX OFFICE", Color(0xFF64B5F6), world to rest)
                    m.streamingOnly -> out += Triple("BOX OFFICE", Color(0xFF64B5F6), "Straight to streaming" to null)
                }
            }
            // your copy, or where to watch
            val file = StreamingStore.libraryFiles[libKey]
            if (file != null) {
                val second = listOfNotNull(file.size, file.drive).joinToString(" · ").ifBlank { null }
                out += Triple("YOUR COPY", Color(0xFF81C784), (file.av ?: second ?: "") to (if (file.av != null) second else null))
            } else if (entry != null && StreamingStore.libraryId(entry) == null) {
                val names = (entry.services + entry.ott.map { it.first }).distinct()
                if (names.isNotEmpty()) {
                    out +=
                        Triple(
                            "WHERE TO WATCH",
                            Color(0xFF81C784),
                            names.take(2).joinToString(" · ") to (if (names.size > 2) "+${names.size - 2} more" else entry.rentBuy?.let { "or rent, buy" }),
                        )
                }
            }
            out
        }
    if (columns.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
        columns.forEach { (label, color, lines) ->
            Row(modifier = Modifier.weight(1f).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(color))
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(label, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
                    Text(lines.first, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lines.second?.let {
                        Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // keep the columns the same width whether there are 2 or 3
        repeat(3 - columns.size) { Box(Modifier.weight(1f)) }
    }
}

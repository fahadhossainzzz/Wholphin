package com.github.damontecres.wholphin.ui.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.SizeStore
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.ui.seasonEpisode
import org.jellyfin.sdk.model.api.BaseItemKind

/** NoBars: the size's colour on poster lines (light blue). */
val PosterSizeColor = Color(0xFF7FD3FF)

/**
 * NoBars: the line under every poster: the year (a show's years, "S1 E2" for an episode in a row) and, for anything in
 * the library, its size on disk in light blue: "2021 · 22.3 GB". Titles you don't have show the year only. A streaming
 * or cinema card for a title you do have shows your copy's size.
 *
 * [base] replaces the year part (cards that already had their own line, e.g. an episode's "S1E2 - Name").
 */
@Composable
fun posterInfoText(
    item: BaseItem?,
    base: String? = null,
): AnnotatedString {
    LaunchedEffect(Unit) { SizeStore.refreshSoon() }
    val sizes by SizeStore.sizes.collectAsState()
    if (item == null) return AnnotatedString("")
    val first =
        base ?: when (item.type) {
            BaseItemKind.EPISODE -> item.data.seasonEpisode ?: item.subtitle
            BaseItemKind.SEASON -> item.subtitle
            else -> item.subtitle
        }
    val ownId = StreamingStore.entries[item.id]?.let { StreamingStore.libraryId(it) } ?: item.id
    val size = sizes[ownId]
    return buildAnnotatedString {
        append(first.orEmpty())
        if (size != null) {
            if (!first.isNullOrBlank()) append(" · ")
            withStyle(SpanStyle(color = PosterSizeColor, fontWeight = FontWeight.SemiBold)) {
                append(SizeStore.label(size, withEpisodes = false))
            }
        }
    }
}

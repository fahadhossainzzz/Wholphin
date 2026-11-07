package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.BoxOffice
import com.github.damontecres.wholphin.services.StreamingStore

/**
 * NoBars: two short lines under a film's details:
 *  "💰 $2.07B worldwide · budget $245M · Blockbuster (8.4×) · #1 of 2015"
 *  "$936M US · $1.13B rest of world · ≈ $2.8B in today's money · source: TMDB"
 * or "📺 Straight to streaming · on Netflix". Nothing when no figure is known.
 */
@Composable
fun BoxOfficeLine(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val library by BoxOffice.library.collectAsState()
    val streaming by StreamingStore.library.collectAsState()
    val key = item.id.toString().replace("-", "")
    val money = library[key] ?: return
    val first = BoxOffice.line(money, streaming[key].orEmpty()) ?: return
    val second = BoxOffice.detailLine(money)
    val color = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier) {
        Text(text = first, fontSize = 14.sp, maxLines = 1, color = color.copy(alpha = 0.88f))
        second?.let { Text(text = it, fontSize = 12.sp, maxLines = 1, color = color.copy(alpha = 0.65f)) }
    }
}

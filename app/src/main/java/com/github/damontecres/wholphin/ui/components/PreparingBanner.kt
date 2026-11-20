package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.services.PreparingStore

/** NoBars: "⏳ Preparing: Anbil Avan · subtitle" (+N more) on Home while the server makes a new download ready. */
@Composable
fun PreparingBanner(modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { PreparingStore.follow() }
    val items by PreparingStore.items.collectAsState()
    val first = items.firstOrNull() ?: return
    val more = items.size - 1
    Text(
        text = "⏳ Preparing: ${first.name} · ${first.stageText}" + (if (more > 0) "  (+$more more)" else ""),
        color = Color(0xFFFFD27F),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

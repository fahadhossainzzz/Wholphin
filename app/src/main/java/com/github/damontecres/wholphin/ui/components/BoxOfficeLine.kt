package com.github.damontecres.wholphin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.BoxOffice

/**
 * NoBars: "💰 $2.07B worldwide · budget $245M · 8.4× its budget · ≈ $2.8B in today's money" under a
 * film's details. Nothing when no figure is known.
 */
@Composable
fun BoxOfficeLine(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val library by BoxOffice.library.collectAsState()
    val money = library[item.id.toString().replace("-", "")] ?: return
    val text = BoxOffice.line(money) ?: return
    Text(
        text = text,
        fontSize = 14.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        modifier = modifier,
    )
}

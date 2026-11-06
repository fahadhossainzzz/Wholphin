package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.WatchProviders
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * NoBars: "Also on  [N] [prime]  (India)" under a film's or show's details. Each logo opens that
 * service's app on the TV. Nothing is shown when the title isn't on any streaming service.
 */
@Composable
fun WatchProvidersLine(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val tv = item.type == BaseItemKind.SERIES
    if (item.type != BaseItemKind.MOVIE && !tv) return
    val tmdbId = item.data.providerIds?.get("Tmdb") ?: return
    val providers by produceState(emptyList<WatchProviders.Provider>(), tmdbId) {
        value = WatchProviders.forTmdb(tmdbId, tv)
    }
    if (providers.isEmpty()) return
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = "Also on",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        providers.forEach { provider ->
            Surface(
                onClick = { WatchProviders.open(context, provider, item.name ?: "") },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.1f)),
                ) {
                    AsyncImage(
                        model = provider.logoUrl,
                        contentDescription = provider.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = "(${WatchProviders.REGION_NAME})",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

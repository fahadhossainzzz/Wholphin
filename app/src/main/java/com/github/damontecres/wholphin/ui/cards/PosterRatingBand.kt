package com.github.damontecres.wholphin.ui.cards

import com.github.damontecres.wholphin.services.forItem
import com.github.damontecres.wholphin.services.LocalImdbVotes
import com.github.damontecres.wholphin.services.ImdbVotesStore
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.components.FilledStarColor
import com.github.damontecres.wholphin.ui.util.LocalInterfaceCustomization
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

private val RATED_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)

/**
 * NoBars: a thin band along the bottom of a film, show, season or episode poster with its star rating (left) and
 * Rotten Tomatoes score (right). Shows only what the item has; nothing when it has neither or when
 * turned off in settings.
 */
@Composable
fun PosterRatingBand(
    item: BaseItem?,
    modifier: Modifier = Modifier,
) {
    if (!LocalInterfaceCustomization.current.showPosterRatings) return
    val dto = item?.data ?: return
    if (dto.type !in RATED_TYPES) return
    val stars = dto.communityRating?.takeIf { it > 0f }
    val tomatoes = dto.criticRating?.takeIf { it >= 0f }
    if (stars == null && tomatoes == null) return
    val votes = LocalImdbVotes.current.forItem(dto.id)?.votes ?: com.github.damontecres.wholphin.services.StreamingStore.votes(dto.id)
    val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val showVotes = votes != null && maxWidth >= 112.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        if (stars != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "★ " + String.format(Locale.US, "%.1f", stars),
                    style = style.copy(color = FilledStarColor),
                )
                if (showVotes) {
                    Text(
                        text = " · " + ImdbVotesStore.short(votes!!),
                        style = style.copy(fontSize = 9.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.7f)),
                        maxLines = 1,
                    )
                }
            }
        } else {
            Spacer(Modifier.size(1.dp))
        }
        if (tomatoes != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Image(
                    painter =
                        painterResource(
                            if (tomatoes >= 60f) R.drawable.ic_rotten_tomatoes_fresh else R.drawable.ic_rotten_tomatoes_rotten,
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
                Text(text = "${tomatoes.toInt()}%", style = style)
            }
        }
    }
    }
}

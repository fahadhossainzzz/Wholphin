package com.github.damontecres.wholphin.ui.cards

import com.github.damontecres.wholphin.services.forItem
import com.github.damontecres.wholphin.services.LocalImdbVotes
import com.github.damontecres.wholphin.services.ImdbVotesStore
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
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
 * NoBars: a thin band along the bottom of a film, show, season or episode poster with three fixed slots:
 * left ★ rating + vote count, middle box office, right 🍅 critics' score (or 🍿 audience score). A missing value
 * leaves its slot empty and nothing moves, so the numbers line up across a row. One text size for every poster;
 * when a band doesn't fit, its content is trimmed (money rounded, then the "%", then the votes), never its size.
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
    // NoBars: Jellyfin's own score, else the daily run's (OMDb / MDBList), else the audience 🍿 score
    val tomatoes =
        dto.criticRating?.takeIf { it >= 0f }
            ?: com.github.damontecres.wholphin.services.CriticFallback.tomatoes(dto.id)?.toFloat()
    val popcorn = if (tomatoes == null) com.github.damontecres.wholphin.services.CriticFallback.popcorn(dto.id) else null
    // NoBars: worldwide box office in the middle slot, films only
    val money =
        if (dto.type == BaseItemKind.MOVIE) {
            com.github.damontecres.wholphin.services.BoxOffice.forItem(dto.id)?.let {
                com.github.damontecres.wholphin.services.BoxOffice.posterLabel(it)
            }
        } else {
            null
        }
    if (stars == null && tomatoes == null && popcorn == null && money == null) return
    val votes = LocalImdbVotes.current.forItem(dto.id)?.votes ?: com.github.damontecres.wholphin.services.StreamingStore.votes(dto.id)

    val main = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    val starStyle = main.copy(color = FilledStarColor)
    val votesStyle = main.copy(fontSize = 9.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.65f))
    val moneyStyle = main.copy(fontSize = 9.5.sp, color = Color(0xFF9BE59B))
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sidePad = 4.dp
        val gap = 4.dp
        val iconW = 11.dp
        val iconGap = 2.dp
        val avail = with(density) { (maxWidth - sidePad * 2).toPx() }
        val gapPx = with(density) { gap.toPx() }

        fun w(
            text: String,
            style: TextStyle,
        ): Float = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toFloat()

        val starText = stars?.let { "★" + String.format(Locale.US, "%.1f", it) }
        val votesText = votes?.let { ImdbVotesStore.short(it) }
        val rounded = money?.let { roundMoney(it) }
        val tomatoText = tomatoes?.let { "${it.toInt()}%" }
        val popcornText = popcorn?.let { "🍿$it%" }

        // The plan for this poster: the first variant that fits (same text size in every variant)
        data class Plan(
            val votes: Boolean,
            val money: String?,
            val percent: Boolean,
        )
        val plans =
            listOf(
                Plan(true, money, true),
                Plan(true, rounded, true),
                Plan(true, rounded, false),
                Plan(false, rounded, false),
            )

        fun fits(p: Plan): Boolean {
            val left =
                (starText?.let { w(it, starStyle) } ?: 0f) +
                    if (p.votes && votesText != null) gapPx * 0.75f + w(votesText, votesStyle) else 0f
            val right =
                when {
                    tomatoText != null -> with(density) { (iconW + iconGap).toPx() } + w(if (p.percent) tomatoText else tomatoText.dropLast(1), main)
                    popcornText != null -> w(if (p.percent) popcornText else popcornText.dropLast(1), main)
                    else -> 0f
                }
            val middle = p.money?.let { w(it, moneyStyle) } ?: 0f
            // the money sits in the space between the left and right groups (not the exact centre), so the
            // vote count keeps its room
            return left + middle + right + (if (middle > 0f) 2 else 1) * gapPx <= avail
        }
        val plan = remember(maxWidth, starText, votesText, money, tomatoText, popcornText) { plans.firstOrNull { fits(it) } ?: plans.last() }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = sidePad, vertical = 3.dp),
        ) {
            // left: ★ rating + votes (an empty box keeps the other slots in place when there's no rating)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (starText != null) Text(text = starText, style = starStyle, maxLines = 1, softWrap = false)
                if (plan.votes && votesText != null) {
                    Text(
                        text = votesText,
                        style = votesStyle,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = gap * 0.75f),
                    )
                }
            }
            // middle: box office (or an empty spacer, so 🍅 stays on the right)
            if (plan.money != null) {
                Text(text = plan.money, style = moneyStyle, maxLines = 1, softWrap = false)
            }
            // right: 🍅 critics, or 🍿 audience
            if (tomatoes != null && tomatoText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(iconGap),
                ) {
                    Image(
                        painter =
                            painterResource(
                                if (tomatoes >= 60f) R.drawable.ic_rotten_tomatoes_fresh else R.drawable.ic_rotten_tomatoes_rotten,
                            ),
                        contentDescription = null,
                        modifier = Modifier.size(iconW),
                    )
                    Text(text = if (plan.percent) tomatoText else tomatoText.dropLast(1), style = main, maxLines = 1, softWrap = false)
                }
            } else if (popcornText != null) {
                Text(
                    text = if (plan.percent) popcornText else popcornText.dropLast(1),
                    style = main,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                Spacer(Modifier.size(1.dp))
            }
        }
    }
}

/** "$2.07B" -> "$2B", "₹2.4Cr" -> "₹2Cr", "₹1,852Cr" -> "₹1852Cr": the shorter form when space is tight. */
private fun roundMoney(label: String): String {
    val m = Regex("""^([^\d]*)([\d.,]+)(.*)$""").find(label) ?: return label
    val number = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return label
    return m.groupValues[1] + String.format(Locale.US, "%.0f", number) + m.groupValues[3]
}

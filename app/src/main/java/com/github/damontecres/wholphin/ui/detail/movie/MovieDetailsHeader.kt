package com.github.damontecres.wholphin.ui.detail.movie

import com.github.damontecres.wholphin.ui.components.WatchProvidersLine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.components.VideoStreamDetails
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PersonKind

@Composable
fun MovieDetailsHeader(
    preferences: UserPreferences,
    movie: BaseItem,
    chosenStreams: ChosenStreams?,
    bringIntoViewRequester: BringIntoViewRequester,
    overviewOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dto = movie.data
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        // Title
        TitleOrLogo(
            item = movie,
            showLogo = preferences.appPreferences.interfacePreferences.showLogos,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            // NoBars "glance" header: one meta line, then rating chips with the "Also on" logos, then the file chips
            // (box office, director and tagline moved to the sections below)
            androidx.compose.foundation.layout.Row(Modifier.padding(start = HeaderUtils.startPadding)) {
                QuickDetails(movie.ui.quickDetails, movie.timeRemainingOrRuntime, showRatings = false)
                com.github.damontecres.wholphin.ui.components.NbGenresInline(movie)
            }
            com.github.damontecres.wholphin.ui.components.NbRatingChips(movie, Modifier.padding(start = HeaderUtils.startPadding, top = 4.dp))
            com.github.damontecres.wholphin.ui.components.NbBoxOfficeLine(movie, Modifier.padding(start = HeaderUtils.startPadding, top = 2.dp))

            VideoStreamDetails(
                chosenStreams = chosenStreams,
                numberOfVersions = movie.data.mediaSourceCount ?: 0,
                modifier =
                    Modifier.padding(
                        start = HeaderUtils.startPadding,
                        top = 4.dp,
                    ),
            )
            com.github.damontecres.wholphin.ui.components.NbFileChips(
                source = chosenStreams?.source ?: movie.data.mediaSources?.firstOrNull(),
                added = movie.data.dateCreated,
                modifier = Modifier.padding(start = HeaderUtils.startPadding, bottom = 16.dp),
            )
            // Description
            dto.overview?.let { overview ->
                OverviewText(
                    overview = overview,
                    maxLines = 3,
                    onClick = overviewOnClick,
                    textBoxHeight = Dp.Unspecified,
                    modifier =
                        Modifier.onFocusChanged {
                            if (it.isFocused) {
                                scope.launch(ExceptionHandler()) {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                )
            }

        }
    }
}

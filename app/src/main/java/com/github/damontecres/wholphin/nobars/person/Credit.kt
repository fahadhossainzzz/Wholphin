package com.github.damontecres.wholphin.nobars.person

import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.ui.cards.PersonCard
import com.github.damontecres.wholphin.ui.cards.personRowCardWidth
import com.github.damontecres.wholphin.ui.components.HiddenFocusBox
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** One title in a person's career. [crew]: directing, writing or producing (with [role] the jobs). */
data class Credit(
    val tmdb: Int,
    val tv: Boolean,
    val title: String,
    val year: String,
    val date: String,
    val poster: String?,
    val rating: Float,
    val votes: Int,
    val role: String,
    val episodes: Int?,
    val crew: Boolean,
    val about: String,
    /** today's IMDb rating and votes (added by the server from its daily IMDb copy), when known */
    val imdbRating: Float? = null,
    val imdbVotes: Int? = null,
    /** 🍅 critics and 🍿 audience scores (MDBList, with the career), when known */
    val rt: Int? = null,
    val pop: Int? = null,
) {
    val key: String get() = (if (crew) "c" else "a") + (if (tv) "tv:" else "movie:") + tmdb
    val ownedKey: String get() = (if (tv) "tv:" else "movie:") + tmdb
    val posterSmall: String? get() = poster?.let { "https://image.tmdb.org/t/p/w92$it" }
    val posterLarge: String? get() = poster?.let { "https://image.tmdb.org/t/p/w185$it" }
}

/** NoBars: the person's credits from /Plugins/ImdbRatings/Person/{tmdbId}: acting merged per title, newest first. */
internal fun parseCredits(json: String): List<Credit> {
    val o = org.json.JSONObject(json)
    fun read(
        arr: org.json.JSONArray?,
        crew: Boolean,
    ): List<Credit> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val c = arr!!.getJSONObject(i)
            val id = c.optInt("id")
            if (id == 0) return@mapNotNull null
            Credit(
                tmdb = id,
                tv = c.optBoolean("tv"),
                title = c.optString("title"),
                year = c.optString("year"),
                date = c.optString("date"),
                poster = c.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                rating = c.optDouble("rating", 0.0).toFloat(),
                votes = c.optInt("votes"),
                role = c.optString("role"),
                episodes = if (c.has("eps")) c.optInt("eps") else null,
                crew = crew,
                about = c.optString("about"),
                imdbRating = c.optDouble("ir").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                imdbVotes = c.optInt("iv").takeIf { it > 0 },
                rt = if (c.has("rt")) c.optInt("rt") else null,
                pop = if (c.has("pc")) c.optInt("pc") else null,
            )
        }
    val acting =
        read(o.optJSONArray("cast"), false)
            .groupBy { it.tv to it.tmdb }
            .map { (_, same) ->
                same.first().copy(
                    role = same.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                    episodes = same.mapNotNull { it.episodes }.sum().takeIf { it > 0 },
                )
            }
    val behind = read(o.optJSONArray("crew"), true)
    val today = java.time.LocalDate.now().toString()
    val order = compareByDescending<Credit> { it.date.isEmpty() || it.date > today }.thenByDescending { it.date }
    return acting.sortedWith(order) + behind.sortedWith(order)
}

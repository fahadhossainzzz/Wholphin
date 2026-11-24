package com.github.damontecres.wholphin.nobars.history

import com.github.damontecres.wholphin.nobars.catalog.openTitle
import com.github.damontecres.wholphin.nobars.catalog.StreamingViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.WatchlistGenreFilter
import com.github.damontecres.wholphin.data.filter.WatchlistTypeFilter
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.nobars.ui.TraktMark
import com.github.damontecres.wholphin.nobars.ui.watchedLine
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NoBars: the History page: your Trakt watch history, as rows: Recently watched, then one row per year (newest
 * first). Each row shows 24 posters and "See all", a grid that loads as you scroll (Sort and Filter), so even years
 * of history open at once. Built on the Mac (posters and IMDb ratings added once per title) and handed to the TV one
 * row or one page at a time by the plugin.
 */
object HistoryShelf {
    data class Row(
        val id: String,
        val name: String,
        val total: Int,
        val items: List<StreamingStore.Entry>,
    )

    data class Page(
        val total: Int,
        val items: List<StreamingStore.Entry>,
        val genres: List<String>,
    )

    /** The Films and TV shows rows: Total, one kind only. */
    const val FILMS = "films"
    const val SHOWS = "shows"

    /** The rows as last seen, so coming back to the page shows them at once. */
    @Volatile var rows: List<Row>? = null

    private const val POSTER = "https://image.tmdb.org/t/p/w342"

    private fun entry(o: JSONObject): StreamingStore.Entry? {
        val key = o.optString("k")
        val series = key.startsWith("tv:")
        val tmdb = key.substringAfter(":").toIntOrNull() ?: return null
        val day = o.optString("d")
        val plays = o.optInt("n", 1)
        return StreamingStore.Entry(
            tmdb = tmdb,
            series = series,
            title = o.optString("t"),
            year = o.optInt("y").takeIf { it > 0 },
            poster = o.optString("p").takeIf { it.isNotBlank() }?.let { POSTER + it },
            // the header: when you watched it, then the start of the plot
            overview = "👁 " + (watchedLine(day to plays) ?: "Watched") + (o.optString("o").takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
            genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            runtime = o.optInt("rn").takeIf { it > 0 && !series },
            seasons = o.optInt("s").takeIf { it > 0 && series },
            imdbRating = o.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
            imdbVotes = o.optInt("v").takeIf { it > 0 },
            libraryId = null,
            rank = null,
        )
    }

    private fun entries(a: JSONArray?): List<StreamingStore.Entry> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::entry) } }.orEmpty()

    suspend fun loadRows(): List<Row>? =
        withContext(WholphinDispatchers.IO) {
            try {
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf") ?: return@withContext rows)
                val a = o.optJSONArray("rows") ?: JSONArray()
                val list =
                    (0 until a.length()).mapNotNull { i ->
                        val r = a.optJSONObject(i) ?: return@mapNotNull null
                        // the first row is everything you've watched: "Total"
                        val id = r.optString("id")
                        Row(id, if (id == "recent") "Total" else r.optString("name"), r.optInt("total"), entries(r.optJSONArray("items")))
                    }
                // after Total: Films and TV shows (Total filtered by kind; the same request as the grid's Films / Shows filter)
                val split =
                    kotlinx.coroutines.coroutineScope {
                        val films = async { page("recent", 0, 24, "watched", false, "movie", emptyList()) }
                        val tv = async { page("recent", 0, 24, "watched", false, "tv", emptyList()) }
                        listOfNotNull(
                            films.await()?.takeIf { it.total > 0 }?.let { Row(FILMS, "Films", it.total, it.items) },
                            tv.await()?.takeIf { it.total > 0 }?.let { Row(SHOWS, "TV shows", it.total, it.items) },
                        )
                    }
                (list.take(1) + split + list.drop(1)).also { rows = it }
            } catch (_: Exception) {
                rows
            }
        }

    suspend fun page(
        row: String,
        offset: Int,
        limit: Int,
        sort: String,
        reverse: Boolean,
        kind: String?,
        genres: List<String>,
    ): Page? =
        withContext(WholphinDispatchers.IO) {
            try {
                fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
                val q =
                    "row=${enc(row)}&offset=$offset&limit=$limit&sort=$sort&reverse=$reverse" +
                        (kind?.let { "&kind=$it" } ?: "") +
                        (if (genres.isNotEmpty()) "&genre=" + enc(genres.joinToString(",")) else "")
                val o = JSONObject(ServerAccess.get("/Plugins/ImdbRatings/History/Shelf?$q") ?: return@withContext null)
                val g = o.optJSONArray("genres")
                Page(o.optInt("total"), entries(o.optJSONArray("items")), g?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty())
            } catch (_: Exception) {
                null
            }
        }
}

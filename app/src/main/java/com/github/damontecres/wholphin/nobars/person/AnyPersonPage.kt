package com.github.damontecres.wholphin.nobars.person

import com.github.damontecres.wholphin.nobars.ui.QuickView
import com.github.damontecres.wholphin.nobars.catalog.StreamingViewModel
import com.github.damontecres.wholphin.ui.detail.PersonPageContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.nobars.person.Credit
import com.github.damontecres.wholphin.nobars.person.parseCredits
import com.github.damontecres.wholphin.util.RowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * NoBars: a person's profile from our plugin (/Plugins/ImdbRatings/Person/{tmdb}: TMDB, height from Trakt), the same
 * for anyone, in your library or not.
 */
data class PersonProfile(
    val name: String,
    val bio: String?,
    val born: LocalDate?,
    val died: LocalDate?,
    val place: String?,
    val known: String?,
    val photo: String?,
    val heightCm: Int?,
    val aka: List<String>,
    val photos: List<String>,
)

private const val TMDB_IMG = "https://image.tmdb.org/t/p/"

fun parsePersonProfile(json: String): PersonProfile? =
    try {
        val p = JSONObject(json).optJSONObject("profile")
        if (p == null) {
            null
        } else {
            fun str(k: String) = p.optString(k).takeIf { it.isNotBlank() && it != "null" }
            fun date(k: String) = str(k)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            fun list(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            PersonProfile(
                name = str("name") ?: "",
                bio = str("bio"),
                born = date("born"),
                died = date("died"),
                place = str("place"),
                known = str("known"),
                photo = str("photo")?.let { TMDB_IMG + "h632" + it },
                heightCm = p.optInt("height").takeIf { it in 50..260 },
                aka = list("aka"),
                photos = list("photos").map { TMDB_IMG + "w185" + it },
            )
        }
    } catch (_: Exception) {
        null
    }

/** The person's whole entry from our plugin (profile + career), kept 30 days on the server. */
suspend fun loadPersonJson(tmdb: Int): String? =
    withContext(WholphinDispatchers.IO) {
        try {
            ServerAccess.get("/Plugins/ImdbRatings/Person/$tmdb")
        } catch (_: Exception) {
            null
        }
    }

/**
 * The extra header lines: "Known for Acting · 1.78 m", the other names, and "Active 1992–2026 · 68 films · 12 series ·
 * 9 behind the camera" (worked out from the career).
 */
fun personExtraLines(
    profile: PersonProfile?,
    work: List<Credit>,
): List<String> {
    val out = mutableListOf<String>()
    val first =
        listOfNotNull(
            profile?.known?.let { "Known for $it" },
            profile?.heightCm?.let { String.format(Locale.US, "%.2f m", it / 100.0) },
        )
    if (first.isNotEmpty()) out += first.joinToString("  ·  ")
    if (!profile?.aka.isNullOrEmpty()) out += "Also known as " + profile!!.aka.joinToString(", ")
    if (work.isNotEmpty()) {
        val today = LocalDate.now().toString()
        val years = work.filter { it.date.isNotBlank() && it.date <= today }.mapNotNull { it.year.toIntOrNull() }
        val acting = work.filter { !it.crew }
        val films = acting.filter { !it.tv }.distinctBy { it.ownedKey }.size
        val series = acting.filter { it.tv }.distinctBy { it.ownedKey }.size
        val behind = work.filter { it.crew }.distinctBy { it.ownedKey }.size
        val parts =
            listOfNotNull(
                if (years.isNotEmpty()) "Active ${years.min()}–${years.max()}" else null,
                if (films > 0) "$films films" else null,
                if (series > 0) "$series series" else null,
                if (behind > 0) "$behind behind the camera" else null,
            )
        if (parts.isNotEmpty()) out += parts.joinToString("  ·  ")
    }
    return out
}

/**
 * A row of the person's other photos under the header. Each can be selected (so Down from the header always has
 * somewhere to go) and opens large.
 */
@Composable
fun PersonPhotoRow(
    photos: List<String>,
    modifier: Modifier = Modifier,
) {
    if (photos.size < 2) return
    var open by remember(photos) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text("Photos", style = MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(photos.drop(1).size) { i ->
                val url = photos.drop(1)[i]
                androidx.tv.material3.Surface(
                    onClick = { open = url },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    modifier = Modifier.width(100.dp).height(150.dp),
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    open?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { open = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = url.replace("/w185/", "/h632/"),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                )
            }
        }
    }
}

/**
 * NoBars: anyone's page, by their TMDB number: an actor in the cast of a title you don't have, a director, anyone.
 * Laid out like your library's person pages (header, photos, Known for / Films / Series / As themselves / Behind the
 * camera); a title in their rows opens its own page, so you can keep going from person to title to person.
 */
@Composable
fun AnyPersonPage(
    preferences: UserPreferences,
    tmdb: Int,
    name: String,
    modifier: Modifier = Modifier,
    viewModel: StreamingViewModel = hiltViewModel(),
) {
    var loaded by remember(tmdb) { mutableStateOf(false) }
    var profile by remember(tmdb) { mutableStateOf<PersonProfile?>(null) }
    var work by remember(tmdb) { mutableStateOf<List<Credit>>(emptyList()) }
    var showBio by remember(tmdb) { mutableStateOf(false) }
    LaunchedEffect(tmdb) {
        if (StreamingStore.owned.isEmpty()) StreamingStore.loadLibrary()
        val json = loadPersonJson(tmdb)
        if (json != null) {
            profile = parsePersonProfile(json)
            work = runCatching { parseCredits(json) }.getOrDefault(emptyList())
        }
        loaded = true
    }
    val p = profile
    when {
        !loaded -> LoadingPage(modifier)
        p == null && work.isEmpty() ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Couldn't load $name. Check that the server is on, then try again.")
            }
        else -> {
            val empty = RowLoadingState.Success(emptyList())
            PersonPageContent(
                preferences = preferences,
                name = p?.name?.ifBlank { null } ?: name,
                overview = p?.bio,
                imageUrl = p?.photo,
                birthdate = p?.born,
                deathdate = p?.died,
                birthPlace = p?.place,
                favorite = false,
                movies = empty,
                series = empty,
                episodes = empty,
                discovered = emptyList(),
                onClickItem = { _, _ -> },
                overviewOnClick = { showBio = true },
                favoriteOnClick = {},
                onClickDiscover = { _, _ -> },
                onClickViewMoreDiscover = {},
                enableViewMoreDiscover = false,
                work = work,
                onClickCredit = { c ->
                    val lib = StreamingStore.ownedId(c.ownedKey)
                    if (lib != null) {
                        viewModel.navigationManager.navigateTo(Destination.MediaItem(lib, if (c.tv) BaseItemKind.SERIES else BaseItemKind.MOVIE))
                    } else {
                        // the quick view (its buttons; "View details" for the whole title page)
                        QuickView.open(c.tmdb, c.tv, c.title, c.posterLarge)
                    }
                },
                extraLines = personExtraLines(p, work),
                photos = p?.photos.orEmpty(),
                showFavorite = false,
                modifier = modifier,
            )
            if (showBio) {
                ItemDetailsDialog(
                    info = ItemDetailsDialogInfo(title = p?.name ?: name, overview = p?.bio, genres = listOf(), files = listOf()),
                    showFilePath = false,
                    onDismissRequest = { showBio = false },
                )
            }
        }
    }
}

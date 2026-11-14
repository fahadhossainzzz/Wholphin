package com.github.damontecres.wholphin.ui.playback.overlay

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
import androidx.compose.runtime.remember
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

/** NoBars: card-size photo (the server otherwise sends the full original, often 1000x1500). */
private const val PERSON_PHOTO_HEIGHT = 300
private const val THUMB_HEIGHT = 150
private const val MAX_PEOPLE = 40

data class PersonPanelState(
    val person: Person,
    val loading: Boolean = true,
    val items: List<BaseItem> = emptyList(),
    val selected: BaseItem? = null,
    /** The film to highlight when coming back to the list from its summary. */
    val focusId: UUID? = null,
    /** NoBars: the profile (bio, dates, guest episodes) shown inside the panel instead of leaving the film. */
    val profileOpen: Boolean = false,
    val profileLoading: Boolean = false,
    val profile: PersonProfile? = null,
    val episodes: List<BaseItem> = emptyList(),
    val bioExpanded: Boolean = false,
    /** NoBars: "All work": the person's whole career from TMDB (fetched by our plugin, kept 30 days on the server). */
    val workOpen: Boolean = false,
    val workLoading: Boolean = false,
    val work: List<Credit>? = null,
    val workSelected: Credit? = null,
    val workFocus: String? = null,
)

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

data class PersonProfile(
    val overview: String?,
    val born: java.time.LocalDate?,
    val died: java.time.LocalDate?,
    val place: String?,
)

/**
 * NoBars: cast & crew for the film that is playing, and the light person panel shown over the film.
 * Lives beside the player and never touches it, so opening either costs no playback resources.
 */
@HiltViewModel
class PlayerPeopleViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        private val _people = MutableStateFlow<List<Person>>(emptyList())
        val people: StateFlow<List<Person>> = _people

        private val _panel = MutableStateFlow<PersonPanelState?>(null)
        val panel: StateFlow<PersonPanelState?> = _panel

        /** Set when the panel closes, so the Cast & Crew row reopens on the same person. */
        private val _returnTo = MutableStateFlow<UUID?>(null)
        val returnTo: StateFlow<UUID?> = _returnTo

        fun returnedToPerson() {
            _returnTo.value = null
        }

        private var loadedFor: UUID? = null
        private var playingId: UUID? = null

        fun load(item: BaseItem?) {
            if (item == null || item.id == loadedFor) return
            loadedFor = item.id
            playingId = item.id
            _people.value = emptyList()
            viewModelScope.launchIO {
                try {
                    val own = item.data.people ?: api.userLibraryApi.getItem(item.id).content.people
                    val series =
                        if (item.type == BaseItemKind.EPISODE) {
                            item.data.seriesId?.let { api.userLibraryApi.getItem(it).content.people }
                        } else {
                            null
                        }
                    val people =
                        (own.orEmpty() + series.orEmpty())
                            .distinctBy { it.id }
                            .take(MAX_PEOPLE)
                            .map {
                                Person.fromDto(context, it, api).copy(
                                    imageUrl =
                                        it.primaryImageTag?.let { tag ->
                                            api.imageApi.getItemImageUrl(
                                                it.id,
                                                ImageType.PRIMARY,
                                                tag = tag,
                                                maxHeight = PERSON_PHOTO_HEIGHT,
                                            )
                                        },
                                )
                            }
                    if (loadedFor == item.id) _people.value = people
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: could not load cast")
                }
            }
        }

        fun openPerson(person: Person) {
            _panel.value = PersonPanelState(person)
            viewModelScope.launchIO {
                val items =
                    try {
                        api.itemsApi
                            .getItems(
                                userId = serverRepository.currentUser?.id,
                                personIds = listOf(person.id),
                                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                                recursive = true,
                                sortBy = listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.PRODUCTION_YEAR),
                                sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING),
                                fields = listOf(ItemFields.OVERVIEW),
                                enableImageTypes = listOf(ImageType.PRIMARY),
                                imageTypeLimit = 1,
                                limit = 60,
                            ).content.items
                            .filter { it.id != playingId }
                            .map { BaseItem(it, false) }
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: could not load films for %s", person.name)
                        emptyList()
                    }
                _panel.update { current ->
                    if (current != null && current.person.id == person.id) current.copy(loading = false, items = items) else current
                }
            }
        }

        fun select(item: BaseItem?) = _panel.update { it?.copy(selected = item) }

        /** NoBars: the full profile, inside the panel. Bio and dates come from one small request. */
        fun openProfile() {
            val current = _panel.value ?: return
            _panel.value = current.copy(profileOpen = true, selected = null, focusId = null, bioExpanded = false)
            if (current.profile != null || current.profileLoading) return
            val personId = current.person.id
            _panel.update { it?.copy(profileLoading = true) }
            viewModelScope.launchIO {
                val info =
                    try {
                        val p = api.userLibraryApi.getItem(personId).content
                        PersonProfile(
                            overview = p.overview?.trim()?.takeIf { it.isNotEmpty() },
                            born = p.premiereDate?.toLocalDate(),
                            died = p.endDate?.toLocalDate(),
                            place = p.productionLocations?.firstOrNull(),
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: could not load profile")
                        PersonProfile(null, null, null, null)
                    }
                val episodes =
                    try {
                        api.itemsApi
                            .getItems(
                                userId = serverRepository.currentUser?.id,
                                personIds = listOf(personId),
                                includeItemTypes = listOf(BaseItemKind.EPISODE),
                                recursive = true,
                                sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                                sortOrder = listOf(SortOrder.DESCENDING),
                                fields = listOf(ItemFields.OVERVIEW),
                                enableImageTypes = listOf(ImageType.PRIMARY),
                                imageTypeLimit = 1,
                                limit = 30,
                            ).content.items
                            .filter { it.id != playingId }
                            .map { BaseItem(it, false) }
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: could not load episodes")
                        emptyList()
                    }
                _panel.update {
                    if (it != null && it.person.id == personId) {
                        it.copy(profile = info, episodes = episodes, profileLoading = false)
                    } else {
                        it
                    }
                }
            }
        }

        fun toggleBio() = _panel.update { it?.copy(bioExpanded = !it.bioExpanded) }

        /** NoBars: "All work", inside the panel. One request to our plugin, which keeps the answer for 30 days. */
        fun openWork() {
            val current = _panel.value ?: return
            _panel.value = current.copy(workOpen = true, workSelected = null, selected = null, focusId = null)
            if (current.work != null || current.workLoading) return
            val personId = current.person.id
            _panel.update { it?.copy(workLoading = true) }
            viewModelScope.launchIO {
                val credits =
                    try {
                        if (com.github.damontecres.wholphin.services.StreamingStore.owned.isEmpty()) {
                            com.github.damontecres.wholphin.services.StreamingStore.loadLibrary()
                        }
                        val tmdb = api.userLibraryApi.getItem(personId).content.providerIds?.get("Tmdb")
                        tmdb?.let { com.github.damontecres.wholphin.services.ServerAccess.get("/Plugins/ImdbRatings/Person/$it") }
                            ?.let(::parseCredits)
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: could not load all work")
                        null
                    }
                _panel.update {
                    if (it != null && it.person.id == personId) it.copy(work = credits ?: emptyList(), workLoading = false) else it
                }
            }
        }

        /** A title from "All work": one you have opens like the library list; others show their summary. */
        fun selectCredit(credit: Credit) {
            val hex = com.github.damontecres.wholphin.services.StreamingStore.owned[credit.ownedKey]
            if (hex == null || hex.length != 32) {
                _panel.update { it?.copy(workSelected = credit, workFocus = credit.key) }
                return
            }
            _panel.update { it?.copy(workFocus = credit.key) }
            viewModelScope.launchIO {
                try {
                    val id = UUID.fromString(hex.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                    val item = BaseItem(api.userLibraryApi.getItem(id).content, false)
                    _panel.update { it?.copy(selected = item) }
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: could not open %s", credit.title)
                    _panel.update { it?.copy(workSelected = credit) }
                }
            }
        }

        /** Back, one step: summary -> full bio -> profile -> the list -> the Cast & Crew row. */
        fun back() {
            val current = _panel.value ?: return
            if (current.selected != null) {
                _panel.value = current.copy(selected = null, focusId = current.selected.id)
            } else if (current.workSelected != null) {
                _panel.value = current.copy(workSelected = null, workFocus = current.workSelected.key)
            } else if (current.workOpen) {
                _panel.value = current.copy(workOpen = false, focusId = WORK_BUTTON)
            } else if (current.profileOpen && current.bioExpanded) {
                _panel.value = current.copy(bioExpanded = false, focusId = null)
            } else if (current.profileOpen) {
                _panel.value = current.copy(profileOpen = false, focusId = current.person.id)
            } else {
                _panel.value = null
                _returnTo.value = current.person.id
            }
        }

        fun close() {
            _panel.value = null
        }

        fun thumbUrl(item: BaseItem): String? =
            item.data.imageTags?.get(ImageType.PRIMARY)?.let {
                api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, tag = it, maxHeight = THUMB_HEIGHT)
            } ?: item.data.seriesPrimaryImageTag?.let { tag ->
                item.data.seriesId?.let { api.imageApi.getItemImageUrl(it, ImageType.PRIMARY, tag = tag, maxHeight = THUMB_HEIGHT) }
            }
    }

/** NoBars: the "Cast & Crew" row under the player's chapters and queue. */
@Composable
fun PeopleRowOverlay(
    people: List<Person>,
    controllerViewState: ControllerViewState,
    previousState: OverlayViewState,
    onChangeState: (OverlayViewState) -> Unit,
    onClickPerson: (Person) -> Unit,
    modifier: Modifier = Modifier,
    focusPersonId: UUID? = null,
    onFocusedPerson: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val hiddenFocusRequester = remember { FocusRequester() }
    val focusIndex = remember { people.indexOfFirst { it.id == focusPersonId }.coerceAtLeast(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(focusIndex)
    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
        onFocusedPerson()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        HiddenFocusBox(hiddenFocusRequester) {
            onChangeState.invoke(previousState)
        }
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.cast_and_crew),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRestorer(focusRequester)
                    .onFocusChanged { if (it.hasFocus) controllerViewState.pulseControls() },
        ) {
            itemsIndexed(people, key = { _, p -> p.id }) { index, person ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused = interactionSource.collectIsFocusedAsState().value
                LaunchedEffect(isFocused) { if (isFocused) controllerViewState.pulseControls() }
                PersonCard(
                    person = person,
                    onClick = {
                        controllerViewState.hideControls()
                        onClickPerson.invoke(person)
                    },
                    onLongClick = {},
                    interactionSource = interactionSource,
                    modifier =
                        Modifier
                            .width(personRowCardWidth)
                            .ifElse(index == focusIndex, Modifier.focusRequester(focusRequester)),
                )
            }
        }
    }
}

/**
 * NoBars: a see-through panel on the right with the person's films in the library, and their full
 * profile (bio, dates, guest episodes). The film keeps playing behind it; Back goes one step at a time.
 */
@Composable
fun PersonPanel(
    state: PersonPanelState,
    thumbUrl: (BaseItem) -> String?,
    onSelect: (BaseItem?) -> Unit,
    onBack: () -> Unit,
    onPlay: (BaseItem) -> Unit,
    onOpenItem: (BaseItem) -> Unit,
    onOpenProfile: (Person) -> Unit,
    onToggleBio: () -> Unit = {},
    onOpenWork: () -> Unit = {},
    onSelectCredit: (Credit) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onBack,
        // NoBars: Back is handled below, one step per press
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.let {
                it.setGravity(Gravity.END or Gravity.FILL_VERTICAL)
                it.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.setDimAmount(0.25f)
            }
        }
        val profileMode = state.profileOpen && state.selected == null
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .width(if (state.profileOpen || state.workOpen) 500.dp else 440.dp)
                    .fillMaxHeight()
                    .onPreviewKeyEvent {
                        if (it.key == Key.Back || it.key == Key.ButtonB) {
                            if (it.type == KeyEventType.KeyUp) onBack()
                            true
                        } else {
                            false
                        }
                    }
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = state.person.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(if (profileMode) 104.dp else 72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.person.name.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.person.role?.let {
                        Text(text = it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 2)
                    }
                    if (!state.loading) {
                        androidx.compose.runtime.remember(state.items) {
                            com.github.damontecres.wholphin.services.BoxOffice.totals(state.items)
                        }?.let {
                            Text(text = it, fontSize = 13.sp, color = Color(0xFF9BE59B), maxLines = 2)
                        }
                    }
                    if (profileMode) {
                        state.profile?.let { lifeLine(it) }?.let {
                            Text(text = it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2)
                        }
                    }
                }
            }
            val selected = state.selected
            val creditSelected = state.workSelected
            when {
                selected != null -> FilmSummary(selected, thumbUrl, onPlay, onOpenItem, onBack)
                creditSelected != null -> CreditSummary(creditSelected, onBack)
                state.workOpen -> WorkView(state, onSelectCredit)
                state.profileOpen -> ProfileView(state, thumbUrl, onSelect, onToggleBio)
                else -> FilmList(state, thumbUrl, onSelect, onOpenProfile, onOpenWork)
            }
        }
    }
}

@Composable
private fun FilmList(
    state: PersonPanelState,
    thumbUrl: (BaseItem) -> String?,
    onSelect: (BaseItem?) -> Unit,
    onOpenProfile: (Person) -> Unit,
    onOpenWork: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val onWork = state.focusId == WORK_BUTTON
    val onButton = state.focusId == state.person.id || onWork
    val focusIndex = remember(state.loading) { state.items.indexOfFirst { it.id == state.focusId }.coerceAtLeast(0) }
    // +1: the Full profile button sits above the films
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(if (onButton) 0 else focusIndex + 1)
    LaunchedEffect(state.loading) { if (!state.loading) first.tryRequestFocus() }
    Text(
        text =
            when {
                state.loading -> "Looking in your library…"
                state.items.isEmpty() -> "Nothing else in your library"
                else -> "In your library (${state.items.size})"
            },
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.85f),
    )
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!state.loading) {
            item(key = "profile") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Button(
                        onClick = { onOpenProfile(state.person) },
                        modifier = Modifier.ifElse(!onWork && (onButton || state.items.isEmpty()), Modifier.focusRequester(first)),
                    ) {
                        Text("Full profile")
                    }
                    Button(
                        onClick = onOpenWork,
                        modifier = Modifier.ifElse(onWork, Modifier.focusRequester(first)),
                    ) {
                        Text("All work")
                    }
                }
            }
        }
        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
            FilmRow(item, thumbUrl, onSelect, Modifier.ifElse(!onButton && index == focusIndex, Modifier.focusRequester(first)))
        }
    }
}

@Composable
private fun FilmRow(
    item: BaseItem,
    thumbUrl: (BaseItem) -> String?,
    onSelect: (BaseItem?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = { onSelect(item) },
        leadingContent = { Thumb(thumbUrl(item)) },
        headlineContent = {
            Text(title(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(facts(item), fontSize = 12.sp, maxLines = 1) },
        modifier = modifier,
    )
}

private sealed interface ProfileRow {
    val key: Any

    data class Header(
        val text: String,
    ) : ProfileRow {
        override val key: Any get() = "h:$text"
    }

    data class Bio(
        val index: Int,
        val text: String,
        val short: Boolean,
    ) : ProfileRow {
        override val key: Any get() = "bio:$index"
    }

    data class Film(
        val item: BaseItem,
    ) : ProfileRow {
        override val key: Any get() = item.id
    }

    data class Note(
        val text: String,
    ) : ProfileRow {
        override val key: Any get() = "n:$text"
    }
}

/** NoBars: the person's profile inside the panel: bio, then films, shows and guest episodes. */
@Composable
private fun ProfileView(
    state: PersonPanelState,
    thumbUrl: (BaseItem) -> String?,
    onSelect: (BaseItem?) -> Unit,
    onToggleBio: () -> Unit,
) {
    val rows =
        remember(state.profile, state.items, state.episodes, state.bioExpanded, state.profileLoading) {
            buildList {
                val bio = state.profile?.overview
                when {
                    state.profileLoading -> add(ProfileRow.Note("Loading profile…"))
                    bio == null -> add(ProfileRow.Note("No biography on the server"))
                    state.bioExpanded -> bioChunks(bio).forEachIndexed { i, t -> add(ProfileRow.Bio(i, t, false)) }
                    else -> add(ProfileRow.Bio(0, bio, true))
                }
                val films = state.items.filter { it.type == BaseItemKind.MOVIE }
                val shows = state.items.filter { it.type == BaseItemKind.SERIES }
                if (films.isNotEmpty()) {
                    add(ProfileRow.Header("Films (${films.size})"))
                    films.forEach { add(ProfileRow.Film(it)) }
                }
                if (shows.isNotEmpty()) {
                    add(ProfileRow.Header("Shows (${shows.size})"))
                    shows.forEach { add(ProfileRow.Film(it)) }
                }
                if (state.episodes.isNotEmpty()) {
                    add(ProfileRow.Header("Guest episodes (${state.episodes.size})"))
                    state.episodes.forEach { add(ProfileRow.Film(it)) }
                }
            }
        }
    val target =
        remember(rows, state.focusId) {
            rows.indexOfFirst { it is ProfileRow.Film && it.item.id == state.focusId }.takeIf { it >= 0 }
                ?: rows.indexOfFirst { it is ProfileRow.Bio || it is ProfileRow.Film }
        }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(target.coerceAtLeast(0))
    LaunchedEffect(target, state.bioExpanded) {
        if (target >= 0) {
            listState.scrollToItem(target)
            first.tryRequestFocus()
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
            val focusMod = Modifier.ifElse(index == target, Modifier.focusRequester(first))
            when (row) {
                is ProfileRow.Header ->
                    Text(
                        text = row.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                is ProfileRow.Note ->
                    Text(text = row.text, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                is ProfileRow.Bio ->
                    androidx.tv.material3.Surface(
                        onClick = onToggleBio,
                        colors =
                            androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                contentColor = Color.White.copy(alpha = 0.88f),
                                focusedContentColor = Color.White,
                            ),
                        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = focusMod.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = row.text,
                                fontSize = 14.sp,
                                maxLines = if (row.short) 8 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.short) {
                                Text("More ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                is ProfileRow.Film -> FilmRow(row.item, thumbUrl, onSelect, focusMod)
            }
        }
    }
}

/** NoBars: the whole bio in focusable pieces of a few sentences, so the remote can scroll through it. */
private fun bioChunks(text: String): List<String> {
    val out = mutableListOf<String>()
    text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
        val sentences = para.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > 360) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
    }
    return out
}

private val DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** NoBars: "Born 12 Mar 1975 (51) · London", or the years and age at death. */
private fun lifeLine(p: PersonProfile): String? {
    val parts = mutableListOf<String>()
    val born = p.born
    when {
        born != null && p.died != null -> {
            val age = java.time.Period.between(born, p.died).years
            parts += "${born.year} – ${p.died.year} (aged $age)"
        }
        born != null -> {
            val age = java.time.Period.between(born, java.time.LocalDate.now()).years
            parts += "Born ${born.format(DAY)} ($age)"
        }
        p.died != null -> parts += "Died ${p.died.format(DAY)}"
    }
    p.place?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

private fun title(item: BaseItem): String =
    if (item.type == BaseItemKind.EPISODE) {
        item.data.seriesName ?: item.name.orEmpty()
    } else {
        item.name.orEmpty()
    }

private fun episodeLabel(item: BaseItem): String? {
    if (item.type != BaseItemKind.EPISODE) return null
    val s = item.data.parentIndexNumber
    val e = item.data.indexNumber
    val code = if (s != null && e != null) "S$s·E$e" else null
    return listOfNotNull(code, item.name).joinToString(" ").takeIf { it.isNotBlank() }
}

/** Focus marker for the "All work" button (any id that is never a real person). */
internal val WORK_BUTTON: UUID = UUID(0L, 0xA11L)

private sealed interface WorkRow {
    val key: Any

    data class Header(
        val text: String,
    ) : WorkRow {
        override val key: Any get() = "h:$text"
    }

    data class Note(
        val text: String,
    ) : WorkRow {
        override val key: Any get() = "n:$text"
    }

    data class Item(
        val credit: Credit,
    ) : WorkRow {
        override val key: Any get() = credit.key
    }
}

/** NoBars: the person's whole career: upcoming, acting, then directing / writing / producing. */
@Composable
private fun WorkView(
    state: PersonPanelState,
    onSelectCredit: (Credit) -> Unit,
) {
    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
    val rows =
        remember(state.work, state.workLoading) {
            buildList {
                val work = state.work
                when {
                    state.workLoading || work == null -> add(WorkRow.Note("Looking up their whole career…"))
                    work.isEmpty() -> add(WorkRow.Note("No career list available for this person"))
                    else -> {
                        val today = java.time.LocalDate.now().toString()
                        val acting = work.filter { !it.crew }
                        val upcoming = acting.filter { it.date.isEmpty() || it.date > today }
                        val past = acting - upcoming.toSet()
                        val behind = work.filter { it.crew }
                        if (upcoming.isNotEmpty()) {
                            add(WorkRow.Header("Upcoming (${upcoming.size})"))
                            upcoming.forEach { add(WorkRow.Item(it)) }
                        }
                        if (past.isNotEmpty()) {
                            add(WorkRow.Header("Acting (${past.size})"))
                            past.forEach { add(WorkRow.Item(it)) }
                        }
                        if (behind.isNotEmpty()) {
                            add(WorkRow.Header("Directing, writing, producing (${behind.size})"))
                            behind.forEach { add(WorkRow.Item(it)) }
                        }
                    }
                }
            }
        }
    val target =
        remember(rows, state.workFocus) {
            rows.indexOfFirst { it is WorkRow.Item && it.credit.key == state.workFocus }.takeIf { it >= 0 }
                ?: rows.indexOfFirst { it is WorkRow.Item }
        }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState((target - 1).coerceAtLeast(0))
    LaunchedEffect(target) { if (target >= 0) first.tryRequestFocus() }
    val total = state.work?.size ?: 0
    Text(
        text = if (total > 0) "All work ($total)" else "All work",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.85f),
    )
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
            when (row) {
                is WorkRow.Header ->
                    Text(
                        text = row.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                is WorkRow.Note -> Text(text = row.text, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                is WorkRow.Item -> {
                    val c = row.credit
                    ListItem(
                        selected = false,
                        onClick = { onSelectCredit(c) },
                        leadingContent = { Thumb(c.posterSmall) },
                        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(creditFacts(c, wanted), fontSize = 12.sp, maxLines = 1) },
                        modifier = Modifier.ifElse(index == target, Modifier.focusRequester(first)),
                    )
                }
            }
        }
    }
}

private fun creditFacts(
    c: Credit,
    wanted: List<com.github.damontecres.wholphin.services.StreamingStore.Entry>,
): String =
    buildList {
        when {
            com.github.damontecres.wholphin.services.StreamingStore.owned.containsKey(c.ownedKey) -> add("✓ In your library")
            wanted.any { it.tmdb == c.tmdb && it.series == c.tv } -> add("🔖 Watchlist")
        }
        if (c.year.isNotBlank()) add(c.year) else add("Upcoming")
        if (c.tv) add(c.episodes?.let { "TV · $it ep" } ?: "TV")
        if (c.role.isNotBlank()) add(if (c.crew) c.role else "as ${c.role}")
        if (c.votes >= 20 && c.rating > 0f) add("★ " + String.format(Locale.US, "%.1f", c.rating))
    }.joinToString("  ·  ")

/** NoBars: a title from "All work" that isn't in your library: what it is, and the Watchlist. */
@Composable
private fun CreditSummary(
    c: Credit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(c.key) { first.tryRequestFocus() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val wanted by com.github.damontecres.wholphin.services.WantedStore.items.collectAsState()
    val onList = wanted.any { it.tmdb == c.tmdb && it.series == c.tv }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Thumb(c.posterLarge, large = true)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 3)
            Text(
                listOfNotNull(
                    c.year.ifBlank { "Upcoming" },
                    if (c.tv) "TV show" else "Film",
                    c.episodes?.let { "$it episodes" },
                    if (c.votes >= 20 && c.rating > 0f) "★ " + String.format(Locale.US, "%.1f", c.rating) + " (TMDB)" else null,
                ).joinToString("  ·  "),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f),
            )
            if (c.role.isNotBlank()) {
                Text(if (c.crew) c.role else "as ${c.role}", fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                scope.launch {
                    val entry =
                        com.github.damontecres.wholphin.services.StreamingStore.cachedEntry(c.tmdb, c.tv)
                            ?: com.github.damontecres.wholphin.services.StreamingStore.Entry(
                                tmdb = c.tmdb,
                                series = c.tv,
                                title = c.title,
                                year = c.year.toIntOrNull(),
                                poster = c.poster?.let { "https://image.tmdb.org/t/p/w342$it" },
                                overview = c.about,
                                genres = emptyList(),
                                runtime = null,
                                seasons = null,
                                imdbRating = null,
                                imdbVotes = null,
                                libraryId = null,
                                rank = null,
                                tmdbRating = c.rating.takeIf { c.votes >= 20 && it > 0f },
                                tmdbVotes = c.votes.takeIf { it > 0 },
                            )
                    com.github.damontecres.wholphin.services.WantedStore.toggle(entry, "people")
                }
            },
            modifier = Modifier.focusRequester(first),
        ) {
            Text(if (onList) "✓ On your Watchlist" else "＋ Watchlist")
        }
        Button(onClick = onBack) { Text("Back") }
    }
    if (c.about.isNotBlank()) {
        Text(text = c.about, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun FilmSummary(
    item: BaseItem,
    thumbUrl: (BaseItem) -> String?,
    onPlay: (BaseItem) -> Unit,
    onOpenItem: (BaseItem) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(item.id) { first.tryRequestFocus() }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Thumb(thumbUrl(item), large = true)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title(item), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 3)
            episodeLabel(item)?.let {
                Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
            }
            Text(facts(item, withRuntime = true), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.EPISODE) {
            Button(onClick = { onPlay(item) }, modifier = Modifier.focusRequester(first)) {
                Text(if (item.resumeMs > 0) "Resume" else "Play")
            }
        } else {
            Button(onClick = { onOpenItem(item) }, modifier = Modifier.focusRequester(first)) {
                Text("Open show")
            }
        }
        Button(onClick = onBack) { Text("Back") }
    }
    // NoBars: the full description below the buttons, scrolled with ▼ / ▲ (▲ at the top goes back to the buttons)
    item.data.overview?.takeIf { it.isNotBlank() }?.let {
        val scroll = androidx.compose.foundation.rememberScrollState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Box(
            modifier =
                Modifier
                    .heightIn(max = 300.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                scope.launch { scroll.animateScrollTo((scroll.value + 220).coerceAtMost(scroll.maxValue)) }
                                true
                            }

                            androidx.compose.ui.input.key.Key.DirectionUp -> {
                                if (scroll.value > 0) {
                                    scope.launch { scroll.animateScrollTo((scroll.value - 220).coerceAtLeast(0)) }
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> {
                                false
                            }
                        }
                    }.focusable(),
        ) {
            Text(
                text = it,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun Thumb(
    url: String?,
    large: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .size(if (large) 80.dp else 40.dp, if (large) 120.dp else 60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.08f)),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun facts(
    item: BaseItem,
    withRuntime: Boolean = false,
): String =
    buildList {
        if (!withRuntime) episodeLabel(item)?.let { add(it) }
        item.data.productionYear?.let { add(it.toString()) }
        if (item.type == BaseItemKind.SERIES) add("TV show")
        item.data.communityRating?.takeIf { it > 0f }?.let {
            val votes = com.github.damontecres.wholphin.services.ImdbVotesStore.get(item.id)?.votes
            add("★ " + String.format(Locale.US, "%.1f", it) + (votes?.let { v -> " (" + com.github.damontecres.wholphin.services.ImdbVotesStore.short(v) + ")" } ?: ""))
        }
        item.data.criticRating?.takeIf { it >= 0f }?.let { add("🍅 ${it.toInt()}%") }
            ?: com.github.damontecres.wholphin.services.CriticFallback.label(item.id)?.let { add(it) }
        if (withRuntime) {
            com.github.damontecres.wholphin.services.BoxOffice.forItem(item.id)?.let { m ->
                com.github.damontecres.wholphin.services.BoxOffice.compact(m)?.let { add(it) }
            }
            item.data.runTimeTicks?.let { ticks ->
                val min = ticks / 600_000_000L
                if (min > 0) add(if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min")
            }
        }
    }.joinToString("  ·  ")

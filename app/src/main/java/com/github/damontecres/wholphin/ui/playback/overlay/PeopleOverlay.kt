package com.github.damontecres.wholphin.ui.playback.overlay

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

        /** Back: from a film's summary to the list, from the list to the film playing. */
        fun back() =
            _panel.update {
                if (it?.selected != null) it.copy(selected = null) else null
            }

        fun close() {
            _panel.value = null
        }

        fun thumbUrl(item: BaseItem): String? =
            item.data.imageTags?.get(ImageType.PRIMARY)?.let {
                api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, tag = it, maxHeight = THUMB_HEIGHT)
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
) {
    val focusRequester = remember { FocusRequester() }
    val hiddenFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
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
                            .ifElse(index == 0, Modifier.focusRequester(focusRequester)),
                )
            }
        }
    }
}

/**
 * NoBars: a see-through panel on the right with the person's films in the library. The film keeps
 * playing behind it; Back closes it.
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
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.let {
                it.setGravity(Gravity.END or Gravity.FILL_VERTICAL)
                it.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.setDimAmount(0.25f)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .width(440.dp)
                    .fillMaxHeight()
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
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column {
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
                }
            }
            val selected = state.selected
            if (selected != null) {
                FilmSummary(selected, thumbUrl, onPlay, onOpenItem, onBack)
            } else {
                FilmList(state, thumbUrl, onSelect, onOpenProfile)
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
) {
    val first = remember { FocusRequester() }
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
            ListItem(
                selected = false,
                onClick = { onSelect(item) },
                leadingContent = { Thumb(thumbUrl(item)) },
                headlineContent = {
                    Text(item.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(facts(item), fontSize = 12.sp, maxLines = 1) },
                modifier = Modifier.ifElse(index == 0, Modifier.focusRequester(first)),
            )
        }
        if (!state.loading) {
            item {
                Button(
                    onClick = { onOpenProfile(state.person) },
                    modifier =
                        Modifier
                            .padding(top = 12.dp)
                            .ifElse(state.items.isEmpty(), Modifier.focusRequester(first)),
                ) {
                    Text("Open full profile")
                }
            }
        }
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
            Text(item.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 3)
            Text(facts(item, withRuntime = true), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
    item.data.overview?.let {
        Text(
            text = it,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 9,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.type == BaseItemKind.MOVIE) {
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
        item.data.productionYear?.let { add(it.toString()) }
        if (item.type == BaseItemKind.SERIES) add("TV show")
        item.data.communityRating?.takeIf { it > 0f }?.let { add("★ " + String.format(Locale.US, "%.1f", it)) }
        item.data.criticRating?.takeIf { it >= 0f }?.let { add("🍅 ${it.toInt()}%") }
        if (withRuntime) {
            item.data.runTimeTicks?.let { ticks ->
                val min = ticks / 600_000_000L
                if (min > 0) add(if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min")
            }
        }
    }.joinToString("  ·  ")

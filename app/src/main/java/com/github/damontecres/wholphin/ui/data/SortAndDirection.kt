package com.github.damontecres.wholphin.ui.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.damontecres.wholphin.R
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

@Serializable
data class SortAndDirection(
    val sort: ItemSortBy,
    val direction: SortOrder,
) {
    fun flip() = copy(direction = direction.flip())

    companion object {
        val DEFAULT =
            SortAndDirection(
                ItemSortBy.SORT_NAME,
                SortOrder.ASCENDING,
            )
    }
}

fun SortOrder.flip() = if (this == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING

val MovieSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.OFFICIAL_RATING,
        ItemSortBy.RUNTIME,
        ItemSortBy.PLAY_COUNT,
        ItemSortBy.RANDOM,
    )

/**
 * NoBars: the money orders ride on sort values Jellyfin never uses for films. The app never sends them to the
 * server: [com.github.damontecres.wholphin.util.MoneySortRequestHandler] sorts these on the TV.
 */
object MoneySortCarrier {
    private val map =
        mapOf(
            ItemSortBy.START_DATE to com.github.damontecres.wholphin.services.MoneySort.BOX_OFFICE,
            ItemSortBy.AIR_TIME to com.github.damontecres.wholphin.services.MoneySort.TODAYS_MONEY,
            ItemSortBy.VIDEO_BIT_RATE to com.github.damontecres.wholphin.services.MoneySort.BUDGET,
            ItemSortBy.STUDIO to com.github.damontecres.wholphin.services.MoneySort.RETURN,
            ItemSortBy.IS_FOLDER to com.github.damontecres.wholphin.services.MoneySort.YEAR_RANK,
        )

    fun of(sort: ItemSortBy?): com.github.damontecres.wholphin.services.MoneySort? = sort?.let { map[it] }

    val options: List<ItemSortBy> = map.keys.toList()
}

/** NoBars: IMDb rating weighted by its number of votes (IMDb's Top 250 method), worked out on the TV */
val WeightedImdbSort: ItemSortBy = ItemSortBy.NAME

/** NoBars: the Movies library's sort menu, with the weighted IMDb order and the money orders after the ratings. */
val MovieLibrarySortOptions =
    MovieSortOptions.flatMap {
        when (it) {
            ItemSortBy.COMMUNITY_RATING -> listOf(it, WeightedImdbSort)
            ItemSortBy.CRITIC_RATING -> listOf(it) + MoneySortCarrier.options
            else -> listOf(it)
        }
    }

/** NoBars: the custom row builder's sorts (films and shows alike). */
val CustomRowSortOptions =
    listOf(
        ItemSortBy.DATE_CREATED,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.COMMUNITY_RATING,
        WeightedImdbSort,
        ItemSortBy.CRITIC_RATING,
    ) + MoneySortCarrier.options +
        listOf(
            ItemSortBy.RUNTIME,
            ItemSortBy.SORT_NAME,
            ItemSortBy.DATE_PLAYED,
            ItemSortBy.PLAY_COUNT,
            ItemSortBy.RANDOM,
        )

val SeriesSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_LAST_CONTENT_ADDED,
        ItemSortBy.SERIES_DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.OFFICIAL_RATING,
        ItemSortBy.RANDOM,
    )

val EpisodeSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.AIRED_EPISODE_ORDER,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.OFFICIAL_RATING,
        ItemSortBy.RUNTIME,
        ItemSortBy.PLAY_COUNT,
        ItemSortBy.RANDOM,
    )

val VideoSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.OFFICIAL_RATING,
        ItemSortBy.RUNTIME,
        ItemSortBy.PLAY_COUNT,
        ItemSortBy.RANDOM,
    )

val PlaylistSortOptions =
    listOf(
        ItemSortBy.DEFAULT,
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.OFFICIAL_RATING,
        ItemSortBy.RUNTIME,
        ItemSortBy.PLAY_COUNT,
        ItemSortBy.RANDOM,
    )

/** NoBars: collections get the weighted IMDb order and the money orders too (sorted on the TV, see NbQuery). */
val BoxSetSortOptions =
    listOf(
        ItemSortBy.DEFAULT,
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        WeightedImdbSort,
        ItemSortBy.CRITIC_RATING,
    ) + MoneySortCarrier.options +
        listOf(
            ItemSortBy.OFFICIAL_RATING,
            ItemSortBy.RUNTIME,
            ItemSortBy.PLAY_COUNT,
            ItemSortBy.RANDOM,
        )

/**
 * NoBars: the direction an order starts in when it is picked: best rated, most money, newest, most played first;
 * names (and the rest) A to Z. Picking the same order again flips it.
 */
fun defaultDirection(sort: ItemSortBy): SortOrder =
    when (sort) {
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        WeightedImdbSort,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_LAST_CONTENT_ADDED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.SERIES_DATE_PLAYED,
        ItemSortBy.PLAY_COUNT,
        -> SortOrder.DESCENDING

        else -> if (MoneySortCarrier.of(sort) != null) SortOrder.DESCENDING else SortOrder.ASCENDING
    }

val AlbumSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.ALBUM_ARTIST,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.RANDOM,
    )

val ArtistSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.RANDOM,
    )

val SongSortOptions =
    listOf(
        ItemSortBy.SORT_NAME,
        ItemSortBy.PREMIERE_DATE,
        ItemSortBy.ALBUM,
        ItemSortBy.ALBUM_ARTIST,
        ItemSortBy.ARTIST,
        ItemSortBy.DATE_CREATED,
        ItemSortBy.DATE_PLAYED,
        ItemSortBy.COMMUNITY_RATING,
        ItemSortBy.CRITIC_RATING,
        ItemSortBy.PLAY_COUNT,
        ItemSortBy.RUNTIME,
        ItemSortBy.RANDOM,
    )

@StringRes
fun getStringRes(sort: ItemSortBy): Int =
    when (sort) {
        ItemSortBy.SORT_NAME,
        ItemSortBy.SERIES_SORT_NAME,
        -> R.string.sort_by_name

        ItemSortBy.PREMIERE_DATE -> R.string.sort_by_date_released

        ItemSortBy.DATE_CREATED -> R.string.sort_by_date_added

        ItemSortBy.DATE_LAST_CONTENT_ADDED -> R.string.sort_by_date_episode_added

        ItemSortBy.DATE_PLAYED,
        ItemSortBy.SERIES_DATE_PLAYED,
        -> R.string.sort_by_date_played

        ItemSortBy.RANDOM -> R.string.sort_by_random

        ItemSortBy.COMMUNITY_RATING -> R.string.community_rating

        ItemSortBy.CRITIC_RATING -> R.string.critic_rating

        ItemSortBy.OFFICIAL_RATING -> R.string.official_rating

        ItemSortBy.PLAY_COUNT -> R.string.play_count

        ItemSortBy.AIRED_EPISODE_ORDER -> R.string.aired_episode_order

        ItemSortBy.RUNTIME -> R.string.runtime_sort

        ItemSortBy.DEFAULT -> R.string.default_track

        ItemSortBy.ALBUM -> R.string.album

        ItemSortBy.ALBUM_ARTIST -> R.string.album_artist

        ItemSortBy.ARTIST -> R.string.artist

        // NoBars: TV-side orders (see MoneySortCarrier, WeightedImdbSort)
        ItemSortBy.NAME -> R.string.sort_by_weighted_imdb

        ItemSortBy.START_DATE -> R.string.sort_by_box_office

        ItemSortBy.AIR_TIME -> R.string.sort_by_box_office_today

        ItemSortBy.VIDEO_BIT_RATE -> R.string.sort_by_budget

        ItemSortBy.STUDIO -> R.string.sort_by_return

        ItemSortBy.IS_FOLDER -> R.string.sort_by_year_rank

        else -> throw IllegalArgumentException("Unsupported sort option: $sort")
    }

@Composable
fun rememberSortOptions(collectionType: CollectionType): List<ItemSortBy> =
    remember(collectionType) {
        when (collectionType) {
            CollectionType.MOVIES -> MovieSortOptions
            CollectionType.TVSHOWS -> SeriesSortOptions
            CollectionType.MUSIC -> AlbumSortOptions
            CollectionType.BOXSETS -> BoxSetSortOptions
            else -> VideoSortOptions
        }
    }

@Composable
fun rememberSortOptions(type: BaseItemKind): List<ItemSortBy> =
    remember(type) {
        when (type) {
            BaseItemKind.MOVIE -> MovieSortOptions
            BaseItemKind.SERIES -> SeriesSortOptions
            BaseItemKind.SEASON -> SeriesSortOptions
            BaseItemKind.EPISODE -> EpisodeSortOptions
            BaseItemKind.AUDIO -> SongSortOptions
            BaseItemKind.MUSIC_ALBUM -> AlbumSortOptions
            BaseItemKind.MUSIC_ARTIST -> ArtistSortOptions
            BaseItemKind.BOX_SET -> BoxSetSortOptions
            else -> VideoSortOptions
        }
    }

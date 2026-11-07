package com.github.damontecres.wholphin.data.filter

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import java.util.UUID

val DefaultFilterOptions =
    listOf(
        PlayedFilter,
        FavoriteFilter,
        GenreFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

val DefaultTvFilterOptions =
    listOf(
        PlayedFilter,
        FavoriteFilter,
        GenreFilter,
        StudioFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

val DefaultForFavoritesFilterOptions =
    listOf(
        PlayedFilter,
        GenreFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

val DefaultForGenresFilterOptions =
    listOf(
        PlayedFilter,
        FavoriteFilter,
        StudioFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

val DefaultForStudiosFilterOptions =
    listOf(
        PlayedFilter,
        FavoriteFilter,
        GenreFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

val DefaultPlaylistItemsOptions =
    listOf(
        PlayedFilter,
        FavoriteFilter,
        CommunityRatingFilter,
        OfficialRatingFilter,
        VideoTypeFilter,
        YearFilter,
        DecadeFilter,
    )

/**
 * Abstract way to filter data
 */
sealed interface FilterBy<FilterType, DataType> {
    @get:StringRes
    val stringRes: Int

    val supportMultiple: Boolean

    fun get(filter: FilterType): DataType?

    fun set(
        value: DataType?,
        filter: FilterType,
    ): FilterType
}

/**
 * A way to filter jellyfin libraries
 *
 * Gets and sets values within a [GetItemsFilter]
 */
sealed interface ItemFilterBy<T> : FilterBy<GetItemsFilter, T>

data object GenreFilter : ItemFilterBy<List<UUID>> {
    override val stringRes: Int = R.string.genres

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<UUID>? = filter.genres

    override fun set(
        value: List<UUID>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(genres = value)
}

data object PlayedFilter : ItemFilterBy<Boolean> {
    override val stringRes: Int = R.string.played

    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Boolean? = filter.played

    override fun set(
        value: Boolean?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(played = value)
}

data object FavoriteFilter : ItemFilterBy<Boolean> {
    override val stringRes: Int = R.string.favorites

    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Boolean? = filter.favorite

    override fun set(
        value: Boolean?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(favorite = value)
}

data object OfficialRatingFilter : ItemFilterBy<List<String>> {
    override val stringRes: Int = R.string.official_rating

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<String>? = filter.officialRatings

    override fun set(
        value: List<String>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(officialRatings = value)
}

enum class FilterVideoType(
    val readable: String,
) {
    FOUR_K("4K"),
    HD("HD"),
    SD("SD"),
    THREE_D("3D"),
    BLU_RAY("Blu-Ray"),
    DVD("DVD"),
}

data object VideoTypeFilter : ItemFilterBy<List<FilterVideoType>> {
    override val stringRes: Int = R.string.video

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<FilterVideoType>? = filter.videoTypes

    override fun set(
        value: List<FilterVideoType>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(videoTypes = value)
}

data object YearFilter : ItemFilterBy<List<Int>> {
    override val stringRes: Int = R.string.year

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<Int>? = filter.years

    override fun set(
        value: List<Int>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(years = value)
}

data object DecadeFilter : ItemFilterBy<List<Int>> {
    override val stringRes: Int = R.string.decade

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<Int>? = filter.decades

    override fun set(
        value: List<Int>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(decades = value)
}

data object CommunityRatingFilter : ItemFilterBy<Int> {
    override val stringRes: Int = R.string.community_rating

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): Int? = filter.minCommunityRating?.toInt()

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(minCommunityRating = value?.toDouble())
}

data object StudioFilter : ItemFilterBy<List<UUID>> {
    override val stringRes: Int = R.string.studios

    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<UUID>? = filter.studios

    override fun set(
        value: List<UUID>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(studios = value)
}

/**
 * NoBars: our extra filters. Each is one of a few kinds so the filter menu handles them all the same way:
 * pick one number ([NbIntFilter]), pick one option ([NbStringFilter]), yes/no ([NbBoolFilter]), or pick several
 * ([NbUuidListFilter], [NbStringListFilter]).
 */
sealed interface NbFilterBy<T> : ItemFilterBy<T>

sealed interface NbIntFilter : NbFilterBy<Int>

sealed interface NbStringFilter : NbFilterBy<String>

sealed interface NbBoolFilter : NbFilterBy<Boolean>

sealed interface NbUuidListFilter : NbFilterBy<List<UUID>>

sealed interface NbStringListFilter : NbFilterBy<List<String>>

private fun GetItemsFilter.extraOrEmpty() = extra ?: com.github.damontecres.wholphin.data.model.NbExtraFilter()

private fun GetItemsFilter.withExtra(change: (com.github.damontecres.wholphin.data.model.NbExtraFilter) -> com.github.damontecres.wholphin.data.model.NbExtraFilter) =
    copy(extra = change(extraOrEmpty()).orNull())

data object TagFilter : NbStringListFilter {
    override val stringRes: Int = R.string.nb_filter_tags
    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<String>? = filter.tags

    override fun set(
        value: List<String>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.copy(tags = value)
}

data object ExcludeTagFilter : NbStringListFilter {
    override val stringRes: Int = R.string.nb_filter_exclude_tags
    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<String>? = filter.extra?.excludeTags

    override fun set(
        value: List<String>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(excludeTags = value) }
}

data object ExcludeGenreFilter : NbUuidListFilter {
    override val stringRes: Int = R.string.nb_filter_exclude_genres
    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<UUID>? = filter.extra?.excludeGenres

    override fun set(
        value: List<UUID>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(excludeGenres = value) }
}

data object CollectionMembershipFilter : NbUuidListFilter {
    override val stringRes: Int = R.string.nb_filter_collections
    override val supportMultiple: Boolean = true

    override fun get(filter: GetItemsFilter): List<UUID>? = filter.extra?.collections

    override fun set(
        value: List<UUID>?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(collections = value) }
}

data object MinVotesFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_min_votes
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.minVotes

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(minVotes = value) }
}

data object MinTomatoesFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_min_tomatoes
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.minTomatoes

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(minTomatoes = value) }
}

data object MinBoxOfficeFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_box_office
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.minGrossMillions

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(minGrossMillions = value) }
}

data object VerdictFilter : NbStringFilter {
    override val stringRes: Int = R.string.nb_filter_verdict
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): String? = filter.extra?.verdict

    override fun set(
        value: String?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(verdict = value) }
}

data object YearRankFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_year_rank
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.maxYearRank

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(maxYearRank = value) }
}

data object StreamingReleaseFilter : NbStringFilter {
    override val stringRes: Int = R.string.nb_filter_streaming_release
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): String? = filter.extra?.streamingRelease

    override fun set(
        value: String?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(streamingRelease = value) }
}

data object OnServiceFilter : NbStringFilter {
    override val stringRes: Int = R.string.nb_filter_on_service
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): String? = filter.extra?.onService

    override fun set(
        value: String?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(onService = value) }
}

data object RuntimeFilter : NbStringFilter {
    override val stringRes: Int = R.string.nb_filter_runtime
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): String? = filter.extra?.runtime

    override fun set(
        value: String?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(runtime = value) }
}

data object AddedWithinFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_added_within
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.addedWithinDays

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(addedWithinDays = value) }
}

data object ReleasedWithinFilter : NbIntFilter {
    override val stringRes: Int = R.string.nb_filter_released_within
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Int? = filter.extra?.releasedWithinDays

    override fun set(
        value: Int?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(releasedWithinDays = value) }
}

data object SubtitlesFilter : NbBoolFilter {
    override val stringRes: Int = R.string.nb_filter_subtitles
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Boolean? = filter.extra?.hasSubtitles

    override fun set(
        value: Boolean?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(hasSubtitles = value) }
}

data object InProgressFilter : NbBoolFilter {
    override val stringRes: Int = R.string.nb_filter_in_progress
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Boolean? = filter.extra?.inProgress

    override fun set(
        value: Boolean?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(inProgress = value?.takeIf { it }) }
}

data object WantedFilter : NbBoolFilter {
    override val stringRes: Int = R.string.nb_filter_wanted
    override val supportMultiple: Boolean = false

    override fun get(filter: GetItemsFilter): Boolean? = filter.extra?.wantedOnly

    override fun set(
        value: Boolean?,
        filter: GetItemsFilter,
    ): GetItemsFilter = filter.withExtra { it.copy(wantedOnly = value?.takeIf { it }) }
}

/** NoBars: the options our filters offer (the lists with server data come from the filter option cache). */
object NbFilterChoices {
    val minVotes =
        listOf(1_000 to "1K+", 10_000 to "10K+", 50_000 to "50K+", 100_000 to "100K+", 500_000 to "500K+", 1_000_000 to "1M+")
    val minTomatoes = listOf(60 to "60%+ (fresh)", 70 to "70%+", 80 to "80%+", 90 to "90%+")
    val boxOffice = listOf(10 to "$10M+", 50 to "$50M+", 100 to "$100M+", 500 to "$500M+", 1000 to "$1B+")
    val verdict = listOf("Blockbuster" to "Blockbuster", "Hit" to "Hit", "Break-even" to "Break-even", "Flop" to "Flop")
    val yearRank = listOf(1 to "#1 of its year", 3 to "Top 3 of its year", 5 to "Top 5", 10 to "Top 10")
    val streamingRelease = listOf("only" to "Only streaming films", "exclude" to "Leave streaming films out")
    val onService = listOf("netflix" to "On Netflix", "prime" to "On Prime Video", "either" to "On either", "neither" to "On neither")
    val runtime = listOf("lt90" to "Under 1 h 30", "lt120" to "Under 2 h", "gt120" to "Over 2 h", "gt150" to "Over 2 h 30")
    val addedWithin = listOf(7 to "Last 7 days", 30 to "Last 30 days", 90 to "Last 3 months", 365 to "Last year")
    val releasedWithin = listOf(90 to "Last 3 months", 365 to "Last year", 1825 to "Last 5 years", 3650 to "Last 10 years")
}

/** NoBars: Films library filter menu (the original ones first, then ours). */
val NbMovieFilterOptions =
    DefaultFilterOptions + StudioFilter +
        listOf(
            TagFilter,
            ExcludeGenreFilter,
            ExcludeTagFilter,
            CollectionMembershipFilter,
            MinVotesFilter,
            MinTomatoesFilter,
            MinBoxOfficeFilter,
            VerdictFilter,
            YearRankFilter,
            StreamingReleaseFilter,
            OnServiceFilter,
            RuntimeFilter,
            AddedWithinFilter,
            ReleasedWithinFilter,
            SubtitlesFilter,
            InProgressFilter,
            WantedFilter,
        )

/** NoBars: TV shows library filter menu */
val NbTvFilterOptions =
    DefaultTvFilterOptions +
        listOf(
            TagFilter,
            ExcludeGenreFilter,
            ExcludeTagFilter,
            CollectionMembershipFilter,
            MinVotesFilter,
            MinTomatoesFilter,
            OnServiceFilter,
            AddedWithinFilter,
            ReleasedWithinFilter,
            InProgressFilter,
        )

package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.NbExtraFilter
import com.github.damontecres.wholphin.ui.data.MoneySortCarrier
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.time.LocalDateTime
import java.util.UUID

/** NoBars: orders the TV works out itself (Jellyfin has no such fields). */
enum class ClientSort { WEIGHTED_IMDB, BOX_OFFICE, TODAYS_MONEY, BUDGET, RETURN, YEAR_RANK }

/**
 * NoBars: the filter engine behind the library filter menu's own filters and custom Home rows.
 * Jellyfin does what it can (the request); then one light request lists every matching item (ids and a few fields,
 * a few KB), which is filtered and ordered here; the posters are then fetched page by page by id
 * ([com.github.damontecres.wholphin.util.OrderedIdsRequestHandler]).
 */
object NbQuery {
    fun clientSort(sort: ItemSortBy?): ClientSort? {
        if (sort == ItemSortBy.NAME) return ClientSort.WEIGHTED_IMDB
        return when (MoneySortCarrier.of(sort)) {
            MoneySort.BOX_OFFICE -> ClientSort.BOX_OFFICE
            MoneySort.TODAYS_MONEY -> ClientSort.TODAYS_MONEY
            MoneySort.BUDGET -> ClientSort.BUDGET
            MoneySort.RETURN -> ClientSort.RETURN
            MoneySort.YEAR_RANK -> ClientSort.YEAR_RANK
            null -> null
        }
    }

    /** True when the TV has to list and filter/sort the items itself. */
    fun needed(
        extra: NbExtraFilter?,
        sort: ItemSortBy?,
    ): Boolean = extra?.needsClient == true || clientSort(sort) != null

    /**
     * Every matching item's id, in order. [request] carries Jellyfin's filters (and its sort, used as-is when there is
     * no [ClientSort]).
     */
    suspend fun orderedIds(
        api: ApiClient,
        request: GetItemsRequest,
        extra: NbExtraFilter?,
        sort: ClientSort?,
        descending: Boolean,
    ): List<UUID> {
        val light =
            request.copy(
                ids = null,
                startIndex = null,
                limit = null,
                sortBy = if (sort != null) listOf(ItemSortBy.SORT_NAME) else (request.sortBy ?: listOf(ItemSortBy.SORT_NAME)),
                sortOrder = if (sort != null) listOf(SortOrder.ASCENDING) else request.sortOrder,
                fields = listOf(ItemFields.GENRES, ItemFields.TAGS, ItemFields.DATE_CREATED, ItemFields.PROVIDER_IDS),
                enableImages = false,
                enableUserData = false,
                enableTotalRecordCount = false,
            )
        val items = api.itemsApi.getItems(light).content.items
        val inCollections =
            extra?.collections?.takeIf { it.isNotEmpty() }?.let { ids ->
                ids
                    .flatMap { collectionId ->
                        api.itemsApi
                            .getItems(
                                userId = request.userId,
                                parentId = collectionId,
                                recursive = true,
                                enableImages = false,
                                enableUserData = false,
                            ).content.items
                            .map { it.id }
                    }.toSet()
            }
        val kept = if (extra == null) items else items.filter { matches(it, extra, inCollections) }
        if (sort == null) return kept.map { it.id }
        val valued = kept.map { it to sortValue(sort, it) }
        val (known, unknown) = valued.partition { it.second != null }
        return ((if (descending) known.sortedByDescending { it.second!! } else known.sortedBy { it.second!! }) + unknown).map { it.first.id }
    }

    private fun key(id: UUID) = id.toString().replace("-", "")

    private fun sortValue(
        sort: ClientSort,
        dto: BaseItemDto,
    ): Double? =
        when (sort) {
            ClientSort.WEIGHTED_IMDB ->
                dto.communityRating?.takeIf { it > 0f }?.let {
                    ImdbVotesStore.weighted(it, ImdbVotesStore.get(dto.id)?.votes).toDouble()
                }
            ClientSort.BOX_OFFICE -> BoxOffice.sortValue(MoneySort.BOX_OFFICE, BoxOffice.forItem(dto.id))
            ClientSort.TODAYS_MONEY -> BoxOffice.sortValue(MoneySort.TODAYS_MONEY, BoxOffice.forItem(dto.id))
            ClientSort.BUDGET -> BoxOffice.sortValue(MoneySort.BUDGET, BoxOffice.forItem(dto.id))
            ClientSort.RETURN -> BoxOffice.sortValue(MoneySort.RETURN, BoxOffice.forItem(dto.id))
            ClientSort.YEAR_RANK -> BoxOffice.sortValue(MoneySort.YEAR_RANK, BoxOffice.forItem(dto.id))
        }

    private fun matches(
        dto: BaseItemDto,
        f: NbExtraFilter,
        inCollections: Set<UUID>?,
    ): Boolean {
        f.excludeGenres?.takeIf { it.isNotEmpty() }?.let { ex ->
            if (dto.genreItems.orEmpty().any { it.id in ex }) return false
        }
        f.excludeTags?.takeIf { it.isNotEmpty() }?.let { ex ->
            if (dto.tags.orEmpty().any { it in ex }) return false
        }
        if (inCollections != null && dto.id !in inCollections) return false
        f.minVotes?.let { min -> if ((ImdbVotesStore.get(dto.id)?.votes ?: 0) < min) return false }
        f.minTomatoes?.let { min ->
            val rt = dto.criticRating?.takeIf { it >= 0f }?.toInt() ?: CriticFallback.tomatoes(dto.id)
            if (rt == null || rt < min) return false
        }
        val money by lazy { BoxOffice.forItem(dto.id) }
        f.minGrossMillions?.let { min -> if ((money?.gross ?: 0L) < min * 1_000_000L) return false }
        f.verdict?.let { v -> if (money?.let { BoxOffice.verdict(it) } != v) return false }
        f.maxYearRank?.let { max -> if ((money?.rank ?: Int.MAX_VALUE) > max) return false }
        f.streamingRelease?.let { mode ->
            val streaming = money?.let { it.streamingOnly || it.streamingFilm } == true
            if (mode == "only" && !streaming) return false
            if (mode == "exclude" && streaming) return false
        }
        f.onService?.let { mode ->
            val on = StreamingStore.library.value[key(dto.id)].orEmpty()
            val ok =
                when (mode) {
                    "netflix" -> StreamingStore.NETFLIX in on
                    "prime" -> StreamingStore.PRIME in on
                    "either" -> on.isNotEmpty()
                    "neither" -> on.isEmpty()
                    else -> true
                }
            if (!ok) return false
        }
        f.runtime?.let { r ->
            val minutes = dto.runTimeTicks?.let { it / 600_000_000L } ?: return false
            val ok =
                when (r) {
                    "lt90" -> minutes < 90
                    "lt120" -> minutes < 120
                    "gt120" -> minutes > 120
                    "gt150" -> minutes > 150
                    else -> true
                }
            if (!ok) return false
        }
        f.addedWithinDays?.let { days ->
            val created = dto.dateCreated ?: return false
            if (created.isBefore(LocalDateTime.now().minusDays(days.toLong()))) return false
        }
        if (f.wantedOnly == true) {
            val tmdb = dto.providerIds?.get("Tmdb")?.toIntOrNull() ?: return false
            if (WantedStore.items.value.none { it.tmdb == tmdb }) return false
        }
        return true
    }
}

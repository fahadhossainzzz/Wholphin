package com.nobars.jellyfin

import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** NoBars: what the phone app shows from the library. Every call needs a signed-in [NbJellyfin]. */
class Library internal constructor(
    private val nb: NbJellyfin,
) {
    data class Page(
        val items: List<BaseItemDto>,
        val total: Int,
    )

    private val api get() = nb.api
    private val uid get() = nb.session.value?.userId
    private val fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES)

    suspend fun resume(): List<BaseItemDto> =
        api.itemsApi
            .getResumeItems(userId = uid, limit = 24, mediaTypes = listOf(MediaType.VIDEO), fields = fields, enableUserData = true)
            .content.items

    suspend fun nextUp(): List<BaseItemDto> =
        api.tvShowsApi
            .getNextUp(userId = uid, limit = 24, fields = fields, enableUserData = true)
            .content.items

    suspend fun latest(kind: BaseItemKind): List<BaseItemDto> =
        api.userLibraryApi
            .getLatestMedia(userId = uid, includeItemTypes = listOf(kind), limit = 24, fields = fields, enableUserData = true)
            .content

    suspend fun items(
        kind: BaseItemKind,
        sort: ItemSortBy,
        descending: Boolean,
        start: Int,
        limit: Int = 100,
    ): Page {
        val r =
            api.itemsApi
                .getItems(
                    userId = uid,
                    includeItemTypes = listOf(kind),
                    recursive = true,
                    sortBy = listOf(sort, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                    startIndex = start,
                    limit = limit,
                    fields = fields,
                    enableUserData = true,
                    enableTotalRecordCount = true,
                ).content
        return Page(r.items, r.totalRecordCount)
    }

    suspend fun search(term: String): List<BaseItemDto> =
        api.itemsApi
            .getItems(
                userId = uid,
                searchTerm = term,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                recursive = true,
                limit = 60,
                fields = fields,
                enableUserData = true,
            ).content.items

    suspend fun item(id: UUID): BaseItemDto = api.userLibraryApi.getItem(itemId = id, userId = uid).content

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.tvShowsApi.getSeasons(seriesId = seriesId, userId = uid, enableUserData = true).content.items

    suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID,
    ): List<BaseItemDto> =
        api.tvShowsApi
            .getEpisodes(seriesId = seriesId, userId = uid, fields = fields, seasonId = seasonId, enableUserData = true)
            .content.items

    /** Poster (2:3). Episodes and seasons use their series' poster when they have none of their own. */
    fun poster(
        item: BaseItemDto,
        height: Int = 450,
    ): String? {
        item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            if (item.type != BaseItemKind.EPISODE) {
                return api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, maxHeight = height, tag = tag)
            }
        }
        val series = item.seriesId ?: return item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, maxHeight = height, tag = tag)
        }
        return api.imageApi.getItemImageUrl(series, ImageType.PRIMARY, maxHeight = height, tag = item.seriesPrimaryImageTag)
    }

    /** Wide picture (16:9): an episode's own still, else the backdrop. */
    fun wide(
        item: BaseItemDto,
        width: Int = 640,
    ): String? {
        if (item.type == BaseItemKind.EPISODE) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                return api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, maxWidth = width, tag = tag)
            }
        }
        return backdrop(item, width)
    }

    fun backdrop(
        item: BaseItemDto,
        width: Int = 1280,
    ): String? {
        item.backdropImageTags?.firstOrNull()?.let { tag ->
            return api.imageApi.getItemImageUrl(item.id, ImageType.BACKDROP, maxWidth = width, tag = tag, imageIndex = 0)
        }
        val parent = item.parentBackdropItemId ?: item.seriesId ?: return null
        return api.imageApi.getItemImageUrl(parent, ImageType.BACKDROP, maxWidth = width, imageIndex = 0)
    }
}

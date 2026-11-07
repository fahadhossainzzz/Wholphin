@file:UseSerializers(UUIDSerializer::class)

package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/**
 * NoBars: filters Jellyfin can't do by itself, shared by the library filter menu and custom Home rows. The parts
 * Jellyfin can do (subtitles, in progress, released within) go to the server; the rest is checked on the TV from a
 * light list of the matching items (see [com.github.damontecres.wholphin.services.NbQuery]).
 */
@Serializable
data class NbExtraFilter(
    val excludeGenres: List<UUID>? = null,
    val excludeTags: List<String>? = null,
    /** Items in any of these collections (e.g. the importer's "Quality • 4K", "Audio • Atmos", "Theme • …") */
    val collections: List<UUID>? = null,
    val minVotes: Int? = null,
    /** Rotten Tomatoes: Jellyfin's score, else the daily run's */
    val minTomatoes: Int? = null,
    /** Worldwide gross, millions of US dollars */
    val minGrossMillions: Int? = null,
    /** Blockbuster, Hit, Break-even or Flop */
    val verdict: String? = null,
    val maxYearRank: Int? = null,
    /** "only" or "exclude" films that went straight to streaming / had only a limited cinema run */
    val streamingRelease: String? = null,
    /** "netflix", "prime", "either" or "neither" */
    val onService: String? = null,
    /** "lt90", "lt120", "gt120", "gt150" */
    val runtime: String? = null,
    val addedWithinDays: Int? = null,
    val releasedWithinDays: Int? = null,
    val hasSubtitles: Boolean? = null,
    val inProgress: Boolean? = null,
    /** Only films from the Cinema page's "Want it" list that have arrived */
    val wantedOnly: Boolean? = null,
) {
    /** True when some of these must be checked on the TV (the rest Jellyfin does itself). */
    val needsClient: Boolean
        get() =
            !excludeGenres.isNullOrEmpty() || !excludeTags.isNullOrEmpty() || !collections.isNullOrEmpty() ||
                minVotes != null || minTomatoes != null || minGrossMillions != null || verdict != null ||
                maxYearRank != null || streamingRelease != null || onService != null || runtime != null ||
                addedWithinDays != null || wantedOnly == true

    val isEmpty: Boolean
        get() = this == NbExtraFilter()

    fun orNull(): NbExtraFilter? = takeIf { !it.isEmpty }
}

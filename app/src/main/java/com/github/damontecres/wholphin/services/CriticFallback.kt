package com.github.damontecres.wholphin.services

import org.json.JSONObject
import java.util.UUID

/**
 * NoBars: Rotten Tomatoes for titles Jellyfin has none for (its own lookup misses many recent films and every TV
 * show). The Mac's daily run fills them from OMDb and MDBList; where critics have no score yet, the audience
 * 🍿 score is used instead.
 */
object CriticFallback {
    @Volatile private var tomatoes: Map<String, Int> = emptyMap()

    @Volatile private var popcorn: Map<String, Int> = emptyMap()

    fun load(
        rt: JSONObject?,
        pop: JSONObject?,
    ) {
        tomatoes = rt?.toIntMap() ?: emptyMap()
        popcorn = pop?.toIntMap() ?: emptyMap()
    }

    private fun JSONObject.toIntMap(): Map<String, Int> = keys().asSequence().associateWith { optInt(it) }

    private fun key(id: UUID) = id.toString().replace("-", "")

    /** Critics' score when Jellyfin has none. */
    fun tomatoes(id: UUID?): Int? = id?.let { tomatoes[key(it)] }

    /** Audience score, only for titles with no critics' score anywhere. */
    fun popcorn(id: UUID?): Int? = id?.let { popcorn[key(it)] ?: StreamingStore.entries[it]?.pop }

    /** "🍅 75%" or "🍿 87%" for a title Jellyfin shows no critics' score for. */
    fun label(id: UUID?): String? = tomatoes(id)?.let { "🍅 $it%" } ?: popcorn(id)?.let { "🍿 $it%" }
}

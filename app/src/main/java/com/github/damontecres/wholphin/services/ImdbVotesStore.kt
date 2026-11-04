package com.github.damontecres.wholphin.services

import androidx.compose.runtime.compositionLocalOf
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * NoBars: IMDb votes for the whole library, served by our fork of the IMDb Ratings NG plugin at
 * /Plugins/ImdbRatings/Votes. Fetched once when the app starts (about 30 KB) and kept in memory.
 */
object ImdbVotesStore {
    data class Vote(
        val rating: Float,
        val votes: Int,
    )

    private val _votes = MutableStateFlow<Map<String, Vote>>(emptyMap())
    val votes: StateFlow<Map<String, Vote>> = _votes

    /** Average rating across the library, the "C" in IMDb's weighted rating. */
    @Volatile
    var meanRating: Float = 6.8f
        private set

    /** Votes needed before a rating counts fully, the "m" in IMDb's weighted rating. */
    private const val MIN_VOTES = 10_000f

    fun key(id: UUID): String = id.toString().replace("-", "")

    fun get(id: UUID?): Vote? = id?.let { _votes.value[key(it)] }

    suspend fun load(
        baseUrl: String?,
        token: String?,
    ) {
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return
        withContext(WholphinDispatchers.IO) {
            try {
                val conn = URL(baseUrl.trimEnd('/') + "/Plugins/ImdbRatings/Votes").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                // Jellyfin 12 only accepts the Authorization header (the old X-Emby-Token is rejected with 401)
                conn.setRequestProperty("Authorization", "MediaBrowser Token=\"$token\"")
                conn.setRequestProperty("Accept", "application/json")
                try {
                    if (conn.responseCode != 200) {
                        Timber.i("NoBars: IMDb votes not available (HTTP %d)", conn.responseCode)
                        return@withContext
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val map = HashMap<String, Vote>(json.length() * 2)
                    var sum = 0.0
                    json.keys().forEach { k ->
                        val o = json.optJSONObject(k) ?: return@forEach
                        val rating = (o.opt("Rating") ?: o.opt("rating")) as? Number
                        val votes = (o.opt("Votes") ?: o.opt("votes")) as? Number
                        if (rating != null && votes != null) {
                            map[k.lowercase(Locale.US)] = Vote(rating.toFloat(), votes.toInt())
                            sum += rating.toDouble()
                        }
                    }
                    if (map.isNotEmpty()) meanRating = (sum / map.size).toFloat()
                    _votes.value = map
                    Timber.i("NoBars: loaded IMDb votes for %d items", map.size)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: could not load IMDb votes")
            }
        }
    }

    /** IMDb's weighted rating: few votes pull a rating towards the library average. */
    fun weighted(
        rating: Float?,
        votes: Int?,
    ): Float {
        val r = rating ?: return 0f
        val v = (votes ?: 0).toFloat()
        return (v / (v + MIN_VOTES)) * r + (MIN_VOTES / (v + MIN_VOTES)) * meanRating
    }

    /** 1,234,567 -> "1.2M", 45,300 -> "45K", 3,400 -> "3.4K", 812 -> "812" */
    fun short(votes: Int): String =
        when {
            votes >= 1_000_000 -> String.format(Locale.US, "%.1fM", votes / 1_000_000f)
            votes >= 10_000 -> "${votes / 1000}K"
            votes >= 1_000 -> String.format(Locale.US, "%.1fK", votes / 1000f)
            else -> votes.toString()
        }

    /** 1234567 -> "1,234,567" */
    fun full(votes: Int): String = String.format(Locale.US, "%,d", votes)
}

/** NoBars: the loaded votes, so posters and headers update once they arrive. */
val LocalImdbVotes = compositionLocalOf<Map<String, ImdbVotesStore.Vote>> { emptyMap() }

fun Map<String, ImdbVotesStore.Vote>.forItem(id: UUID?): ImdbVotesStore.Vote? = id?.let { this[ImdbVotesStore.key(it)] }

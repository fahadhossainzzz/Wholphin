package com.github.damontecres.wholphin.services

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** NoBars: how to reach this Jellyfin server from outside the SDK (set once the user is signed in). */
object ServerAccess {
    @Volatile var baseUrl: String? = null
    @Volatile var token: String? = null
    @Volatile var userId: UUID? = null

    fun get(path: String): String? {
        val base = baseUrl ?: return null
        val t = token ?: return null
        return httpGet(base.trimEnd('/') + path, mapOf("Authorization" to "MediaBrowser Token=\"$t\""))
    }
}

internal fun httpGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String? {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 12_000
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    conn.setRequestProperty("Accept", "application/json")
    return try {
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } finally {
        conn.disconnect()
    }
}

/**
 * NoBars: which streaming services carry a title ("where to watch" data from TMDB / JustWatch).
 * TMDB has no Bangladesh data, so India's catalogue is used. The TMDB key lives in Jellyfin's
 * per-user settings, never in the app's public code.
 */
object WatchProviders {
    const val REGION = "IN"
    const val REGION_NAME = "India"

    data class Provider(
        val id: Int,
        val name: String,
        val logoUrl: String?,
    )

    private val cache = ConcurrentHashMap<String, List<Provider>>()

    @Volatile private var key: String? = null

    @Volatile private var keyCheckedAt = 0L

    private fun tmdbKey(): String? {
        key?.let { return it }
        if (System.currentTimeMillis() - keyCheckedAt < 10 * 60_000) return null
        keyCheckedAt = System.currentTimeMillis()
        val user = ServerAccess.userId ?: return null
        val json = ServerAccess.get("/DisplayPreferences/nobars?userId=$user&client=nobars") ?: return null
        key =
            JSONObject(json)
                .optJSONObject("CustomPrefs")
                ?.optString("tmdbKey")
                ?.takeIf { it.isNotBlank() }
        return key
    }

    suspend fun forTmdb(
        tmdbId: String,
        tv: Boolean,
    ): List<Provider> {
        val cacheKey = (if (tv) "tv" else "movie") + tmdbId
        cache[cacheKey]?.let { return it }
        return withContext(WholphinDispatchers.IO) {
            try {
                val k = tmdbKey() ?: return@withContext emptyList()
                val json =
                    httpGet("https://api.themoviedb.org/3/${if (tv) "tv" else "movie"}/$tmdbId/watch/providers?api_key=$k")
                        ?: return@withContext emptyList()
                val region = JSONObject(json).optJSONObject("results")?.optJSONObject(REGION)
                val providers = LinkedHashMap<Int, Provider>()
                // Subscription first, then free / with ads; renting and buying is left out
                for (kind in listOf("flatrate", "free", "ads")) {
                    val arr = region?.optJSONArray(kind) ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optInt("provider_id")
                        val name = o.optString("provider_name")
                        // "Netflix Standard with Ads" and the like duplicate the main service
                        if (name.contains("with Ads", ignoreCase = true) && providers.values.any { name.startsWith(it.name) }) continue
                        providers.getOrPut(id) {
                            Provider(id, name, o.optString("logo_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w92$it" })
                        }
                    }
                }
                providers.values.take(6).also { cache[cacheKey] = it }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: where-to-watch lookup failed")
                emptyList()
            }
        }
    }

    /** Android TV app packages for the services we can hand over to. */
    private fun packagesFor(name: String): List<String> {
        val n = name.lowercase()
        return when {
            n.startsWith("netflix") -> listOf("com.netflix.ninja", "com.netflix.mediaclient")
            n.contains("prime video") || n.contains("amazon") -> listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
            n.contains("hotstar") -> listOf("in.startv.hotstar.dplus.tv", "in.startv.hotstar")
            n.contains("apple tv") -> listOf("com.apple.atve.androidtv.appletv")
            n.contains("youtube") -> listOf("com.google.android.youtube.tv")
            n.contains("zee5") -> listOf("com.graymatrix.did")
            n.contains("sony liv") || n.contains("sonyliv") -> listOf("com.sonyliv")
            n.contains("mubi") -> listOf("com.mubi")
            n.contains("hoichoi") -> listOf("com.viewlift.hoichoi")
            n.contains("crunchyroll") -> listOf("com.crunchyroll.crunchyroid")
            else -> emptyList()
        }
    }

    private fun searchUri(
        name: String,
        title: String,
    ): Uri? {
        val q = URLEncoder.encode(title, "UTF-8")
        val n = name.lowercase()
        return when {
            n.startsWith("netflix") -> Uri.parse("https://www.netflix.com/search?q=$q")
            n.contains("prime video") || n.contains("amazon") -> Uri.parse("https://app.primevideo.com/search?phrase=$q")
            n.contains("youtube") -> Uri.parse("https://www.youtube.com/results?search_query=$q")
            else -> null
        }
    }

    /** Opens the service's app, searching for the title where the app supports it. */
    fun open(
        context: Context,
        provider: Provider,
        title: String,
    ) {
        val packages = packagesFor(provider.name)
        for (pkg in packages) {
            searchUri(provider.name, title)?.let { uri ->
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                } catch (_: ActivityNotFoundException) {
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: search hand-off to %s failed", pkg)
                }
            }
            val launch =
                context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                try {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: opening %s failed", pkg)
                }
            }
        }
        Toast.makeText(context, "${provider.name} isn't installed on this TV", Toast.LENGTH_LONG).show()
    }
}

package com.github.damontecres.wholphin.services

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

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

/** NoBars: hands a title over to the Netflix or Prime Video app on the TV. */
object WatchProviders {
    data class Provider(
        val id: Int,
        val name: String,
        val logoUrl: String?,
    )

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

    /** True when there's a TV app we know how to hand over to for this service. */
    fun canOpen(name: String): Boolean = packagesFor(name).isNotEmpty()

    /** Opens the service's app, searching for the title where the app supports it. */
    fun open(
        context: Context,
        provider: Provider,
        title: String,
        netflixId: String? = null,
    ) {
        val packages = packagesFor(provider.name)
        val isNetflix = provider.name.lowercase().startsWith("netflix")
        if (isNetflix) {
            // Netflix's TV app takes links to a title (by Netflix's own number) but no searches
            if (netflixId != null) {
                for (pkg in packages) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/$netflixId"))
                                .setPackage(pkg)
                                .putExtra("source", "30")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        return
                    } catch (_: ActivityNotFoundException) {
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: Netflix title hand-off to %s failed", pkg)
                    }
                }
            }
            for (pkg in packages) {
                val launch =
                    context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                        ?: context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    try {
                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Toast.makeText(context, "In Netflix, search for: $title", Toast.LENGTH_LONG).show()
                        return
                    } catch (e: Exception) {
                        Timber.w(e, "NoBars: opening %s failed", pkg)
                    }
                }
            }
            Toast.makeText(context, "Netflix isn't installed on this TV", Toast.LENGTH_LONG).show()
            return
        }
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

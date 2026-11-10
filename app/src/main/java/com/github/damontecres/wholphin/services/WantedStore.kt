package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * NoBars: My list (titles saved from the Netflix, Prime Video and Theatre pages, kept until you remove them). Kept on the Jellyfin server (the user's display
 * preferences), so it survives reinstalls and every TV sees the same list. Once a film arrives in
 * the library its card shows ✓ and opens your copy.
 */
object WantedStore {
    private const val PREFS_ID = "nobars-wanted"
    private const val CLIENT = "nobars"

    private val _items = MutableStateFlow<List<StreamingStore.Entry>>(emptyList())
    val items: StateFlow<List<StreamingStore.Entry>> = _items

    @Volatile private var loaded = false

    private val _watched = MutableStateFlow<Set<java.util.UUID>>(emptySet())

    /** Library copies of My list titles that you've watched to the end (they stay on the list until you remove them) */
    val watched: StateFlow<Set<java.util.UUID>> = _watched

    private fun path(): String? {
        val user = ServerAccess.userId ?: return null
        return "/DisplayPreferences/$PREFS_ID?userId=$user&client=$CLIENT"
    }

    suspend fun load() =
        withContext(WholphinDispatchers.IO) {
            try {
                val json = JSONObject(ServerAccess.get(path() ?: return@withContext) ?: return@withContext)
                val list = json.optJSONObject("CustomPrefs")?.optString("wanted").orEmpty()
                _items.value =
                    if (list.isBlank()) {
                        emptyList()
                    } else {
                        JSONArray(list).let { a -> (0 until a.length()).map { StreamingStore.fromJson(a.getJSONObject(it)) } }
                    }
                loaded = true
            } catch (e: Exception) {
                Timber.w(e, "NoBars: wanted list unavailable")
            }
            loadWatched()
        }

    /** Asks Jellyfin which of the list's library copies you've finished (one small request). */
    private fun loadWatched() {
        try {
            val user = ServerAccess.userId ?: return
            val ids = _items.value.mapNotNull { StreamingStore.libraryId(it) }.distinct()
            if (ids.isEmpty()) {
                _watched.value = emptySet()
                return
            }
            val json =
                JSONObject(
                    ServerAccess.get(
                        "/Items?userId=$user&ids=${ids.joinToString(",") { it.toString().replace("-", "") }}&enableUserData=true&fields=",
                    ) ?: return,
                )
            val items = json.optJSONArray("Items") ?: return
            _watched.value =
                (0 until items.length())
                    .mapNotNull { i ->
                        val o = items.getJSONObject(i)
                        if (o.optJSONObject("UserData")?.optBoolean("Played") == true) {
                            ids.firstOrNull { it.toString().replace("-", "") == o.optString("Id").replace("-", "") }
                        } else {
                            null
                        }
                    }.toSet()
        } catch (e: Exception) {
            Timber.w(e, "NoBars: watched marks unavailable")
        }
    }

    fun contains(e: StreamingStore.Entry): Boolean = _items.value.any { it.tmdb == e.tmdb && it.series == e.series }

    /** Adds the title (newest first) or removes it. Returns true when it's now on the list. */
    suspend fun toggle(
        e: StreamingStore.Entry,
        from: String? = null,
    ): Boolean {
        if (!loaded) load()
        val now = _items.value
        val adding = !contains(e)
        _items.value =
            if (adding) {
                val saved =
                    e.copy(
                        rank = null,
                        move = null,
                        note = null,
                        status = null,
                        added = e.added ?: java.time.LocalDate.now().toString(),
                        from = e.from ?: from,
                    )
                listOf(saved) + now
            } else {
                now.filterNot { it.tmdb == e.tmdb && it.series == e.series }
            }
        save()
        return adding
    }

    private suspend fun save() =
        withContext(WholphinDispatchers.IO) {
            try {
                val p = path() ?: return@withContext
                val current = JSONObject(ServerAccess.get(p) ?: "{}")
                val prefs = current.optJSONObject("CustomPrefs") ?: JSONObject()
                prefs.put("wanted", JSONArray(_items.value.map { StreamingStore.toJson(it) }).toString())
                current.put("CustomPrefs", prefs)
                if (!current.has("Id")) current.put("Id", PREFS_ID)
                if (!current.has("Client")) current.put("Client", CLIENT)
                val base = ServerAccess.baseUrl ?: return@withContext
                val conn = URL(base.trimEnd('/') + p).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 12_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "MediaBrowser Token=\"${ServerAccess.token}\"")
                    conn.outputStream.use { it.write(current.toString().toByteArray()) }
                    if (conn.responseCode !in 200..299) Timber.w("NoBars: saving wanted list failed (%d)", conn.responseCode)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: saving wanted list failed")
            }
        }
}

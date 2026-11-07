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
 * NoBars: the "Want it" list from the Cinema page. Kept on the Jellyfin server (the user's display
 * preferences), so it survives reinstalls and every TV sees the same list. Once a film arrives in
 * the library its card shows ✓ and opens your copy.
 */
object WantedStore {
    private const val PREFS_ID = "nobars-wanted"
    private const val CLIENT = "nobars"

    private val _items = MutableStateFlow<List<StreamingStore.Entry>>(emptyList())
    val items: StateFlow<List<StreamingStore.Entry>> = _items

    @Volatile private var loaded = false

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
        }

    fun contains(e: StreamingStore.Entry): Boolean = _items.value.any { it.tmdb == e.tmdb && it.series == e.series }

    suspend fun toggle(e: StreamingStore.Entry) {
        if (!loaded) load()
        val now = _items.value
        _items.value = if (contains(e)) now.filterNot { it.tmdb == e.tmdb && it.series == e.series } else listOf(e) + now
        save()
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

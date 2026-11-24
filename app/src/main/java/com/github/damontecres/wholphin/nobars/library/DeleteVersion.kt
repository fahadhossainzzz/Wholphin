package com.github.damontecres.wholphin.nobars.library

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.ui.showToast
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.json.JSONObject
import timber.log.Timber

/**
 * NoBars: deletes one version (one file) of a title with two or more, through the server's ratings plugin
 * (Plugins/ImdbRatings/DeleteVersion): only that file; the other versions and every subtitle file stay. Shows the
 * server's answer. True when deleted.
 */
suspend fun deleteVersionOnServer(
    context: Context,
    item: BaseItem,
    source: MediaSourceInfo,
): Boolean {
    val sourceId = source.id ?: return false
    val reply =
        try {
            ServerAccess.post("/Plugins/ImdbRatings/DeleteVersion/${item.id}/$sourceId")?.let { JSONObject(it) }
        } catch (e: Exception) {
            Timber.w(e, "NoBars: delete version failed")
            null
        }
    if (reply == null) {
        showToast(context, "Couldn't delete the version (the server refused or couldn't be reached)")
        return false
    }
    val ok = reply.optBoolean("ok")
    if (ok) com.github.damontecres.wholphin.nobars.data.SizeStore.invalidate()
    showToast(context, reply.optString("message").ifBlank { if (ok) "Version deleted" else "Not deleted" })
    return ok
}

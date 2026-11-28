package com.github.damontecres.wholphin.nobars.data

import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * NoBars: the TV app's side of the shared data layer (module core-data): it turns catalogue titles into the TV's
 * poster cards, and adds up box office for the TV's people panel. The data layer itself knows nothing of the TV's
 * screens, so the phone app can make its own cards from the same titles.
 */

/** A poster card for a catalogue title (see [StreamingStore.cardDto]). */
fun StreamingStore.toBaseItem(
    e: StreamingStore.Entry,
    row: String = "",
): BaseItem = BaseItem(cardDto(e, row), imageUrlOverride = e.poster)

/** Person panel: "💰 Films in your library earned $12.4B (9 films) · biggest: Skyfall $1.1B" */
fun BoxOffice.totals(items: List<BaseItem>): String? {
    val earned =
        items
            .filter { it.type == BaseItemKind.MOVIE }
            .mapNotNull { item -> forItem(item.id)?.let { m -> m.gross?.let { Triple(item, m, it) } } }
    if (earned.isEmpty()) return null
    val total = earned.sumOf { it.third }
    val best = earned.maxBy { it.third }
    val films = if (earned.size == 1) "1 film" else "${earned.size} films"
    val biggest = if (earned.size > 1) " · biggest: ${best.first.name} ${posterLabel(best.second)}" else ""
    return "💰 Films in your library earned ${short(total)} ($films)$biggest"
}

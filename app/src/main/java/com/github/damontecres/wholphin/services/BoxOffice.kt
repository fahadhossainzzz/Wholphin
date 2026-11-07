package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * NoBars: worldwide box office for films. The Mac's daily refresh puts it in the streaming
 * library list (TMDB first, Wikidata where TMDB has nothing), plus "today's money" using US
 * consumer prices. Catalogue (Netflix / Prime) cards carry their own figures.
 */
object BoxOffice {
    data class Money(
        /** Worldwide gross, US dollars */
        val gross: Long?,
        val budget: Long?,
        val year: Int?,
        /** Worldwide gross in today's dollars */
        val today: Long?,
    )

    private val _library = MutableStateFlow<Map<String, Money>>(emptyMap())

    /** Jellyfin item id (no dashes) -> money */
    val library: StateFlow<Map<String, Money>> = _library

    fun load(json: JSONObject?) {
        if (json == null) return
        val map = HashMap<String, Money>()
        json.keys().forEach { k ->
            val o = json.optJSONObject(k) ?: return@forEach
            map[k] =
                Money(
                    gross = o.optLong("g").takeIf { it > 0 },
                    budget = o.optLong("b").takeIf { it > 0 },
                    year = o.optInt("y").takeIf { it > 0 },
                    today = o.optLong("a").takeIf { it > 0 },
                )
        }
        _library.value = map
    }

    fun forItem(id: UUID?): Money? {
        id ?: return null
        _library.value[id.toString().replace("-", "")]?.let { return it }
        val e = StreamingStore.entries[id] ?: return null
        if (e.gross == null && e.budget == null) return null
        return Money(e.gross, e.budget, e.year, e.grossToday)
    }

    /** "$2.1B", "$245M", "$5.2M", "$57K" */
    fun short(v: Long): String =
        when {
            v >= 1_000_000_000L -> "$" + trim(v / 1e9, if (v >= 10_000_000_000L) 0 else 1) + "B"
            v >= 1_000_000L -> "$" + trim(v / 1e6, if (v >= 10_000_000L) 0 else 1) + "M"
            v >= 1_000L -> "$" + (v / 1000) + "K"
            else -> "$$v"
        }

    /** "$2.07B", "$245.6M" */
    private fun full(v: Long): String =
        when {
            v >= 1_000_000_000L -> "$" + trim(v / 1e9, 2) + "B"
            v >= 1_000_000L -> "$" + trim(v / 1e6, 1) + "M"
            else -> short(v)
        }

    private fun trim(
        x: Double,
        decimals: Int,
    ): String = String.format(Locale.US, "%.${decimals}f", x).let { if (decimals > 0) it.trimEnd('0').trimEnd('.') else it }

    /** True when today's money is worth showing (older films, at least 15% more). */
    private fun showToday(m: Money): Boolean = m.today != null && m.gross != null && m.today > m.gross * 1.15

    /** Detail page: "$2.07B worldwide · budget $245M · 8.4× its budget · ≈ $2.8B in today's money" */
    fun line(m: Money): String? {
        val parts = mutableListOf<String>()
        m.gross?.let { parts += "${full(it)} worldwide" }
        m.budget?.let { parts += "budget ${full(it)}" }
        if (m.gross != null && m.budget != null && m.budget >= 100_000) {
            val x = m.gross.toDouble() / m.budget
            parts += if (x >= 1) "${trim(x, 1)}× its budget" else "earned ${(x * 100).toInt()}% of its budget"
        }
        if (showToday(m)) parts += "≈ ${full(m.today!!)} in today's money"
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")?.let { "💰 $it" }
    }

    /** Short form for the player and panels: "💰 $2.1B" (or "💰 budget $40M" when no gross is known). */
    fun compact(m: Money): String? =
        when {
            m.gross != null -> "💰 ${short(m.gross)}" + if (showToday(m)) " (≈ ${short(m.today!!)} today)" else ""
            m.budget != null -> "💰 budget ${short(m.budget)}"
            else -> null
        }
}

package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * NoBars: box office for films. The Mac's daily refresh puts it in the streaming library list:
 * TMDB first, then the Wikipedia infobox (Indian films in ₹ crore as published), then Wikidata;
 * plus rank in its year, US share (OMDb), today's-money value (US consumer prices) and whether
 * the film skipped cinemas. Catalogue (Netflix / Prime) cards carry their own TMDB figures.
 */
object BoxOffice {
    data class Money(
        /** Worldwide gross, US dollars (converted from rupees for Indian films) */
        val gross: Long?,
        val budget: Long?,
        val year: Int?,
        /** Worldwide gross in today's dollars */
        val today: Long? = null,
        /** As published, e.g. "₹141–150 crore" */
        val grossLocal: String? = null,
        val budgetLocal: String? = null,
        /** Poster label for local figures, e.g. "₹146Cr" */
        val grossShort: String? = null,
        /** TMDB, Wikipedia or Wikidata */
        val source: String? = null,
        /** Position among that year's biggest earners worldwide (TMDB) */
        val rank: Int? = null,
        /** US (domestic) gross */
        val us: Long? = null,
        val streamingOnly: Boolean = false,
        /** A streaming service's film with only a limited cinema run: no box office is reported */
        val streamingFilm: Boolean = false,
        val released: LocalDate? = null,
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
                    grossLocal = o.str("gl"),
                    budgetLocal = o.str("bl"),
                    grossShort = o.str("gs"),
                    source = o.str("src"),
                    rank = o.optInt("r").takeIf { it > 0 },
                    us = o.optLong("us").takeIf { it > 0 },
                    streamingOnly = o.optInt("st") == 1,
                    streamingFilm = o.optInt("st") == 2,
                    released = o.str("rd")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                )
        }
        _library.value = map
    }

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun forItem(id: UUID?): Money? {
        id ?: return null
        _library.value[id.toString().replace("-", "")]?.let { return it }
        val e = StreamingStore.entries[id] ?: return null
        if (e.gross == null && e.budget == null) return null
        return Money(e.gross, e.budget, e.year, e.grossToday, source = "TMDB")
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

    /** Poster label: "$2.1B", or "₹146Cr" for Indian figures. */
    /** NoBars: the browsing header's short form: "$714M worldwide · Hit 3.8× · #6 of 2024" */
    fun headerLine(m: Money): String? {
        if (m.gross == null) return if (m.streamingOnly) "Straight to streaming" else null
        val parts = mutableListOf(if (m.grossLocal != null) "${m.grossLocal} worldwide" else "${short(m.gross)} worldwide")
        verdict(m)?.let { v -> parts += "$v ${trim(multiple(m)!!, 1)}×" }
        if (m.rank != null && m.year != null) parts += "#${m.rank} of ${m.year}"
        return parts.joinToString(" · ")
    }

    fun posterLabel(m: Money): String? = m.grossShort ?: m.gross?.let { short(it) }

    /** Gross ÷ budget, when both are known and the budget is a real figure. */
    fun multiple(m: Money): Double? =
        if (m.gross != null && m.budget != null && m.budget >= 100_000) m.gross.toDouble() / m.budget else null

    /**
     * Our own label from gross ÷ budget: a film needs roughly 2.5× its budget to profit once marketing and the
     * cinemas' share are paid. Not shown in the first 90 days, while the film is still earning.
     */
    fun verdict(m: Money): String? {
        val x = multiple(m) ?: return null
        val released = m.released
        if (released != null && ChronoUnit.DAYS.between(released, LocalDate.now()) < 90) return null
        return when {
            x >= 4.0 -> "Blockbuster"
            x >= 2.5 -> "Hit"
            x >= 1.5 -> "Break-even"
            else -> "Flop"
        }
    }

    /** True when today's money is worth showing (older dollar figures, at least 15% more). */
    private fun showToday(m: Money): Boolean = m.grossLocal == null && m.today != null && m.gross != null && m.today > m.gross * 1.15

    /**
     * Detail page, first line: "💰 $2.07B worldwide · budget $245M · Blockbuster (8.4×) · #1 of 2015", or
     * "💰 ₹141–150 crore worldwide (≈ $17M) · budget ₹60 crore · Hit (2.8×)", or "📺 Straight to streaming · on Netflix".
     */
    fun line(
        m: Money,
        services: List<String> = emptyList(),
    ): String? {
        if (m.gross == null && m.budget == null) {
            val on = services.joinToString(" & ") { StreamingStore.displayName(it) }
            return when {
                m.streamingOnly -> "📺 Straight to streaming" + if (on.isNotEmpty()) " · on $on" else " · no cinema release"
                m.streamingFilm -> "📺 Streaming film · limited cinema run, no box office reported" + if (on.isNotEmpty()) " · on $on" else ""
                else -> null
            }
        }
        val parts = mutableListOf<String>()
        m.gross?.let { g -> parts += if (m.grossLocal != null) "${m.grossLocal} worldwide (≈ ${short(g)})" else "${full(g)} worldwide" }
        (m.budgetLocal ?: m.budget?.let { full(it) })?.let { parts += "budget $it" }
        verdict(m)?.let { v -> parts += "$v (${trim(multiple(m)!!, 1)}×)" }
        if (m.rank != null && m.year != null) parts += "#${m.rank} of ${m.year}"
        return "💰 " + parts.joinToString("  ·  ")
    }

    /** Detail page, second line: "$936M US · $1.13B rest of world · ≈ $2.8B in today's money · source: TMDB" */
    fun detailLine(m: Money): String? {
        if (m.gross == null) return null
        val parts = mutableListOf<String>()
        if (m.us != null && m.us <= m.gross) {
            parts += "${full(m.us)} US"
            parts += "${full(m.gross - m.us)} rest of world"
        }
        if (showToday(m)) parts += "≈ ${full(m.today!!)} in today's money"
        m.source?.let { parts += "source: $it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    /** Player and panels: "💰 $2.1B · Blockbuster", "💰 ₹146Cr · Hit", "📺 Straight to streaming". */
    fun compact(m: Money): String? {
        val amount = posterLabel(m) ?: m.budget?.let { "budget " + short(it) }
        if (amount == null) {
            return when {
                m.streamingOnly -> "📺 Straight to streaming"
                m.streamingFilm -> "📺 Streaming film"
                else -> null
            }
        }
        return "💰 $amount" + (verdict(m)?.let { " · $it" } ?: "")
    }

    /** Person panel: "💰 Films in your library earned $12.4B (9 films) · biggest: Skyfall $1.1B" */
    fun totals(items: List<BaseItem>): String? {
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

    /** Sort value for the money orders of the Movies list (null = no figure, always listed last). */
    fun sortValue(
        sort: MoneySort,
        m: Money?,
    ): Double? {
        m ?: return null
        return when (sort) {
            MoneySort.BOX_OFFICE -> m.gross?.toDouble()
            MoneySort.TODAYS_MONEY -> (m.today ?: m.gross)?.toDouble()
            MoneySort.BUDGET -> m.budget?.toDouble()
            MoneySort.RETURN -> multiple(m)
            // #1 of its year is the "highest"
            MoneySort.YEAR_RANK -> m.rank?.let { -it.toDouble() }
        }
    }
}

/** NoBars: the money orders in the Movies sort menu, sorted on the TV from [BoxOffice]. */
enum class MoneySort { BOX_OFFICE, TODAYS_MONEY, BUDGET, RETURN, YEAR_RANK }

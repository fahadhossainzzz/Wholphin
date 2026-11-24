package com.github.damontecres.wholphin.nobars.calendar

import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * NoBars Calendar: one thing on one day. [kind] says whose it is: yours (a new episode of a show you watch, a
 * Watchlist film), watched (your Trakt history) or the world (cinema, digital, streaming premieres).
 */
data class CalItem(
    val key: String?,
    val title: String,
    val kind: Kind,
    val tag: String,
    val what: String,
    val where: String,
    val time: String,
    val image: String?,
    val rating: Float?,
    val votes: Int?,
    val season: Int?,
    val episode: Int?,
) {
    enum class Kind { YOURS, WATCHED, WORLD }

    val tmdb: Int? get() = key?.substringAfter(":")?.toIntOrNull()
    val series: Boolean get() = key?.startsWith("tv:") == true

    /** "S1 E9 · Prime Video · 8:30 AM" */
    val line: String get() = listOf(what, where, time).filter { it.isNotBlank() }.joinToString("  ·  ")
}

/** One month of the Calendar: the items of each day, yours first, then watched, then the world. */
data class CalMonth(
    val month: YearMonth,
    val days: Map<LocalDate, List<CalItem>>,
    val built: String,
)

/** The plugin's answer for /Plugins/ImdbRatings/Calendar/{yyyy-MM}. */
fun parseCalMonth(json: String): CalMonth? =
    try {
        val o = JSONObject(json)
        val month = YearMonth.parse(o.getString("month"))
        val days = o.optJSONObject("days") ?: JSONObject()
        val out = sortedMapOf<LocalDate, List<CalItem>>()
        days.keys().forEach { day ->
            val a = days.optJSONArray(day) ?: return@forEach
            out[LocalDate.parse(day)] =
                (0 until a.length()).mapNotNull { i ->
                    val it = a.optJSONObject(i) ?: return@mapNotNull null
                    CalItem(
                        key = it.optString("k").takeIf { k -> k.contains(":") },
                        title = it.optString("t"),
                        kind =
                            when (it.optString("kind")) {
                                "yours" -> CalItem.Kind.YOURS
                                "watched" -> CalItem.Kind.WATCHED
                                else -> CalItem.Kind.WORLD
                            },
                        tag = it.optString("tag"),
                        what = it.optString("what"),
                        where = it.optString("where"),
                        time = it.optString("time"),
                        image = it.optString("img").takeIf { s -> s.startsWith("http") },
                        rating = it.optDouble("r").takeIf { r -> !r.isNaN() && r > 0 }?.toFloat(),
                        votes = it.optInt("v").takeIf { v -> v > 0 },
                        season = it.optInt("s").takeIf { s -> s > 0 },
                        episode = it.optInt("e").takeIf { e -> e > 0 },
                    )
                }
        }
        CalMonth(month, out, o.optString("built"))
    } catch (_: Exception) {
        null
    }

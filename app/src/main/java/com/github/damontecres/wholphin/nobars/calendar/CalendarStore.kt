package com.github.damontecres.wholphin.nobars.calendar

import com.github.damontecres.wholphin.nobars.data.ServerAccess
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * NoBars Calendar: months from the plugin (one request each), kept while the app runs. A month the server hasn't
 * built yet takes a little while the first time (it gathers Trakt, TMDB and TVmaze), so the request is repeated
 * until it's there.
 */
object CalendarStore {
    private val months = ConcurrentHashMap<YearMonth, CalMonth>()
    private val loadedAt = ConcurrentHashMap<YearMonth, Long>()
    private val scope = CoroutineScope(SupervisorJob())

    /** How many things of yours are out today (for the side menu). */
    private val _today = MutableStateFlow(0)
    val today: StateFlow<Int> = _today

    fun cached(month: YearMonth): CalMonth? = months[month]

    /** The month (fresh if older than 10 minutes here); null if the server couldn't give it within ~3 minutes. */
    suspend fun load(month: YearMonth): CalMonth? =
        withContext(WholphinDispatchers.IO) {
            val have = months[month]
            if (have != null && System.currentTimeMillis() - (loadedAt[month] ?: 0) < 10 * 60_000) return@withContext have
            repeat(24) { attempt ->
                val got = ServerAccess.get("/Plugins/ImdbRatings/Calendar/$month")?.let(::parseCalMonth)
                if (got != null) {
                    months[month] = got
                    loadedAt[month] = System.currentTimeMillis()
                    if (month == YearMonth.now()) {
                        _today.value = got.days[LocalDate.now()].orEmpty().count { it.kind == CalItem.Kind.YOURS }
                    }
                    return@withContext got
                }
                if (have != null) return@withContext have
                delay(if (attempt < 3) 3_000 else 8_000)
            }
            null
        }

    /** Loads this month in the background (for the side menu's count). */
    fun warmUp() {
        scope.launch { load(YearMonth.now()) }
    }
}

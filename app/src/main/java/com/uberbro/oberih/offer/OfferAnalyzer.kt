package com.uberbro.oberih.offer

import android.content.Context
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.data.Geo
import com.uberbro.oberih.data.GeoPoint
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.data.RiskGrid
import com.uberbro.oberih.data.Sensitivity
import com.uberbro.oberih.util.SunTimes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class Profit(val word: String) { GOOD("Вигідно"), OK("Так собі"), BAD("Невигідно"), UNKNOWN("") }

data class Verdict(
    val level: Level,
    val pickupLevel: Level,
    val dropoffLevel: Level,
    /** Найгірша зона, через яку проходить маршрут (тільки проїзд, без зупинки). */
    val routeWorst: Level,
    val pickupArea: String?,
    val dropoffArea: String?,
    val isNight: Boolean,
    val fare: Double?,
    val netPerHour: Double?,
    val perMile: Double?,
    val totalMinutes: Double?,
    val totalMiles: Double?,
    val profit: Profit,
    /** Коротке пояснення для плашки на екрані. */
    val reason: String,
)

object OfferAnalyzer {

    suspend fun analyze(ctx: Context, offer: ParsedOffer): Verdict = coroutineScope {
        val prefs = Prefs(ctx)
        val grid = CrimeRepository.load(ctx)
        val night = SunTimes.isNight()
        val pickupJob = async { offer.pickupText?.let { Geo.geocode(ctx, it, grid) } }
        val dropJob = async { offer.dropoffText?.let { Geo.geocode(ctx, it, grid) } }
        val p = pickupJob.await()
        val d = dropJob.await()
        val route = if (p != null && d != null) Geo.route(p, d) else null
        evaluate(offer, grid, p, d, route?.points.orEmpty(), route?.minutes, route?.miles, night, prefs.sensitivity,
            ProfitInputs(prefs.mpg.toDouble(), prefs.gasPrice.toDouble(), prefs.wearPerMile.toDouble(), prefs.targetPerHour.toDouble()))
    }

    data class ProfitInputs(val mpg: Double, val gasPrice: Double, val wearPerMile: Double, val targetPerHour: Double)

    /** Уся логіка рішення окремо від мережі — щоб її можна було перевірити тестами. */
    fun evaluate(
        offer: ParsedOffer,
        grid: RiskGrid?,
        pickup: GeoPoint?,
        dropoff: GeoPoint?,
        routePoints: List<GeoPoint>,
        routeMinutes: Double?,
        routeMiles: Double?,
        night: Boolean,
        sens: Sensitivity,
        pi: ProfitInputs,
    ): Verdict {
        fun lvl(g: GeoPoint?) = if (g == null || grid == null) Level.UNKNOWN else grid.levelAt(g.lat, g.lon, night, sens)
        val pl = lvl(pickup)
        val dl = lvl(dropoff)

        // Проїзд через небезпечний район (без зупинки) — менший ризик, ніж подача чи висадка там.
        // Рахуємо, тільки якщо маршрут іде через червоне хоча б ~300 м.
        var routeWorst = Level.UNKNOWN
        if (grid != null && routePoints.isNotEmpty()) {
            val levels = routePoints.map { grid.levelAt(it.lat, it.lon, night, sens) }
            val redRun = levels.count { it == Level.RED }
            routeWorst = Level.worst(levels)
            if (routeWorst == Level.RED && redRun < 2) routeWorst = Level.YELLOW
        }
        // Проїзд через червону зону = «не рекомендуємо» (маршрут у Waze можна змінити); через жовту — не страшно.
        val transit = when (routeWorst) {
            Level.RED -> Level.YELLOW
            Level.UNKNOWN -> Level.UNKNOWN
            else -> Level.GREEN
        }
        // Нічне правило «жовта → помаранчева» вже застосоване в levelAt для подачі й висадки.
        val level = Level.worst(listOf(pl, dl, transit))

        // ---- Вигідність ----
        val tripMin = offer.tripMin ?: routeMinutes
        val tripMi = offer.tripMi ?: routeMiles
        val totalMin = tripMin?.let { it + (offer.pickupMin ?: 0.0) }
        val totalMi = tripMi?.let { it + (offer.pickupMi ?: 0.0) }
        val fare = offer.fare
        var netPerHour: Double? = null
        var perMile: Double? = null
        var profit = Profit.UNKNOWN
        if (fare != null && totalMin != null && totalMin > 0.5) {
            val cost = (totalMi ?: 0.0) * (pi.gasPrice / pi.mpg.coerceAtLeast(5.0) + pi.wearPerMile)
            netPerHour = (fare - cost) / (totalMin / 60.0)
            perMile = totalMi?.takeIf { it > 0.1 }?.let { fare / it }
            profit = when {
                netPerHour >= pi.targetPerHour -> Profit.GOOD
                netPerHour >= pi.targetPerHour * 0.75 -> Profit.OK
                else -> Profit.BAD
            }
        }

        val pArea = pickup?.let { grid?.areaName(it.lat, it.lon) }
        val dArea = dropoff?.let { grid?.areaName(it.lat, it.lon) }
        val reason = buildReason(level, pl, dl, transit, pArea, dArea, offer, pickup, dropoff)

        return Verdict(level, pl, dl, routeWorst, pArea, dArea, night, fare, netPerHour, perMile, totalMin, totalMi, profit, reason)
    }

    private fun buildReason(
        level: Level, pl: Level, dl: Level, transit: Level, pArea: String?, dArea: String?,
        offer: ParsedOffer, pickup: GeoPoint?, dropoff: GeoPoint?,
    ): String {
        val parts = ArrayList<String>()
        fun bad(l: Level) = l.severity >= Level.YELLOW.severity
        if (bad(pl)) parts += "подача: ${pArea ?: "небезпечний район"}"
        if (bad(dl)) parts += "висадка: ${dArea ?: "небезпечний район"}"
        if (parts.isEmpty() && bad(transit)) parts += "маршрут через небезпечний район"
        if (level == Level.GREEN) parts += listOfNotNull(dArea?.let { "висадка: $it" })
        if (pickup == null && offer.pickupText != null) parts += "адресу подачі не знайдено"
        if (dropoff == null && offer.dropoffText != null) parts += "адресу висадки не знайдено"
        if (offer.pickupText == null && offer.dropoffText == null) parts += "адреси не прочитано"
        if (dl == Level.UNKNOWN && dropoff != null) parts += "висадка за межами Чикаго"
        return parts.joinToString(" · ")
    }
}

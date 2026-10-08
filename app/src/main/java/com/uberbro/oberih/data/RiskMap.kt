package com.uberbro.oberih.data

/** Місто поза Чикаго й Мілвокі: рівень насильницької злочинності за даними ФБР. */
data class Town(
    val name: String,
    val state: String,
    val lat: Double,
    val lon: Double,
    val population: Int,
    /** Насильницьких злочинів на 100 000 жителів за рік. */
    val rate: Int,
    val radiusKm: Double,
)

/** Усі міста Іллінойсу й південно-східного Вісконсину (файл assets/towns.tsv, збирає tools/build_towns.py). */
class TownMap(val towns: List<Town>) {

    /** Найближче місто, в межах якого лежить точка (з невеликим запасом). */
    fun find(lat: Double, lon: Double): Town? {
        var best: Town? = null
        var bestK = Double.MAX_VALUE
        for (t in towns) {
            val d = RiskGrid.distanceKm(lat, lon, t.lat, t.lon)
            val k = d / t.radiusKm
            if (k < 1.25 && k < bestK) { bestK = k; best = t }
        }
        return best
    }

    fun level(t: Town, isNight: Boolean, s: Sensitivity): Level {
        var l = when {
            t.rate >= s.townRed -> Level.RED
            t.rate >= s.townYellow -> Level.YELLOW
            else -> Level.GREEN
        }
        if (isNight && l == Level.YELLOW) l = Level.ORANGE
        return l
    }

    companion object {
        fun parse(text: String): TownMap = TownMap(
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("town\t") }
                .mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 7) return@mapNotNull null
                    runCatching {
                        Town(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toInt(), p[5].toInt(), p[6].toDouble())
                    }.getOrNull()
                }.toList(),
        )
    }
}

/**
 * Єдина карта ризику: детальні квартали Чикаго й Мілвокі + рівень інших міст Іллінойсу та Вісконсину.
 * Порядок: спершу детальні карти, потім місто з даних ФБР.
 */
class RiskMap(val chicago: RiskGrid?, val milwaukee: RiskGrid?, val towns: TownMap) {

    fun levelAt(lat: Double, lon: Double, isNight: Boolean, s: Sensitivity): Level {
        chicago?.takeIf { it.covers(lat, lon) }?.let { return it.levelAt(lat, lon, isNight, s) }
        milwaukee?.takeIf { it.covers(lat, lon) }?.let { return it.levelAt(lat, lon, isNight, s) }
        towns.find(lat, lon)?.let { return towns.level(it, isNight, s) }
        return Level.UNKNOWN
    }

    fun areaName(lat: Double, lon: Double): String? {
        chicago?.takeIf { it.covers(lat, lon) }?.areaName(lat, lon)?.let { return it }
        milwaukee?.takeIf { it.covers(lat, lon) }?.let { return "Milwaukee" }
        return towns.find(lat, lon)?.name
    }

    val isEmpty: Boolean get() = chicago == null && milwaukee == null && towns.towns.isEmpty()
}

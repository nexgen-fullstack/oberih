package com.uberbro.oberih

import com.uberbro.oberih.data.CrimeWeights
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.RiskGrid
import com.uberbro.oberih.data.RiskGridBuilder
import com.uberbro.oberih.data.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGridTest {
    private val englewood = 41.7790 to -87.6447
    private val riverNorth = 41.8899 to -87.6313

    @Test fun cellsMatchServerGrouping() {
        // Сервер групує floor(lat*250), floor(lon*200) — наша сітка має збігатися.
        val (la, lo) = englewood
        assertEquals(kotlin.math.floor(la * 250).toInt() - RiskGrid.ROW0, RiskGrid.rowOf(la))
        assertEquals(kotlin.math.floor(lo * 200).toInt() - RiskGrid.COL0, RiskGrid.colOf(lo))
        assertTrue(RiskGrid.index(la, lo) >= 0)
        assertEquals(-1, RiskGrid.index(40.0, -87.6))
    }

    @Test fun shootingsMakeRedButCrowdedDowntownStaysCalmer() {
        val b = RiskGridBuilder()
        // Фон: по кілька дрібних подій по всьому місту, щоб були «звичайні» клітинки.
        var lat = 41.66
        while (lat < 42.0) {
            var lon = -87.92
            while (lon < -87.56) { b.add(lat, lon, 0.5f, false, 0, "2026-09-01"); lon += 0.01 }
            lat += 0.008
        }
        val shot = CrimeWeights.weight("BATTERY", "AGGRAVATED - HANDGUN", false, 20)
        repeat(25) { b.add(englewood.first, englewood.second, shot, true, 68, "2026-09-20") }
        // У центрі стільки ж стрілянини, але там у 100 разів більше людей.
        repeat(25) { b.add(riverNorth.first, riverNorth.second, shot, false, 8, "2026-09-20") }
        b.addCrowd(RiskGrid.rowOf(riverNorth.first), RiskGrid.colOf(riverNorth.second), 3000)
        b.addCrowd(RiskGrid.rowOf(englewood.first), RiskGrid.colOf(englewood.second), 30)
        val g = b.build(0)

        assertEquals(Level.RED, g.levelAt(englewood.first, englewood.second, false, Sensitivity.NORMAL))
        val rn = g.all[RiskGrid.index(riverNorth.first, riverNorth.second)]
        val en = g.all[RiskGrid.index(englewood.first, englewood.second)]
        assertTrue("центр $rn vs Englewood $en", rn < en / 10)
        assertEquals(Level.UNKNOWN, g.levelAt(41.0, -88.3, false, Sensitivity.NORMAL))
    }

    @Test fun nightTurnsYellowIntoOrange() {
        val b = RiskGridBuilder()
        var lat = 41.66
        while (lat < 42.0) {
            var lon = -87.92
            while (lon < -87.56) { b.add(lat, lon, 1f, false, 0, "2026-09-01"); lon += 0.01 }
            lat += 0.008
        }
        val g = b.build(0)
        // Шукаємо будь-яку жовту клітинку — вночі вона має стати помаранчевою.
        for (r in 0 until RiskGrid.ROWS) for (c in 0 until RiskGrid.COLS) {
            val la = (RiskGrid.ROW0 + r + 0.5) / 250.0
            val lo = (RiskGrid.COL0 + c + 0.5) / 200.0
            if (g.levelAt(la, lo, false, Sensitivity.NORMAL) == Level.YELLOW) {
                assertEquals(Level.ORANGE, g.levelAt(la, lo, true, Sensitivity.NORMAL))
                return
            }
        }
    }

    @Test fun weightsFocusOnViolence() {
        assertTrue(CrimeWeights.weight("ROBBERY", "VEHICULAR HIJACKING", false, 10) > 10f)
        assertEquals(0f, CrimeWeights.weight("BATTERY", "SIMPLE", false, 10), 0f)
        assertEquals(0f, CrimeWeights.weight("BATTERY", "AGGRAVATED P.O. - HANDS, FISTS, FEET", false, 10), 0f)
        assertTrue(CrimeWeights.weight("BATTERY", "AGGRAVATED - HANDGUN", true, 10) <
            CrimeWeights.weight("BATTERY", "AGGRAVATED - HANDGUN", false, 10))
    }
}

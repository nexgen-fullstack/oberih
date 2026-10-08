package com.uberbro.oberih

import com.uberbro.oberih.data.CrimeWeights
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.Region
import com.uberbro.oberih.data.RiskGridBuilder
import com.uberbro.oberih.data.RiskMap
import com.uberbro.oberih.data.Sensitivity
import com.uberbro.oberih.data.TownMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsTest {
    private val tsv = """
        # Violent crime per 100k
        town	state	lat	lon	population	rate	radius_km	ori
        Harvey	IL	41.61	-87.65	19000	1900	2.0	IL0160900
        Naperville	IL	41.75	-88.15	149000	80	5.6	IL0220400
        Joliet	IL	41.525	-88.08	150000	600	5.6	IL0990700
        Kenosha	WI	42.58	-87.82	99000	350	4.6	WI0300200
    """.trimIndent()

    @Test fun townsParseAndLevels() {
        val t = TownMap.parse(tsv)
        assertEquals(4, t.towns.size)
        val m = RiskMap(null, null, t)
        assertEquals(Level.RED, m.levelAt(41.61, -87.65, false, Sensitivity.NORMAL))
        assertEquals(Level.GREEN, m.levelAt(41.76, -88.14, false, Sensitivity.NORMAL))
        assertEquals(Level.YELLOW, m.levelAt(41.525, -88.08, false, Sensitivity.NORMAL))
        assertEquals(Level.ORANGE, m.levelAt(41.525, -88.08, true, Sensitivity.NORMAL))
        assertEquals("Kenosha", m.areaName(42.58, -87.83))
        // Посеред поля між містами — даних немає.
        assertEquals(Level.UNKNOWN, m.levelAt(41.0, -89.5, false, Sensitivity.NORMAL))
        assertNull(m.areaName(41.0, -89.5))
    }

    @Test fun milwaukeeGridIsSeparate() {
        val downtownMke = 43.0389 to -87.9065
        assertTrue(Region.MILWAUKEE.index(downtownMke.first, downtownMke.second) >= 0)
        assertEquals(-1, Region.CHICAGO.index(downtownMke.first, downtownMke.second))
        val b = RiskGridBuilder(Region.MILWAUKEE)
        var lat = 42.95
        while (lat < 43.17) {
            var lon = -88.05
            while (lon < -87.88) { b.add(lat, lon, 0.5f, false, 0, "2026-09-01"); lon += 0.01 }
            lat += 0.008
        }
        val shot = CrimeWeights.nibrsWeight("13A", "HANDGUN", 10)
        repeat(30) { b.add(43.07, -87.95, shot, false, 0, "2026-09-20") }
        val g = b.build(0)
        val map = RiskMap(null, g, TownMap.parse(tsv))
        assertEquals(Level.RED, map.levelAt(43.07, -87.95, false, Sensitivity.NORMAL))
        assertEquals("Milwaukee", map.areaName(43.07, -87.95))
    }

    @Test fun nibrsWeights() {
        assertEquals(14f * 1.3f, CrimeWeights.nibrsWeight("120;240", "HANDGUN", 5), 0.01f) // carjacking
        assertTrue(CrimeWeights.nibrsWeight("13A", "FIREARM", 5) > CrimeWeights.nibrsWeight("13A", "PERSONAL WEAPON", 5))
        assertEquals(0f, CrimeWeights.nibrsWeight("13B", "NONE", 5), 0f)
        assertEquals(15f * 0.7f, CrimeWeights.nibrsWeight("09A", null, 150), 0.01f)
    }
}

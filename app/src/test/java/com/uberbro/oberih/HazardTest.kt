package com.uberbro.oberih

import com.uberbro.oberih.data.DistanceUnit
import com.uberbro.oberih.data.VoiceLang
import com.uberbro.oberih.hazard.HazardAlert
import com.uberbro.oberih.hazard.HazardParser
import com.uberbro.oberih.hazard.HazardTracker
import com.uberbro.oberih.hazard.HazardType
import com.uberbro.oberih.hazard.Stage
import com.uberbro.oberih.offer.ScreenText
import com.uberbro.oberih.service.Phrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTest {
    private fun t(vararg s: String) = s.map { ScreenText(it) }

    @Test fun wazePoliceCard() {
        val r = HazardParser.parse(t("Police", "1.2 mi", "Reported 6 min ago", "Thanks"))
        assertEquals(1, r.size)
        assertEquals(HazardType.POLICE, r[0].type)
        assertEquals(1931.0, r[0].meters!!, 2.0)
    }

    @Test fun feetAndCrash() {
        val r = HazardParser.parse(t("Crash reported", "800 ft"))
        assertEquals(HazardType.CRASH, r[0].type)
        assertEquals(243.8, r[0].meters!!, 1.0)
    }

    @Test fun policeAheadWithoutDistance() {
        val r = HazardParser.parse(t("Police reported ahead"))
        assertEquals(HazardType.POLICE, r.single().type)
        assertEquals(null, r.single().meters)
    }

    @Test fun ignoresReportMenuAndStillThereQuestion() {
        assertTrue(HazardParser.parse(t("Report", "Traffic", "Police", "Crash", "Hazard", "Closure")).isEmpty())
        assertTrue(HazardParser.parse(t("Police", "Still there?", "Not there")).isEmpty())
    }

    @Test fun ignoresWholeRouteDistance() {
        // «Police» без власної відстані, поруч тільки довжина всього маршруту 12 mi — це не відстань до поліції.
        val r = HazardParser.parse(t("Police", "12.4 mi"))
        assertTrue(r.isEmpty())
    }

    @Test fun trackerGivesFarThenNear() {
        val tr = HazardTracker { 500.0 }
        val a1 = tr.onSightings(HazardParser.parse(t("Police", "1.2 mi")), 0)
        assertEquals(listOf(HazardAlert(HazardType.POLICE, 1931.2128, Stage.FAR)).map { it.stage }, a1.map { it.stage })
        // Та сама поліція ближче, але ще далі за 500 м — мовчимо.
        assertTrue(tr.onSightings(HazardParser.parse(t("Police", "0.6 mi")), 5_000).isEmpty())
        // 0.2 mi ≈ 320 м — друге попередження, один раз.
        assertEquals(Stage.NEAR, tr.onSightings(HazardParser.parse(t("Police", "0.2 mi")), 10_000).single().stage)
        assertTrue(tr.onSightings(HazardParser.parse(t("Police", "0.1 mi")), 12_000).isEmpty())
        // Через 5 хвилин — нова поліція.
        assertEquals(1, tr.onSightings(HazardParser.parse(t("Police", "1.0 mi")), 12_000 + HazardTracker.FORGET_MS + 1).size)
    }

    @Test fun closeFirstSightingIsSingleNearAlert() {
        val tr = HazardTracker { 500.0 }
        assertEquals(Stage.NEAR, tr.onSightings(HazardParser.parse(t("Police", "400 ft")), 0).single().stage)
    }

    @Test fun ukrainianDistances() {
        assertEquals("за 400 метрів", Phrases.distance(400.0, VoiceLang.UK, DistanceUnit.KM))
        assertEquals("за кілометр", Phrases.distance(1_200.0, VoiceLang.UK, DistanceUnit.KM))
        assertEquals("за 2 кілометри", Phrases.distance(1_931.0, VoiceLang.UK, DistanceUnit.KM))
        assertEquals("за 5 кілометрів", Phrases.distance(4_800.0, VoiceLang.UK, DistanceUnit.KM))
        assertEquals("за 1 милю", Phrases.distance(1_609.0, VoiceLang.UK, DistanceUnit.MI))
        assertEquals("Увага! Попереду поліція за 2 кілометри.",
            Phrases.hazard(HazardAlert(HazardType.POLICE, 1_931.0, Stage.FAR), VoiceLang.UK, DistanceUnit.KM))
        assertEquals("Поліція за 300 метрів. Будь уважний.",
            Phrases.hazard(HazardAlert(HazardType.POLICE, 320.0, Stage.NEAR), VoiceLang.UK, DistanceUnit.KM))
        assertEquals("1,9 км", Phrases.shortDistance(1_931.0, DistanceUnit.KM))
    }
}

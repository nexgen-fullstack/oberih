package com.uberbro.oberih

import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.VoiceLang
import com.uberbro.oberih.offer.OfferParser
import com.uberbro.oberih.offer.Profit
import com.uberbro.oberih.offer.ScreenText
import com.uberbro.oberih.offer.Verdict
import com.uberbro.oberih.service.Phrases
import com.uberbro.oberih.util.SunTimes
import com.uberbro.oberih.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class OfferParserTest {
    private fun lines(vararg s: String) = s.mapIndexed { i, t -> ScreenText(t, 1200 + i * 60, 50) }

    @Test fun uberClassicCard() {
        val o = OfferParser.parse("Uber", lines(
            "UberX", "Exclusive", "$14.27", "4.95 ★", "Verified",
            "6 mins (1.9 mi) away", "1201 W Madison St, Chicago",
            "22 mins (8.4 mi) trip", "6300 S Halsted St, Chicago", "Accept",
        ), 2400)
        assertNotNull(o); o!!
        assertEquals(14.27, o.fare!!, 0.001)
        assertEquals(6.0, o.pickupMin!!, 0.01); assertEquals(1.9, o.pickupMi!!, 0.01)
        assertEquals(22.0, o.tripMin!!, 0.01); assertEquals(8.4, o.tripMi!!, 0.01)
        assertEquals("1201 W Madison St, Chicago", o.pickupText)
        assertEquals("6300 S Halsted St, Chicago", o.dropoffText)
    }

    @Test fun earningsPillAtTopIsNotFare() {
        val items = listOf(ScreenText("$212.40", 80, 60)) + lines(
            "$9.85", "3 min (0.8 mi) away", "W Division St & N Ashland Ave", "12 min (3.1 mi) trip",
            "2100 N Milwaukee Ave", "Chicago, IL 60647", "Accept",
        )
        val o = OfferParser.parse("Uber", items, 2400)!!
        assertEquals(9.85, o.fare!!, 0.001)
        assertEquals("W Division St & N Ashland Ave", o.pickupText)
        assertEquals("2100 N Milwaukee Ave, Chicago, IL 60647", o.dropoffText)
    }

    @Test fun surgeLineIgnoredAndCityLineMerged() {
        val o = OfferParser.parse("Uber", lines(
            "$22.10", "+$4.00 Surge included", "Pickup", "8 mins (2.6 mi) away",
            "3401 W Fullerton Ave", "Chicago, IL", "Dropoff", "35 mins (18.2 mi) trip", "O'Hare International Airport", "Accept",
        ), 2400)!!
        assertEquals(22.10, o.fare!!, 0.001)
        assertEquals("3401 W Fullerton Ave, Chicago, IL", o.pickupText)
        assertEquals("O'Hare International Airport", o.dropoffText)
        assertEquals(35.0, o.tripMin!!, 0.01)
    }

    @Test fun lyftDotSeparatedFormat() {
        val o = OfferParser.parse("Lyft", lines(
            "Lyft", "$11.62", "Pickup 4 min · 1.1 mi", "1550 N Damen Ave", "Trip 1 hr 5 min · 31.4 mi",
            "500 W Ogden Ave, Naperville", "Accept",
        ), 2400)!!
        assertEquals(11.62, o.fare!!, 0.001)
        assertEquals(4.0, o.pickupMin!!, 0.01); assertEquals(1.1, o.pickupMi!!, 0.01)
        assertEquals(65.0, o.tripMin!!, 0.01); assertEquals(31.4, o.tripMi!!, 0.01)
        assertEquals("1550 N Damen Ave", o.pickupText)
        assertEquals("500 W Ogden Ave, Naperville", o.dropoffText)
    }

    @Test fun wholeCardInOneContentDescription() {
        val o = OfferParser.parse("Uber", listOf(ScreenText(
            "Trip request. $16.05. 5 mins (1.4 mi) away. 4800 S Cottage Grove Ave, Chicago. 19 mins (6.9 mi) trip. 233 S Wacker Dr, Chicago. Accept",
            1500, 900)), 2400)!!
        assertEquals(16.05, o.fare!!, 0.001)
        assertEquals("4800 S Cottage Grove Ave, Chicago", o.pickupText)
        assertEquals("233 S Wacker Dr, Chicago", o.dropoffText)
    }

    @Test fun neighborhoodNamesOnly() {
        val o = OfferParser.parse("Uber", lines("$12.00", "7 mins (2.0 mi) away", "Wicker Park", "18 mins (6.0 mi) trip", "Englewood", "Accept"), 2400)!!
        assertEquals("Wicker Park", o.pickupText)
        assertEquals("Englewood", o.dropoffText)
    }

    @Test fun homeScreenIsNotAnOffer() {
        assertNull(OfferParser.parse("Uber", lines("$212.40", "You're online", "Finding trips", "3 min wait"), 2400))
        assertNull(OfferParser.parse("Uber", lines("Earnings", "$84.10", "Trips", "6"), 2400))
    }

    @Test fun tripInProgressIsNotAnOffer() {
        assertNull(OfferParser.parse("Uber", lines("Dropping off John", "12 min (4.1 mi)", "6300 S Halsted St", "Complete trip"), 2400))
    }

    @Test fun addressLikeRules() {
        assertTrue(OfferParser.isAddressLike("1201 W Madison St"))
        assertTrue(OfferParser.isAddressLike("Midway International Airport"))
        assertFalse(OfferParser.isAddressLike("UberX"))
        assertFalse(OfferParser.isAddressLike("4.95"))
        assertFalse(OfferParser.isAddressLike("6 mins (1.9 mi) away"))
    }

    @Test fun ukrainianPlurals() {
        assertEquals("1 долар", Phrases.dollarsUk(1))
        assertEquals("22 долари", Phrases.dollarsUk(22))
        assertEquals("12 доларів", Phrases.dollarsUk(12))
        assertEquals("25 доларів", Phrases.dollarsUk(25))
        assertEquals("111 доларів", Phrases.dollarsUk(111))
    }

    @Test fun voicePhrase() {
        val v = Verdict(Level.RED, Level.GREEN, Level.RED, Level.GREEN, null, "Englewood", false, 18.0, 14.4, 1.5,
            30.0, 10.0, Profit.BAD, "")
        assertEquals("Червона зона. Заборонено. 14 доларів за годину, невигідно.", Phrases.full(v, VoiceLang.UK, true))
        assertEquals("Червона зона. Заборонено.", Phrases.full(v, VoiceLang.UK, false))
    }

    @Test fun sunTimesChicago() {
        val (rise, set) = SunTimes.riseSet(LocalDate.of(2026, 6, 21))
        assertTrue("rise $rise", rise.hour == 5 && rise.minute in 5..25)   // ~5:15
        assertTrue("set $set", set.hour == 20 && set.minute in 20..40)    // ~20:29
        val z = ZoneId.of("America/Chicago")
        assertTrue(SunTimes.isNight(ZonedDateTime.of(2026, 1, 10, 18, 0, 0, 0, z)))
        assertFalse(SunTimes.isNight(ZonedDateTime.of(2026, 1, 10, 12, 0, 0, 0, z)))
    }

    @Test fun versionCompare() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.9"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
    }
}

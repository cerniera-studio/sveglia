package com.cerniera.sveglia

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MattinaTest {
    private val z = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 19, h, m, 0, 0, z)  // a Thursday
    private val light = Mattina.Light(at(7, 41), at(16, 2), at(15, 2))

    @Test fun rainBeforeAnOutingSaysTakeABrolly() {
        val first = Mattina.First("Arsenal Women", at(16, 30), "Emirates Stadium")
        val p = Mattina.compose(at(7), first, Mattina.Rain(at(10), 0.7f, "Cielo"), null, light)
        assertEquals("Rain from 10:00, so take a brolly to Arsenal Women.", p.line)
        assertEquals(listOf("FIRST", "SKY", "LIGHT"), p.rows.map { it.label })
        assertEquals("Arsenal Women · 16:30", p.rows[0].main)
        assertEquals("in 9 h 30 min · Emirates Stadium", p.rows[0].sub)
        assertEquals("Rain from 10:00", p.rows[1].main)
        assertEquals("Sunrise 07:41", p.rows[2].main)
    }

    @Test fun aCallIsNotAnOuting() {
        val first = Mattina.First("Stand-up", at(9), "Microsoft Teams Meeting")
        val p = Mattina.compose(at(7, 30), first, Mattina.Rain(at(8), 0.8f, "Cielo"), null, light)
        assertEquals("Stand-up at 09:00: an hour and a half to get ready.", p.line)
    }

    @Test fun dryDayWithALateEvent() {
        val first = Mattina.First("Dinner", at(19), null)
        val p = Mattina.compose(at(7), first, null, "Dry today, 9°", light)
        assertEquals("A dry Thursday, and nothing until Dinner at 19:00.", p.line)
    }

    @Test fun clearDiaryUpBeforeTheSun() {
        val p = Mattina.compose(at(6, 30), null, null, "Dry today, 9°", light)
        assertEquals("Up before the sun: it rises at 07:41.", p.line)
        assertEquals(listOf("SKY", "LIGHT"), p.rows.map { it.label })
    }

    @Test fun afternoonRainWithNoEvents() {
        val p = Mattina.compose(at(8), null, Mattina.Rain(at(14), 0.6f, "Cielo"), null, light)
        assertEquals("Dry this morning, with rain from 14:00.", p.line)
        assertEquals("60% chance · Cielo", p.rows[0].sub)
    }

    @Test fun durations() {
        assertEquals("in 40 min", Mattina.inWords(java.time.Duration.ofMinutes(40)))
        assertEquals("in 2 h", Mattina.inWords(java.time.Duration.ofMinutes(120)))
        assertEquals("three hours", Mattina.spoken(java.time.Duration.ofMinutes(170)))
    }
}

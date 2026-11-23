package com.cerniera.sveglia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cronografo's arithmetic: what the bezel reads, and timers through pause, +1 min and reset. */
class ChronoTest {
    @Test fun tachymeterReadsSpeedOverAMeasuredUnit() {
        assertEquals(120, Chrono.tachymeter(30_000))   // a mile in 30 s: 120 mph
        assertEquals(60, Chrono.tachymeter(60_000))    // the bezel's last mark
        assertEquals(500, Chrono.tachymeter(7_200))    // its first
        assertNull(Chrono.tachymeter(61_000))
        assertNull(Chrono.tachymeter(5_000))
    }

    @Test fun pulsometerReadsBeatsAMinuteFromFifteen() {
        assertEquals(75, Chrono.pulsometer(12_000))    // 15 beats in 12 s
        assertEquals(60, Chrono.pulsometer(15_000))
        assertNull(Chrono.pulsometer(2_000))
    }

    @Test fun clockAndWords() {
        assertEquals("0:07", Chrono.clock(7_400))
        assertEquals("12:03", Chrono.clock(723_000))
        assertEquals("1:02:03", Chrono.clock(3_723_000))
        assertEquals("1:23.4", Chrono.clock(83_450, tenths = true))
        assertEquals("10 min", Chrono.words(600_000))
        assertEquals("1 h 30 min", Chrono.words(5_400_000))
        assertEquals("45 s", Chrono.words(45_000))
    }

    @Test fun timerPausesResumesAndGainsAMinute() {
        val t0 = 1_000_000L
        val t = Chrono.Timer(1, "", 600_000, t0 + 600_000, 600_000)
        assertEquals(400_000, t.remaining(t0 + 200_000))
        val paused = Chrono.pause(t, t0 + 200_000)
        assertEquals(400_000, paused.remaining(t0 + 900_000))   // the clock stops while paused
        val resumed = Chrono.start(paused, t0 + 900_000)
        assertEquals(t0 + 1_300_000, resumed.endsAt)
        val more = Chrono.addMinute(resumed, t0 + 900_000)
        assertEquals(t0 + 1_360_000, more.endsAt)
        val rang = more.copy(ringing = true, left = 0)
        val again = Chrono.addMinute(rang, t0 + 2_000_000)      // +1 min on a timer that has run out
        assertEquals(t0 + 2_060_000, again.endsAt); assertEquals(false, again.ringing)
        assertEquals(660_000, Chrono.reset(more).left)
    }

    @Test fun stopwatchLapsAndBanks() {
        var w = Chrono.Watch()
        w = Chrono.toggle(w, 0)                 // start
        w = Chrono.lap(w, 31_000)
        w = Chrono.lap(w, 59_000)
        w = Chrono.toggle(w, 80_000)            // stop
        assertEquals(80_000, w.elapsed(500_000))
        w = Chrono.toggle(w, 600_000)           // carry on
        assertEquals(90_000, w.elapsed(610_000))
        assertEquals(listOf(31_000L, 28_000L), Chrono.lapLengths(w.laps))
    }
}

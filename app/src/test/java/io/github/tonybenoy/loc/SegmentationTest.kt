package io.github.tonybenoy.loc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SegmentationTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val rnd = Random(42)

    // ~1 degree latitude = 111_320 m
    private fun pt(t: Long, lat: Double, lon: Double, acc: Float? = 10f, motion: String = "STILL") =
        RawPoint(timestamp = t, lat = lat, lon = lon, accuracy = acc, speed = null, altitude = null, source = "gps", motion = motion)

    /** Points every [stepS] seconds with ~5 m of jitter around a place. */
    private fun stay(from: Long, mins: Int, lat: Double, lon: Double, stepS: Int = 60): List<RawPoint> =
        (0..mins * 60 / stepS).map {
            pt(from + it * stepS * 1000L, lat + (rnd.nextDouble() - 0.5) * 8e-5, lon + (rnd.nextDouble() - 0.5) * 8e-5)
        }

    /** Straight walk north at [mps], points every 30 s. */
    private fun walk(from: Long, mins: Int, lat: Double, lon: Double, mps: Double): List<RawPoint> =
        (1..mins * 2).map {
            val metres = mps * it * 30
            pt(from + it * 30_000L, lat + metres / 111_320.0, lon, motion = "MOVING")
        }

    @Test
    fun twoStaysAndAWalkGiveTwoVisitsAndOneTrip() {
        val a = stay(t0, 30, 51.5000, -0.1200)
        val w = walk(a.last().timestamp, 10, 51.5000, -0.1200, 1.4)
        val endLat = w.last().lat
        val b = stay(w.last().timestamp + 60_000, 20, endLat, -0.1200)

        val s = Segmentation.segment(a + w + b)

        assertEquals(2, s.visits.size)
        assertEquals(1, s.trips.size)
        assertEquals(Mode.WALK, s.trips[0].mode)
        assertTrue(s.visits[0].end - s.visits[0].start >= 29 * minute)
        assertEquals(51.5000, s.visits[0].lat, 1e-3)
        // 10 min at 1.4 m/s is about 840 m
        assertEquals(840.0, s.trips[0].distanceM, 150.0)
    }

    @Test
    fun fastMovementIsClassifiedAsVehicle() {
        val a = stay(t0, 10, 51.5, -0.12)
        val d = walk(a.last().timestamp, 15, 51.5, -0.12, 14.0)
        val b = stay(d.last().timestamp + 60_000, 10, d.last().lat, -0.12)
        val s = Segmentation.segment(a + d + b)
        assertEquals(Mode.VEHICLE, s.trips.single().mode)
    }

    @Test
    fun stopShorterThanMinDwellIsNotAVisit() {
        val a = stay(t0, 30, 51.5, -0.12)
        val w1 = walk(a.last().timestamp, 5, 51.5, -0.12, 1.4)
        val pause = stay(w1.last().timestamp + 30_000, 2, w1.last().lat, -0.12)
        val w2 = walk(pause.last().timestamp, 5, w1.last().lat, -0.12, 1.4)
        val b = stay(w2.last().timestamp + 60_000, 20, w2.last().lat, -0.12)
        val s = Segmentation.segment(a + w1 + pause + w2 + b)
        assertEquals(2, s.visits.size)
    }

    @Test
    fun lowAccuracyAndImpossibleJumpsAreDropped() {
        val good = stay(t0, 10, 51.5, -0.12)
        val badAcc = pt(t0 + 5 * minute + 500, 51.6, -0.12, acc = 500f)
        val jump = pt(t0 + 5 * minute + 1000, 52.5, 1.0, acc = 10f) // ~100 km in 1 s
        val cleaned = Segmentation.clean((good + badAcc + jump).sortedBy { it.timestamp })
        assertEquals(good.size, cleaned.size)
    }

    @Test
    fun nearbyVisitsSeparatedByShortGapAreMerged() {
        val a = stay(t0, 20, 51.5, -0.12)
        // 6 minutes of GPS dropout, then the same place again
        val b = stay(a.last().timestamp + 6 * minute, 20, 51.5002, -0.12)
        val s = Segmentation.segment(a + b)
        assertEquals(1, s.visits.size)
        assertTrue(s.visits[0].end - s.visits[0].start >= 45 * minute)
    }

    @Test
    fun emptyInputGivesNothing() {
        val s = Segmentation.segment(emptyList())
        assertTrue(s.visits.isEmpty())
        assertTrue(s.trips.isEmpty())
    }
}

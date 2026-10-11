package io.github.tonybenoy.loc

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A stay: the user remained within [radiusM] of [lat],[lon] for at least the minimum dwell time. */
data class Visit(val start: Long, val end: Long, val lat: Double, val lon: Double, val radiusM: Double, val pointCount: Int)

enum class Mode { WALK, CYCLE, VEHICLE, UNKNOWN }

/** Movement between two visits (or before the first / after the last). */
data class Trip(val start: Long, val end: Long, val mode: Mode, val distanceM: Double, val points: List<RawPoint>)

data class Segments(val visits: List<Visit>, val trips: List<Trip>)

data class SegmentationConfig(
    val maxAccuracyM: Float = 100f,
    val maxSpeedMps: Double = 70.0,
    val visitRadiusM: Double = 50.0,
    val minDwellMs: Long = 5 * 60_000L,
    /** Two visits closer than this, separated by less than [mergeGapMs], become one (GPS dropout, short outings). */
    val mergeDistanceM: Double = 100.0,
    val mergeGapMs: Long = 10 * 60_000L,
    val walkMaxMps: Double = 2.2,
    val cycleMaxMps: Double = 6.0,
)

object Geo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * Turns raw points into visits and trips. Pure function of its input: raw points are never changed,
 * so this can be re-run over old data whenever the rules improve.
 */
object Segmentation {

    /** Drops low-accuracy fixes and fixes implying an impossible speed from the last kept fix. */
    fun clean(points: List<RawPoint>, cfg: SegmentationConfig = SegmentationConfig()): List<RawPoint> {
        val out = ArrayList<RawPoint>(points.size)
        for (p in points.sortedBy { it.timestamp }) {
            if (p.accuracy != null && p.accuracy > cfg.maxAccuracyM) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val dt = (p.timestamp - prev.timestamp) / 1000.0
                if (dt <= 0.0) continue
                val speed = Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon) / dt
                if (speed > cfg.maxSpeedMps) continue
            }
            out.add(p)
        }
        return out
    }

    fun segment(raw: List<RawPoint>, cfg: SegmentationConfig = SegmentationConfig()): Segments {
        val pts = clean(raw, cfg)
        val visits = mergeVisits(detectVisits(pts, cfg), cfg)
        return Segments(visits, tripsBetween(pts, visits, cfg))
    }

    internal fun detectVisits(pts: List<RawPoint>, cfg: SegmentationConfig): List<Visit> {
        val visits = ArrayList<Visit>()
        var i = 0
        while (i < pts.size) {
            var j = i
            var sumLat = 0.0
            var sumLon = 0.0
            // Grow a cluster while each new point stays within the radius of the running centroid.
            while (j < pts.size) {
                val n = j - i
                val cLat = if (n == 0) pts[j].lat else sumLat / n
                val cLon = if (n == 0) pts[j].lon else sumLon / n
                if (n > 0 && Geo.distanceM(cLat, cLon, pts[j].lat, pts[j].lon) > cfg.visitRadiusM) break
                sumLat += pts[j].lat
                sumLon += pts[j].lon
                j++
            }
            val n = j - i
            if (n >= 2 && pts[j - 1].timestamp - pts[i].timestamp >= cfg.minDwellMs) {
                val cLat = sumLat / n
                val cLon = sumLon / n
                val radius = (i until j).maxOf { Geo.distanceM(cLat, cLon, pts[it].lat, pts[it].lon) }
                visits.add(Visit(pts[i].timestamp, pts[j - 1].timestamp, cLat, cLon, radius, n))
                i = j
            } else {
                i++
            }
        }
        return visits
    }

    internal fun mergeVisits(visits: List<Visit>, cfg: SegmentationConfig): List<Visit> {
        val out = ArrayList<Visit>()
        for (v in visits) {
            val last = out.lastOrNull()
            if (last != null &&
                v.start - last.end <= cfg.mergeGapMs &&
                Geo.distanceM(last.lat, last.lon, v.lat, v.lon) <= cfg.mergeDistanceM
            ) {
                val total = last.pointCount + v.pointCount
                val lat = (last.lat * last.pointCount + v.lat * v.pointCount) / total
                val lon = (last.lon * last.pointCount + v.lon * v.pointCount) / total
                val radius = max(last.radiusM, v.radiusM) + Geo.distanceM(last.lat, last.lon, v.lat, v.lon)
                out[out.lastIndex] = Visit(last.start, v.end, lat, lon, radius, total)
            } else {
                out.add(v)
            }
        }
        return out
    }

    internal fun tripsBetween(pts: List<RawPoint>, visits: List<Visit>, cfg: SegmentationConfig): List<Trip> {
        // Boundaries: [-inf, v0.start), (v0.end, v1.start), ..., (vN.end, +inf]
        val bounds = ArrayList<Pair<Long, Long>>()
        var from = Long.MIN_VALUE
        for (v in visits) {
            bounds.add(from to v.start)
            from = v.end
        }
        bounds.add(from to Long.MAX_VALUE)

        return bounds.mapNotNull { (a, b) ->
            val seg = pts.filter { it.timestamp >= a && it.timestamp <= b }
            if (seg.size < 2) return@mapNotNull null
            var dist = 0.0
            for (k in 1 until seg.size) {
                dist += Geo.distanceM(seg[k - 1].lat, seg[k - 1].lon, seg[k].lat, seg[k].lon)
            }
            val durationS = (seg.last().timestamp - seg.first().timestamp) / 1000.0
            val avg = if (durationS > 0) dist / durationS else 0.0
            val mode = when {
                dist < 50 -> Mode.UNKNOWN
                avg <= cfg.walkMaxMps -> Mode.WALK
                avg <= cfg.cycleMaxMps -> Mode.CYCLE
                else -> Mode.VEHICLE
            }
            Trip(seg.first().timestamp, seg.last().timestamp, mode, dist, seg)
        }
    }
}

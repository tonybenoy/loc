package io.github.tonybenoy.loc

data class Gap(val from: Long, val to: Long, val whileMoving: Boolean) {
    val minutes: Long get() = (to - from) / 60_000
}

object Gaps {
    /**
     * Gaps longer than [thresholdMs] between consecutive points. A gap counts as "while moving"
     * if either neighbouring point was recorded in the MOVING state. The Phase 0 exit criterion
     * is: no such gap longer than 10 minutes.
     */
    fun find(points: List<RawPoint>, thresholdMs: Long = 10 * 60_000L): List<Gap> =
        points.zipWithNext().mapNotNull { (a, b) ->
            if (b.timestamp - a.timestamp > thresholdMs) {
                Gap(a.timestamp, b.timestamp, a.motion == "MOVING" || b.motion == "MOVING")
            } else null
        }
}

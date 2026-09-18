package ch.swhizkid.tailtrace.tracker.detect

import ch.swhizkid.tailtrace.tracker.model.RiskState
import ch.swhizkid.tailtrace.tracker.model.Sensitivity
import ch.swhizkid.tailtrace.tracker.model.TrackerEcosystem
import ch.swhizkid.tailtrace.tracker.model.TrackerSighting

/**
 * Temporal co-movement test: has this tracker been at enough of *my* distinct
 * places, over enough time, while actually close to me?
 *
 * Thresholds from AirGuard's field-tuned model (≥3 sightings, N distinct
 * locations, T minutes) plus an RSSI proximity gate AirGuard omits.
 */
object CoMovementEvaluator {

    const val WINDOW_MS = 24 * 3_600_000L
    const val ALERT_COOLDOWN_MS = 4 * 3_600_000L
    private const val DEDUP_MS = 15 * 60_000L

    data class Thresholds(
        val minSightings: Int,
        val minPlaces: Int,
        val minSpanMin: Int,
        val rssiFloorDbm: Int
    )

    data class Assessment(
        val riskState: RiskState,
        val sightings: Int,
        val distinctPlaces: Int,
        val spanMin: Long,
        val closeEnough: Boolean,
        val separatedSeen: Boolean
    )

    fun thresholdsFor(s: Sensitivity): Thresholds = when (s) {
        Sensitivity.HIGH -> Thresholds(minSightings = 3, minPlaces = 2, minSpanMin = 30, rssiFloorDbm = -90)
        Sensitivity.MEDIUM -> Thresholds(minSightings = 3, minPlaces = 3, minSpanMin = 45, rssiFloorDbm = -85)
        Sensitivity.LOW -> Thresholds(minSightings = 3, minPlaces = 4, minSpanMin = 90, rssiFloorDbm = -80)
    }

    fun evaluate(
        sightings: List<TrackerSighting>,
        ecosystem: TrackerEcosystem,
        t: Thresholds
    ): Assessment {
        if (sightings.isEmpty()) {
            return Assessment(RiskState.OBSERVED, 0, 0, 0, closeEnough = false, separatedSeen = false)
        }
        val sorted = sightings.sortedBy { it.timestamp }

        var effective = 0
        var lastCounted: Long? = null
        for (s in sorted) {
            if (lastCounted == null || s.timestamp - lastCounted >= DEDUP_MS) {
                effective++
                lastCounted = s.timestamp
            }
        }

        val distinctPlaces = sorted.mapNotNull { it.geohash7 }.toSet().size
        val spanMin = (sorted.last().timestamp - sorted.first().timestamp) / 60_000
        val maxRssi = sorted.maxOf { it.rssi }
        val closeEnough = maxRssi >= t.rssiFloorDbm
        val separatedSeen = ecosystem == TrackerEcosystem.TILE || sorted.any { it.separated }

        val riskState = when {
            !separatedSeen -> RiskState.OBSERVED
            effective >= t.minSightings && distinctPlaces >= t.minPlaces &&
                spanMin >= t.minSpanMin && closeEnough -> RiskState.ALERTING
            effective >= t.minSightings && closeEnough && distinctPlaces >= 1 -> RiskState.SUSPICIOUS
            else -> RiskState.OBSERVED
        }

        return Assessment(riskState, effective, distinctPlaces, spanMin, closeEnough, separatedSeen)
    }
}

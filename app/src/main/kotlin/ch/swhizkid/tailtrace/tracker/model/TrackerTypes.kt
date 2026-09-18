package ch.swhizkid.tailtrace.tracker.model

/** Tracker ecosystems parsed off the air. */
enum class TrackerEcosystem(val display: String) {
    APPLE_FIND_MY("Apple Find My"),
    GOOGLE_FMDN("Google Find My Device"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
    DULT("DULT tracker"),
    UNKNOWN("Unknown tracker")
}

/**
 * Whether a tracker is signalling it is away from its owner. SEPARATED is the
 * detectable/dangerous state. Tile has no separated flag, so Tile sightings are
 * UNKNOWN and rely on persistence alone.
 */
enum class SeparatedState { SEPARATED, NEAR_OWNER, UNKNOWN }

/** One parsed BLE sighting of a tracker at one instant. */
data class TrackerObservation(
    /** Best-effort stable identity within a rotation epoch (payload key / static MAC). */
    val stableId: String,
    val ecosystem: TrackerEcosystem,
    val mac: String,
    val rssi: Int,
    val separated: SeparatedState,
    val label: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Risk lifecycle for a tracked device (hysteretic; see CoMovementEvaluator). */
enum class RiskState { OBSERVED, SUSPICIOUS, ALERTING }

/** User-facing status, folding in the allowlist + learned baseline. */
enum class TrackerStatus { SAFE_APPROVED, SAFE_BASELINE, OBSERVED, SUSPICIOUS, ALERTING }

/** Detection sensitivity — trades time-to-alert against false positives. */
enum class Sensitivity { HIGH, MEDIUM, LOW }

/** A geotagged point where a tracker was seen, for the in-app co-movement trail. */
data class TrailPoint(val lat: Double, val lon: Double)

/** One persisted sighting, used by the co-movement test. */
data class TrackerSighting(
    val trackerId: String,
    val timestamp: Long,
    val rssi: Int,
    val separated: Boolean,
    val lat: Double? = null,
    val lon: Double? = null,
    val geohash7: String? = null,
    val id: Long = 0
)

data class PlaceRow(
    val geohash6: String,
    val label: String = "",
    val visitCount: Int = 0,
    val lastSeen: Long = 0,
    val anchor: Boolean = false
)

data class TrackerRow(
    val stableId: String,
    val ecosystem: String,
    val label: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightingCount: Int,
    val riskState: String,
    val approved: Boolean = false,
    val baselineSafe: Boolean = false,
    val lastAlertMs: Long = 0,
    val lastAnchorDay: Long = -1,
    val anchorDayCount: Int = 0,
    val lastRssi: Int = 0,
    val peakRssi: Int = -127,
    val distinctPlaces: Int = 0,
    val effectiveSightings: Int = 0,
    val lastMac: String = ""
)

data class TrackerAlert(
    val id: Long = 0,
    val trackerId: String,
    val ecosystem: String,
    val label: String,
    val timestamp: Long,
    val distinctPlaces: Int,
    val peakRssi: Int,
    val lat: Double? = null,
    val lon: Double? = null
)

package ch.swhizkid.tailtrace.tracker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ch.swhizkid.tailtrace.tracker.detect.BaselineManager
import ch.swhizkid.tailtrace.tracker.detect.CoMovementEvaluator
import ch.swhizkid.tailtrace.tracker.model.RiskState
import ch.swhizkid.tailtrace.tracker.model.SeparatedState
import ch.swhizkid.tailtrace.tracker.model.Sensitivity
import ch.swhizkid.tailtrace.tracker.model.TrackerAlert
import ch.swhizkid.tailtrace.tracker.model.TrackerEcosystem
import ch.swhizkid.tailtrace.tracker.model.TrackerObservation
import ch.swhizkid.tailtrace.tracker.model.TrackerRow
import ch.swhizkid.tailtrace.tracker.model.TrackerSighting
import ch.swhizkid.tailtrace.tracker.model.TrailPoint
import ch.swhizkid.tailtrace.tracker.util.Geohash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Temporal core. Persists sightings, maintains the learned baseline, and runs
 * co-movement evaluation on every observation. All state is on-device.
 */
class TrackerRepository(private val db: TrackerDatabase) {

    private val mutex = Mutex()

    data class RecordResult(val tracker: TrackerRow, val newlyAlerting: Boolean)

    private val _trackers = MutableStateFlow<List<TrackerRow>>(emptyList())
    val trackers: StateFlow<List<TrackerRow>> = _trackers.asStateFlow()

    private val _alerts = MutableStateFlow<List<TrackerAlert>>(emptyList())
    val alerts: StateFlow<List<TrackerAlert>> = _alerts.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { reloadLocked() }
    }

    suspend fun setApproved(id: String, approved: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.setApproved(id, approved)
            reloadLocked()
        }
    }

    suspend fun clearBaseline(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.clearBaseline(id)
            reloadLocked()
        }
    }

    suspend fun prune(retentionDays: Int = RETENTION_DAYS) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cutoff = System.currentTimeMillis() - retentionDays * BaselineManager.DAY_MS
            db.pruneSightings(cutoff)
            db.pruneStaleTrackers(cutoff)
            reloadLocked()
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.clearTrackers()
            db.clearSightings()
            reloadLocked()
        }
    }

    suspend fun clearAlerts() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.clearAlerts()
            reloadLocked()
        }
    }

    suspend fun record(
        obs: TrackerObservation,
        lat: Double?,
        lon: Double?,
        sensitivity: Sensitivity
    ): RecordResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = obs.timestampMs
            val geohash7 = if (lat != null && lon != null) Geohash.encode(lat, lon, 7) else null
            val geohash6 = if (lat != null && lon != null) Geohash.encode(lat, lon, 6) else null

            db.insertSighting(
                TrackerSighting(
                    trackerId = obs.stableId,
                    timestamp = now,
                    rssi = obs.rssi,
                    separated = obs.separated == SeparatedState.SEPARATED,
                    lat = lat, lon = lon, geohash7 = geohash7
                )
            )

            val existing = db.getTracker(obs.stableId)
            val approved = existing?.approved ?: false

            val since = now - CoMovementEvaluator.WINDOW_MS
            val recent = db.recentSightings(obs.stableId, since)
            val t = CoMovementEvaluator.thresholdsFor(sensitivity)
            val assessment = CoMovementEvaluator.evaluate(recent, obs.ecosystem, t)
            val coMoving = assessment.riskState != RiskState.OBSERVED

            var lastAnchorDay = existing?.lastAnchorDay ?: -1L
            var anchorDayCount = existing?.anchorDayCount ?: 0
            var baselineSafe = existing?.baselineSafe ?: false
            if (geohash6 != null) {
                val isAnchor = BaselineManager.noteLocation(db, geohash6, now)
                if (isAnchor && !coMoving) {
                    val day = now / BaselineManager.DAY_MS
                    if (day != lastAnchorDay) {
                        lastAnchorDay = day
                        anchorDayCount += 1
                    }
                    if (anchorDayCount >= BaselineManager.BASELINE_MIN_DAYS) baselineSafe = true
                }
            }
            if (baselineSafe && coMoving) baselineSafe = false

            val riskState = if (approved || baselineSafe) RiskState.OBSERVED else assessment.riskState

            val wasAlerting = existing?.riskState == RiskState.ALERTING.name
            val cooldownOk = now - (existing?.lastAlertMs ?: 0) > CoMovementEvaluator.ALERT_COOLDOWN_MS
            val newlyAlerting = riskState == RiskState.ALERTING && !wasAlerting && cooldownOk

            val updated = TrackerRow(
                stableId = obs.stableId,
                ecosystem = obs.ecosystem.name,
                label = obs.label,
                firstSeen = existing?.firstSeen ?: now,
                lastSeen = now,
                sightingCount = (existing?.sightingCount ?: 0) + 1,
                riskState = riskState.name,
                approved = approved,
                baselineSafe = baselineSafe,
                lastAlertMs = if (newlyAlerting) now else (existing?.lastAlertMs ?: 0),
                lastAnchorDay = lastAnchorDay,
                anchorDayCount = anchorDayCount,
                lastRssi = obs.rssi,
                peakRssi = maxOf(existing?.peakRssi ?: -127, obs.rssi),
                distinctPlaces = assessment.distinctPlaces,
                effectiveSightings = assessment.sightings,
                lastMac = obs.mac
            )
            db.upsertTracker(updated)
            if (newlyAlerting) {
                db.insertAlert(
                    TrackerAlert(
                        trackerId = obs.stableId,
                        ecosystem = obs.ecosystem.name,
                        label = obs.label,
                        timestamp = now,
                        distinctPlaces = assessment.distinctPlaces,
                        peakRssi = updated.peakRssi,
                        lat = lat, lon = lon
                    )
                )
            }
            reloadLocked()
            RecordResult(updated, newlyAlerting)
        }
    }

    suspend fun trailFor(id: String): List<TrailPoint> = withContext(Dispatchers.IO) {
        db.allSightings(id).mapNotNull { s ->
            val la = s.lat; val lo = s.lon
            if (la != null && lo != null) TrailPoint(la, lo) else null
        }
    }

    suspend fun buildTextReport(): String? = withContext(Dispatchers.IO) {
        val alerts = db.allAlerts()
        if (alerts.isEmpty()) return@withContext null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("TailTrace — tracker evidence report")
        sb.appendLine("Generated: ${fmt.format(Date())}")
        sb.appendLine("Times are this phone's local time; locations are approximate (phone GPS).")
        sb.appendLine("=".repeat(52))
        for ((trackerId, group) in alerts.groupBy { it.trackerId }) {
            val head = group.first()
            sb.appendLine()
            sb.appendLine("${ecoLabel(head.ecosystem)} — ${head.label}")
            sb.appendLine("Identity: $trackerId")
            sb.appendLine("Flagged ${group.size} time(s):")
            for (a in group) {
                val where = if (a.lat != null && a.lon != null) "%.5f, %.5f".format(a.lat, a.lon) else "no location"
                sb.appendLine("  - ${fmt.format(Date(a.timestamp))} : ${a.distinctPlaces} places, peak ${a.peakRssi} dBm, at $where")
            }
            val geo = db.allSightings(trackerId).filter { it.lat != null && it.lon != null }
            if (geo.isNotEmpty()) {
                sb.appendLine("  Seen with you at ${geo.size} location(s):")
                for (s in geo) {
                    sb.appendLine("    ${fmt.format(Date(s.timestamp))}  ${"%.5f, %.5f".format(s.lat, s.lon)}  ${s.rssi} dBm")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("=".repeat(52))
        sb.appendLine("Recorded by TailTrace — a log of Bluetooth trackers detected moving with this phone.")
        return@withContext sb.toString()
    }

    suspend fun buildGpx(): String = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="TailTrace" xmlns="http://www.topografix.com/GPX/1/1">""")
        for (s in db.allGeotaggedSightings()) {
            sb.appendLine("""  <wpt lat="${s.lat}" lon="${s.lon}">""")
            sb.appendLine("    <time>${fmt.format(Date(s.timestamp))}</time>")
            sb.appendLine("    <name>${trackerShortName(s.trackerId)} ${s.rssi}dBm</name>")
            sb.appendLine("  </wpt>")
        }
        sb.appendLine("</gpx>")
        sb.toString()
    }

    private fun reloadLocked() {
        _trackers.value = db.allTrackers()
        _alerts.value = db.allAlerts()
    }

    companion object {
        const val RETENTION_DAYS = 14

        @Volatile
        private var instance: TrackerRepository? = null

        fun get(context: Context): TrackerRepository {
            return instance ?: synchronized(this) {
                instance ?: TrackerRepository(TrackerDatabase(context.applicationContext)).also {
                    instance = it
                }
            }
        }
    }
}

private fun ecoLabel(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun trackerShortName(stableId: String): String = when (stableId.substringBefore(':')) {
    "apple" -> "AppleFindMy"
    "fmdn" -> "GoogleFMD"
    "samsung" -> "SmartTag"
    "tile" -> "Tile"
    "dult" -> "DULT"
    else -> "Tracker"
}

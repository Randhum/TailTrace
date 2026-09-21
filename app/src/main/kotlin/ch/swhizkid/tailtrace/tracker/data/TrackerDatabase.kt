package ch.swhizkid.tailtrace.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ch.swhizkid.tailtrace.tracker.detect.BaselineManager
import ch.swhizkid.tailtrace.tracker.model.PlaceRow
import ch.swhizkid.tailtrace.tracker.model.TrackerAlert
import ch.swhizkid.tailtrace.tracker.model.TrackerRow
import ch.swhizkid.tailtrace.tracker.model.TrackerSighting

/**
 * On-device tracker store. Sightings, allowlist, learned places, and alerts
 * never leave the phone. Separate from the radio catalog so a schema bump
 * here cannot wipe overheard MACs.
 */
class TrackerDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    NAME,
    null,
    VERSION
), BaselineManager.PlaceStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trackers (
                stable_id TEXT PRIMARY KEY,
                ecosystem TEXT NOT NULL,
                label TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                sighting_count INTEGER NOT NULL,
                risk_state TEXT NOT NULL,
                approved INTEGER NOT NULL DEFAULT 0,
                baseline_safe INTEGER NOT NULL DEFAULT 0,
                last_alert_ms INTEGER NOT NULL DEFAULT 0,
                last_anchor_day INTEGER NOT NULL DEFAULT -1,
                anchor_day_count INTEGER NOT NULL DEFAULT 0,
                last_rssi INTEGER NOT NULL DEFAULT 0,
                peak_rssi INTEGER NOT NULL DEFAULT -127,
                distinct_places INTEGER NOT NULL DEFAULT 0,
                effective_sightings INTEGER NOT NULL DEFAULT 0,
                last_mac TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tracker_sightings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tracker_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                rssi INTEGER NOT NULL,
                separated INTEGER NOT NULL,
                lat REAL,
                lon REAL,
                geohash7 TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ts_tracker ON tracker_sightings(tracker_id, timestamp)")
        db.execSQL(
            """
            CREATE TABLE tracker_places (
                geohash6 TEXT PRIMARY KEY,
                label TEXT NOT NULL DEFAULT '',
                visit_count INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL DEFAULT 0,
                anchor INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tracker_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tracker_id TEXT NOT NULL,
                ecosystem TEXT NOT NULL,
                label TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                distinct_places INTEGER NOT NULL,
                peak_rssi INTEGER NOT NULL,
                lat REAL,
                lon REAL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema. Future versions add columns here — never drop.
    }

    fun getTracker(id: String): TrackerRow? {
        readableDatabase.rawQuery(
            "SELECT $TRACKER_COLS FROM trackers WHERE stable_id = ?",
            arrayOf(id)
        ).use { c -> return if (c.moveToFirst()) readTracker(c) else null }
    }

    fun upsertTracker(row: TrackerRow) {
        val values = ContentValues().apply {
            put("stable_id", row.stableId)
            put("ecosystem", row.ecosystem)
            put("label", row.label)
            put("first_seen", row.firstSeen)
            put("last_seen", row.lastSeen)
            put("sighting_count", row.sightingCount)
            put("risk_state", row.riskState)
            put("approved", if (row.approved) 1 else 0)
            put("baseline_safe", if (row.baselineSafe) 1 else 0)
            put("last_alert_ms", row.lastAlertMs)
            put("last_anchor_day", row.lastAnchorDay)
            put("anchor_day_count", row.anchorDayCount)
            put("last_rssi", row.lastRssi)
            put("peak_rssi", row.peakRssi)
            put("distinct_places", row.distinctPlaces)
            put("effective_sightings", row.effectiveSightings)
            put("last_mac", row.lastMac)
        }
        writableDatabase.insertWithOnConflict("trackers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allTrackers(): List<TrackerRow> {
        readableDatabase.rawQuery(
            "SELECT $TRACKER_COLS FROM trackers ORDER BY last_seen DESC",
            null
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readTracker(c)) }
        }
    }

    fun setApproved(id: String, approved: Boolean) {
        val values = ContentValues().apply { put("approved", if (approved) 1 else 0) }
        writableDatabase.update("trackers", values, "stable_id = ?", arrayOf(id))
    }

    fun clearBaseline(id: String) {
        val values = ContentValues().apply {
            put("baseline_safe", 0)
            put("anchor_day_count", 0)
            put("last_anchor_day", -1)
        }
        writableDatabase.update("trackers", values, "stable_id = ?", arrayOf(id))
    }

    fun pruneStaleTrackers(cutoff: Long) {
        writableDatabase.delete("trackers", "last_seen < ? AND approved = 0", arrayOf(cutoff.toString()))
    }

    fun clearTrackers() {
        writableDatabase.delete("trackers", null, null)
    }

    fun insertSighting(s: TrackerSighting) {
        val values = ContentValues().apply {
            put("tracker_id", s.trackerId)
            put("timestamp", s.timestamp)
            put("rssi", s.rssi)
            put("separated", if (s.separated) 1 else 0)
            if (s.lat != null) put("lat", s.lat) else putNull("lat")
            if (s.lon != null) put("lon", s.lon) else putNull("lon")
            put("geohash7", s.geohash7)
        }
        writableDatabase.insert("tracker_sightings", null, values)
    }

    fun recentSightings(id: String, since: Long): List<TrackerSighting> {
        readableDatabase.rawQuery(
            """
            SELECT id, tracker_id, timestamp, rssi, separated, lat, lon, geohash7
            FROM tracker_sightings
            WHERE tracker_id = ? AND timestamp >= ?
            ORDER BY timestamp
            """.trimIndent(),
            arrayOf(id, since.toString())
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readSighting(c)) }
        }
    }

    fun allSightings(id: String): List<TrackerSighting> {
        readableDatabase.rawQuery(
            """
            SELECT id, tracker_id, timestamp, rssi, separated, lat, lon, geohash7
            FROM tracker_sightings
            WHERE tracker_id = ?
            ORDER BY timestamp
            """.trimIndent(),
            arrayOf(id)
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readSighting(c)) }
        }
    }

    fun allGeotaggedSightings(): List<TrackerSighting> {
        readableDatabase.rawQuery(
            """
            SELECT id, tracker_id, timestamp, rssi, separated, lat, lon, geohash7
            FROM tracker_sightings
            WHERE lat IS NOT NULL
            ORDER BY timestamp
            """.trimIndent(),
            null
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readSighting(c)) }
        }
    }

    fun pruneSightings(cutoff: Long) {
        writableDatabase.delete("tracker_sightings", "timestamp < ?", arrayOf(cutoff.toString()))
    }

    fun clearSightings() {
        writableDatabase.delete("tracker_sightings", null, null)
    }

    override fun get(geohash6: String): PlaceRow? {
        readableDatabase.rawQuery(
            "SELECT geohash6, label, visit_count, last_seen, anchor FROM tracker_places WHERE geohash6 = ?",
            arrayOf(geohash6)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PlaceRow(
                geohash6 = c.getString(0),
                label = c.getString(1),
                visitCount = c.getInt(2),
                lastSeen = c.getLong(3),
                anchor = c.getInt(4) != 0
            )
        }
    }

    override fun upsert(place: PlaceRow) {
        val values = ContentValues().apply {
            put("geohash6", place.geohash6)
            put("label", place.label)
            put("visit_count", place.visitCount)
            put("last_seen", place.lastSeen)
            put("anchor", if (place.anchor) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "tracker_places", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun insertAlert(a: TrackerAlert) {
        val values = ContentValues().apply {
            put("tracker_id", a.trackerId)
            put("ecosystem", a.ecosystem)
            put("label", a.label)
            put("timestamp", a.timestamp)
            put("distinct_places", a.distinctPlaces)
            put("peak_rssi", a.peakRssi)
            if (a.lat != null) put("lat", a.lat) else putNull("lat")
            if (a.lon != null) put("lon", a.lon) else putNull("lon")
        }
        writableDatabase.insert("tracker_alerts", null, values)
    }

    fun allAlerts(): List<TrackerAlert> {
        readableDatabase.rawQuery(
            """
            SELECT id, tracker_id, ecosystem, label, timestamp, distinct_places, peak_rssi, lat, lon
            FROM tracker_alerts
            ORDER BY timestamp DESC
            """.trimIndent(),
            null
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readAlert(c)) }
        }
    }

    fun clearAlerts() {
        writableDatabase.delete("tracker_alerts", null, null)
    }

    private fun readTracker(c: Cursor) = TrackerRow(
        stableId = c.getString(0),
        ecosystem = c.getString(1),
        label = c.getString(2),
        firstSeen = c.getLong(3),
        lastSeen = c.getLong(4),
        sightingCount = c.getInt(5),
        riskState = c.getString(6),
        approved = c.getInt(7) != 0,
        baselineSafe = c.getInt(8) != 0,
        lastAlertMs = c.getLong(9),
        lastAnchorDay = c.getLong(10),
        anchorDayCount = c.getInt(11),
        lastRssi = c.getInt(12),
        peakRssi = c.getInt(13),
        distinctPlaces = c.getInt(14),
        effectiveSightings = c.getInt(15),
        lastMac = c.getString(16)
    )

    private fun readSighting(c: Cursor) = TrackerSighting(
        id = c.getLong(0),
        trackerId = c.getString(1),
        timestamp = c.getLong(2),
        rssi = c.getInt(3),
        separated = c.getInt(4) != 0,
        lat = if (c.isNull(5)) null else c.getDouble(5),
        lon = if (c.isNull(6)) null else c.getDouble(6),
        geohash7 = c.getString(7)
    )

    private fun readAlert(c: Cursor) = TrackerAlert(
        id = c.getLong(0),
        trackerId = c.getString(1),
        ecosystem = c.getString(2),
        label = c.getString(3),
        timestamp = c.getLong(4),
        distinctPlaces = c.getInt(5),
        peakRssi = c.getInt(6),
        lat = if (c.isNull(7)) null else c.getDouble(7),
        lon = if (c.isNull(8)) null else c.getDouble(8)
    )

    companion object {
        const val NAME = "tailtrace_trackers.db"
        const val VERSION = 1
        private const val TRACKER_COLS = """
            stable_id, ecosystem, label, first_seen, last_seen, sighting_count, risk_state,
            approved, baseline_safe, last_alert_ms, last_anchor_day, anchor_day_count,
            last_rssi, peak_rssi, distinct_places, effective_sightings, last_mac
        """
    }
}

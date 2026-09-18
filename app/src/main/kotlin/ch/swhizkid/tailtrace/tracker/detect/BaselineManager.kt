package ch.swhizkid.tailtrace.tracker.detect

import ch.swhizkid.tailtrace.tracker.model.PlaceRow

/**
 * Learns the user's "safe" RF world so household tags at home/work fall silent.
 *
 * Count location-tagged visits per geohash-6 cell; once a cell crosses
 * [ANCHOR_MIN_VISITS] it's an anchor. A tracker seen at an anchor on ≥
 * [BASELINE_MIN_DAYS] distinct days is marked baseline-safe.
 */
object BaselineManager {

    const val DAY_MS = 86_400_000L
    const val DWELL_BUCKET_MS = 10 * 60_000L
    const val ANCHOR_MIN_VISITS = 18
    const val BASELINE_MIN_DAYS = 3

    interface PlaceStore {
        fun get(geohash6: String): PlaceRow?
        fun upsert(place: PlaceRow)
    }

    /**
     * Note presence in a cell; returns whether it's an anchor. A cell accrues
     * at most ONE visit per [DWELL_BUCKET_MS], so the anchor signal tracks time
     * actually spent there — not how many trackers or adverts were seen.
     */
    fun noteLocation(store: PlaceStore, geohash6: String, now: Long): Boolean {
        val existing = store.get(geohash6) ?: run {
            store.upsert(PlaceRow(geohash6 = geohash6, visitCount = 1, lastSeen = now, anchor = false))
            return false
        }
        if (now - existing.lastSeen < DWELL_BUCKET_MS) return existing.anchor
        val visits = existing.visitCount + 1
        val anchor = visits >= ANCHOR_MIN_VISITS
        store.upsert(existing.copy(visitCount = visits, lastSeen = now, anchor = anchor))
        return anchor
    }
}

package ch.swhizkid.tailtrace.tracker.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ch.swhizkid.tailtrace.tracker.model.PlaceRow

class BaselineManagerTest {

    private class Mem : BaselineManager.PlaceStore {
        val rows = HashMap<String, PlaceRow>()
        override fun get(geohash6: String) = rows[geohash6]
        override fun upsert(place: PlaceRow) { rows[place.geohash6] = place }
    }

    @Test
    fun firstVisitIsNotAnchor() {
        val store = Mem()
        assertFalse(BaselineManager.noteLocation(store, "u0nzhj", 0L))
        assertEqualsVisits(store, 1)
    }

    @Test
    fun dwellBucketDoesNotDoubleCount() {
        val store = Mem()
        BaselineManager.noteLocation(store, "u0nzhj", 0L)
        assertFalse(BaselineManager.noteLocation(store, "u0nzhj", BaselineManager.DWELL_BUCKET_MS - 1))
        assertEqualsVisits(store, 1)
    }

    @Test
    fun enoughDwellPromotesAnchor() {
        val store = Mem()
        var now = 0L
        var last = false
        repeat(BaselineManager.ANCHOR_MIN_VISITS) {
            last = BaselineManager.noteLocation(store, "u0nzhj", now)
            now += BaselineManager.DWELL_BUCKET_MS
        }
        assertTrue(last)
    }

    private fun assertEqualsVisits(store: Mem, n: Int) {
        org.junit.Assert.assertEquals(n, store.rows["u0nzhj"]?.visitCount)
    }
}

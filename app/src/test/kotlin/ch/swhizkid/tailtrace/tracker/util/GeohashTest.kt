package ch.swhizkid.tailtrace.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashTest {

    @Test
    fun zurichLength7IsStable() {
        val h = Geohash.encode(47.3769, 8.5417, 7)
        assertEquals(7, h.length)
        assertTrue(h.all { it in "0123456789bcdefghjkmnpqrstuvwxyz" })
        assertEquals(h, Geohash.encode(47.3769, 8.5417, 7))
    }

    @Test
    fun nearbyPointsSharePrefix() {
        val a = Geohash.encode(47.0, 8.0, 5)
        val b = Geohash.encode(47.001, 8.001, 5)
        assertEquals(a, b)
    }
}

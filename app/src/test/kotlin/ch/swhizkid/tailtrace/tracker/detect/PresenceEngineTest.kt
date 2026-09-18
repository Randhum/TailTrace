package ch.swhizkid.tailtrace.tracker.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEngineTest {

    private val stepMs = 2_000L
    private val steps = 900
    private val cloneRotationMs = 30_000L

    private fun maxTier(
        idAt: (Int) -> String,
        rssiAt: (Int) -> Int,
        latAt: (Int) -> Double?,
        lonAt: (Int) -> Double?
    ): PresenceEngine.Tier {
        val engine = PresenceEngine()
        engine.reset()
        var max = PresenceEngine.Tier.CLEAR
        for (i in 0 until steps) {
            val v = engine.onSighting(idAt(i), rssiAt(i), i * stepMs, latAt(i), lonAt(i))
            if (v.tier.ordinal > max.ordinal) max = v.tier
        }
        return max
    }

    @Test
    fun cloneMovingIsConfirmed() {
        val tier = maxTier(
            idAt = { i -> "clone-${(i * stepMs) / cloneRotationMs}" },
            rssiAt = { i -> -55 + (i % 3 - 1) },
            latAt = { 40.0 },
            lonAt = { i -> -74.0 + i * 0.00003 }
        )
        assertEquals(PresenceEngine.Tier.CONFIRMED, tier)
    }

    @Test
    fun cloneStationaryIsNotConfirmed() {
        val tier = maxTier(
            idAt = { i -> "clone-${(i * stepMs) / cloneRotationMs}" },
            rssiAt = { i -> -55 + (i % 3 - 1) },
            latAt = { 40.0 },
            lonAt = { -74.0 }
        )
        assertTrue("stationary clone should not confirm, was $tier", tier.ordinal < PresenceEngine.Tier.CONFIRMED.ordinal)
    }

    @Test
    fun singleRealTrackerIsClear() {
        val tier = maxTier(
            idAt = { "realtag" },
            rssiAt = { i -> -55 + (i % 3 - 1) },
            latAt = { 40.0 },
            lonAt = { i -> -74.0 + i * 0.00003 }
        )
        assertEquals(PresenceEngine.Tier.CLEAR, tier)
    }

    @Test
    fun ambientFarIsClear() {
        val tier = maxTier(
            idAt = { i -> "ambient-$i" },
            rssiAt = { -85 },
            latAt = { i -> 40.0 + i * 0.00003 },
            lonAt = { -74.0 }
        )
        assertEquals(PresenceEngine.Tier.CLEAR, tier)
    }
}

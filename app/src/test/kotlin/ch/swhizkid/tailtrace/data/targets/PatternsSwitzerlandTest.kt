package ch.swhizkid.tailtrace.data.targets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternsSwitzerlandTest {

    @Test
    fun matchesEuropeanCameraNamesAndSsids() {
        assertTrue(Patterns.bleNameMatch("HIKVISION-CAM"))
        assertTrue(Patterns.bleNameMatch("Axis-ACCC8E"))
        assertTrue(Patterns.ssidGenericMatch("axis-office"))
        assertTrue(Patterns.ssidGenericMatch("Kantonspolizei-ZH"))
        assertTrue(Patterns.ssidFlockFormat("axis-A1B2C3"))
    }

    @Test
    fun doesNotMatchPublicAccessOrUsFlock() {
        assertFalse(Patterns.ssidGenericMatch("SBB-Free"))
        assertFalse(Patterns.ssidGenericMatch("Swisscom_Auto"))
        assertFalse(Patterns.ssidGenericMatch("Flock-1A2B"))
        assertFalse(Patterns.bleNameMatch("Penguin"))
        assertFalse(Patterns.bleNameMatch("FlockCam"))
        assertFalse(Patterns.isPenguinNumeric("12345678"))
    }

    @Test
    fun flockSupplyChainOuisAreNotInSwissSets() {
        assertFalse(WifiOuis.matches("70:c9:4e:00:00:01"))
        assertFalse(BleOuis.matches("a4:cf:12:00:00:01"))
        assertTrue(WifiOuis.matches("c0:56:e3:11:22:33"))
        assertTrue(BleOuis.matches("00:40:8c:aa:bb:cc"))
        assertTrue(BleOuis.isAxon("00:25:DF:01:02:03"))
    }
}

package ch.swhizkid.tailtrace.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioGateTest {

    private class FakeBle : RadioGate.BleClient {
        var yielded = 0
        var resumed = 0
        override fun yieldLeScan() { yielded++ }
        override fun resumeLeScan() { resumed++ }
    }

    @Before
    fun reset() {
        RadioGate.resetForTest()
    }

    @Test
    fun btExclusiveYieldsAndResumesClients() {
        val ble = FakeBle()
        RadioGate.registerBle(ble)
        RadioGate.acquireBtExclusive("a")
        assertTrue(RadioGate.btExclusive)
        assertEquals(1, ble.yielded)
        assertEquals(0, ble.resumed)

        RadioGate.acquireBtExclusive("a") // idempotent per owner
        assertEquals(1, ble.yielded)

        RadioGate.releaseBtExclusive("a")
        assertFalse(RadioGate.btExclusive)
        assertEquals(1, ble.resumed)
        RadioGate.unregisterBle(ble)
    }

    @Test
    fun secondOwnerDoesNotResumeUntilLastRelease() {
        val ble = FakeBle()
        RadioGate.registerBle(ble)
        RadioGate.acquireBtExclusive(RadioGate.OWNER_BT_FINDER)
        RadioGate.acquireBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        assertEquals(1, ble.yielded)

        RadioGate.releaseBtExclusive(RadioGate.OWNER_BT_FINDER)
        assertTrue(RadioGate.btExclusive)
        assertEquals(0, ble.resumed)

        RadioGate.releaseBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        assertFalse(RadioGate.btExclusive)
        assertEquals(1, ble.resumed)
        RadioGate.unregisterBle(ble)
    }

    @Test
    fun releaseUnknownOwnerIsNoOp() {
        RadioGate.acquireBtExclusive("a")
        RadioGate.releaseBtExclusive("b")
        assertTrue(RadioGate.btExclusive)
        RadioGate.releaseBtExclusive("a")
        assertFalse(RadioGate.btExclusive)
    }

    @Test
    fun lateBleClientYieldsIfExclusiveAlreadyHeld() {
        RadioGate.acquireBtExclusive("a")
        val ble = FakeBle()
        RadioGate.registerBle(ble)
        assertEquals(1, ble.yielded)
        RadioGate.unregisterBle(ble)
    }

    @Test
    fun btExclusiveBlocksWifiTriggersWhenNoOwner() {
        RadioGate.acquireBtExclusive("a")
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER))
        RadioGate.releaseBtExclusive("a")
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
    }

    @Test
    fun wifiHuntKeepsOwnTriggersWhileBtExclusive() {
        RadioGate.acquireWifi(RadioGate.WIFI_FINDER)
        RadioGate.acquireBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER))
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
        RadioGate.releaseBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        RadioGate.releaseWifi(RadioGate.WIFI_FINDER)
    }

    @Test
    fun inquiryBlocksWifiTriggersForEveryone() {
        RadioGate.setInquiry(true)
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER))
        RadioGate.setInquiry(false)
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER))
    }

    @Test
    fun wifiOwnerBlocksTheOtherParty() {
        RadioGate.acquireWifi(RadioGate.WIFI_FINDER)
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER))
        assertFalse(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
        RadioGate.releaseWifi(RadioGate.WIFI_FINDER)
        assertTrue(RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION))
    }

    @Test
    fun releaseWifiIgnoresMismatchedOwner() {
        RadioGate.acquireWifi(RadioGate.WIFI_FINDER)
        RadioGate.releaseWifi(RadioGate.WIFI_DETECTION)
        assertEquals(RadioGate.WIFI_FINDER, RadioGate.wifiOwner)
        RadioGate.releaseWifi(RadioGate.WIFI_FINDER)
        assertEquals(null, RadioGate.wifiOwner)
    }
}

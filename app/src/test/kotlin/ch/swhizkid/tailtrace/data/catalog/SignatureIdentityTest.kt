package ch.swhizkid.tailtrace.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureIdentityTest {

    @Test
    fun normalizeAddress_colonAndDash() {
        assertEquals("AA:BB:CC:DD:EE:FF", SignatureIdentity.normalizeAddress("aa-bb-cc-dd-ee-ff"))
        assertEquals("00:25:DF:01:02:03", SignatureIdentity.normalizeAddress(" 00:25:df:01:02:03 "))
    }

    @Test
    fun ouiOf_firstThreeOctets() {
        assertEquals("00:25:DF", SignatureIdentity.ouiOf("00:25:df:01:02:03"))
        assertNull(SignatureIdentity.ouiOf("short"))
    }

    @Test
    fun key_isRadioPlusAddress() {
        assertEquals(
            "ble|00:25:DF:01:02:03",
            SignatureIdentity.key(Radio.BLE, "00:25:df:01:02:03")
        )
        assertEquals(
            "wifi|70:C9:4E:AA:BB:CC",
            SignatureIdentity.key(Radio.WIFI, "70-c9-4e-aa-bb-cc")
        )
    }

    @Test
    fun payloadFingerprint_companyAndFirstBytes() {
        val fp = SignatureIdentity.payloadFingerprint(0x09C8, byteArrayOf(0x54, 0x4E, 0x07))
        assertEquals("0x09c8:544e07", fp)
        assertEquals("0x004c", SignatureIdentity.payloadFingerprint(0x004C, null))
        assertNull(SignatureIdentity.payloadFingerprint(null, null))
    }

    @Test
    fun companyIds_roundTrip() {
        val csv = SignatureIdentity.companyIdsCsv(listOf(0x09C8, 0x004C, 0x09C8))
        assertEquals("0x004c,0x09c8", csv)
        assertEquals(listOf(0x004C, 0x09C8), SignatureIdentity.parseCompanyIds(csv))
    }
}

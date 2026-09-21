package ch.swhizkid.tailtrace.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraitMatcherTest {

    private fun traits(
        oui: String? = "00:25:DF",
        name: String? = "TN7201",
        companies: List<Int> = listOf(0x09C8),
        uuids: List<String> = listOf("0000fd5a-0000-1000-8000-00805f9b34fb"),
        ssid: String? = "Flock-1A2B"
    ) = SignatureTraits(
        radio = Radio.BLE,
        address = "00:25:DF:01:02:03",
        oui = oui,
        advertisedName = name,
        companyIds = companies,
        serviceUuids = uuids,
        ssid = ssid,
        payloadFingerprint = "0x09c8:544e"
    )

    private fun rule(kind: TraitKind, value: String, entityId: Long = 7) = TraitRule(
        id = 1,
        traitKind = kind,
        traitValue = TraitMatcher.normalizeValue(kind, value),
        entityId = entityId,
        createdMs = 0
    )

    @Test
    fun matches_oui_company_uuid_name_ssid() {
        val t = traits()
        assertTrue(TraitMatcher.matches(rule(TraitKind.OUI, "00:25:df"), t))
        assertTrue(TraitMatcher.matches(rule(TraitKind.COMPANY_ID, "09c8"), t))
        assertTrue(TraitMatcher.matches(rule(TraitKind.COMPANY_ID, "0x09C8"), t))
        assertTrue(TraitMatcher.matches(rule(TraitKind.SERVICE_UUID, "0000FD5A-0000-1000-8000-00805F9B34FB"), t))
        assertTrue(TraitMatcher.matches(rule(TraitKind.NAME_SUBSTRING, "tn72"), t))
        assertTrue(TraitMatcher.matches(rule(TraitKind.SSID_SUBSTRING, "flock"), t))
        assertFalse(TraitMatcher.matches(rule(TraitKind.OUI, "70:C9:4E"), t))
        assertFalse(TraitMatcher.matches(rule(TraitKind.COMPANY_ID, "0x004c"), t))
    }

    @Test
    fun firstMatch_prefersEarlierRule() {
        val rules = listOf(
            rule(TraitKind.OUI, "00:25:DF", entityId = 1),
            rule(TraitKind.COMPANY_ID, "0x09c8", entityId = 2)
        )
        assertEquals(1L, TraitMatcher.firstMatch(rules, traits())?.entityId)
    }

    @Test
    fun firstMatch_skipsNonMatching() {
        val rules = listOf(
            rule(TraitKind.OUI, "70:C9:4E", entityId = 1),
            rule(TraitKind.COMPANY_ID, "0x09c8", entityId = 2)
        )
        assertEquals(2L, TraitMatcher.firstMatch(rules, traits())?.entityId)
    }

    @Test
    fun availableTraits_listsReusableFields() {
        val pairs = TraitMatcher.availableTraits(traits())
        assertTrue(pairs.any { it.first == TraitKind.OUI && it.second == "00:25:DF" })
        assertTrue(pairs.any { it.first == TraitKind.COMPANY_ID && it.second == "0x09c8" })
        assertTrue(pairs.any { it.first == TraitKind.NAME_SUBSTRING && it.second == "TN7201" })
    }
}

package ch.swhizkid.tailtrace.data.catalog

/**
 * Matches a stored [TraitRule] against extracted radio traits.
 *
 * A later-identified signature (OUI, company id, UUID, name, SSID) can be
 * bound to an entity; every unassigned row that carries that trait inherits it.
 */
object TraitMatcher {

    fun normalizeValue(kind: TraitKind, raw: String): String {
        val v = raw.trim()
        return when (kind) {
            TraitKind.OUI -> SignatureIdentity.normalizeAddress(v)
            TraitKind.COMPANY_ID -> {
                val n = v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    ?: v.toIntOrNull()
                if (n != null) SignatureIdentity.formatCompanyId(n) else v.lowercase()
            }
            TraitKind.SERVICE_UUID -> v.lowercase()
            TraitKind.NAME_SUBSTRING, TraitKind.SSID_SUBSTRING -> v.lowercase()
        }
    }

    fun matches(rule: TraitRule, traits: SignatureTraits): Boolean {
        val expected = normalizeValue(rule.traitKind, rule.traitValue)
        return when (rule.traitKind) {
            TraitKind.OUI -> traits.oui?.equals(expected, ignoreCase = true) == true
            TraitKind.COMPANY_ID -> traits.companyIds.any {
                SignatureIdentity.formatCompanyId(it) == expected
            }
            TraitKind.SERVICE_UUID -> traits.serviceUuids.any { it.equals(expected, ignoreCase = true) }
            TraitKind.NAME_SUBSTRING ->
                !traits.advertisedName.isNullOrBlank() &&
                    traits.advertisedName.lowercase().contains(expected)
            TraitKind.SSID_SUBSTRING ->
                !traits.ssid.isNullOrBlank() &&
                    traits.ssid.lowercase().contains(expected)
        }
    }

    /** First matching rule wins. Rules are applied in caller-defined order. */
    fun firstMatch(rules: List<TraitRule>, traits: SignatureTraits): TraitRule? =
        rules.firstOrNull { matches(it, traits) }

    fun availableTraits(traits: SignatureTraits): List<Pair<TraitKind, String>> = buildList {
        traits.oui?.let { add(TraitKind.OUI to it) }
        traits.companyIds.forEach { add(TraitKind.COMPANY_ID to SignatureIdentity.formatCompanyId(it)) }
        traits.serviceUuids.forEach { add(TraitKind.SERVICE_UUID to it) }
        traits.advertisedName?.let { add(TraitKind.NAME_SUBSTRING to it) }
        traits.ssid?.let { add(TraitKind.SSID_SUBSTRING to it) }
    }
}

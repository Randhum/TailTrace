package ch.swhizkid.tailtrace.data.catalog

/**
 * Stable identity for a heard radio packet.
 *
 * Instance key is radio + address so each MAC/BSSID is its own row (rotating
 * BLE addresses become many rows — that is intentional). Trait rules later
 * group those rows onto one [Entity] without rewriting history.
 */
object SignatureIdentity {

    fun normalizeAddress(raw: String): String =
        raw.trim().uppercase().replace('-', ':')

    fun ouiOf(address: String): String? {
        val parts = normalizeAddress(address).split(':')
        if (parts.size < 3) return null
        if (parts.take(3).any { it.length != 2 }) return null
        return parts.take(3).joinToString(":")
    }

    fun key(radio: Radio, address: String): String =
        "${radio.name.lowercase()}|${normalizeAddress(address)}"

    fun payloadFingerprint(companyId: Int?, payload: ByteArray?): String? {
        if (companyId == null && (payload == null || payload.isEmpty())) return null
        val cid = companyId?.let { formatCompanyId(it) } ?: "none"
        val bytes = payload?.take(8)?.joinToString("") { b ->
            "%02x".format(b.toInt() and 0xFF)
        } ?: ""
        return if (bytes.isEmpty()) cid else "$cid:$bytes"
    }

    fun formatCompanyId(id: Int): String = "0x%04x".format(id and 0xFFFF)

    fun companyIdsCsv(ids: List<Int>): String? =
        ids.distinct().sorted().joinToString(",") { formatCompanyId(it) }.ifBlank { null }

    fun uuidCsv(uuids: List<String>): String? =
        uuids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .distinct().sorted().joinToString(",").ifBlank { null }

    fun parseCompanyIds(csv: String?): List<Int> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',').mapNotNull { token ->
            val hex = token.trim().removePrefix("0x").removePrefix("0X")
            hex.toIntOrNull(16)
        }
    }

    fun parseCsv(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun traitsOf(obs: RadioObservation): SignatureTraits {
        val address = normalizeAddress(obs.address)
        return SignatureTraits(
            radio = obs.radio,
            address = address,
            oui = ouiOf(address),
            advertisedName = obs.advertisedName?.trim()?.ifBlank { null },
            companyIds = obs.companyIds.distinct(),
            serviceUuids = obs.serviceUuids.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            ssid = obs.ssid?.trim()?.ifBlank { null },
            payloadFingerprint = obs.fingerprint
                ?: payloadFingerprint(obs.companyIds.firstOrNull(), obs.payload)
        )
    }
}

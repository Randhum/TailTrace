package ch.swhizkid.tailtrace.data.catalog

/** Radio a signature was heard on. BLE advertisements or WiFi scan results. */
enum class Radio { BLE, WIFI }

/** How a user-defined rule matches future (and existing unassigned) signatures. */
enum class TraitKind {
    OUI,
    COMPANY_ID,
    SERVICE_UUID,
    NAME_SUBSTRING,
    SSID_SUBSTRING
}

data class RadioObservation(
    val radio: Radio,
    val address: String,
    val advertisedName: String? = null,
    val companyIds: List<Int> = emptyList(),
    val serviceUuids: List<String> = emptyList(),
    val ssid: String? = null,
    val payload: ByteArray? = null,
    /** Pre-computed fingerprint (e.g. WiFi beacon-IE hash); overrides the BLE payload derivation. */
    val fingerprint: String? = null,
    val rssi: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val catalogHint: String? = null,
    val score: Int = 0,
    val seenMs: Long = System.currentTimeMillis()
)

/** Trait slice used for matching without a database row. */
data class SignatureTraits(
    val radio: Radio,
    val address: String,
    val oui: String?,
    val advertisedName: String?,
    val companyIds: List<Int>,
    val serviceUuids: List<String>,
    val ssid: String?,
    val payloadFingerprint: String?
)

data class Entity(
    val id: Long,
    val name: String,
    val kind: String,
    val notes: String?,
    val createdMs: Long
)

data class TraitRule(
    val id: Long,
    val traitKind: TraitKind,
    val traitValue: String,
    val entityId: Long,
    val createdMs: Long
)

data class SignatureRow(
    val id: Long,
    val signatureKey: String,
    val radio: Radio,
    val address: String,
    val oui: String?,
    val advertisedName: String?,
    val companyIds: String?,
    val serviceUuids: String?,
    val ssid: String?,
    val payloadFingerprint: String?,
    val catalogHint: String?,
    val entityId: Long?,
    val entityName: String?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val hitCount: Long,
    val lastRssi: Int?,
    val lastLat: Double?,
    val lastLon: Double?,
    val lastScore: Int
) {
    fun traits(): SignatureTraits = SignatureTraits(
        radio = radio,
        address = address,
        oui = oui,
        advertisedName = advertisedName,
        companyIds = SignatureIdentity.parseCompanyIds(companyIds),
        serviceUuids = SignatureIdentity.parseCsv(serviceUuids),
        ssid = ssid,
        payloadFingerprint = payloadFingerprint
    )
}

data class SightingRow(
    val id: Long,
    val signatureId: Long,
    val seenMs: Long,
    val rssi: Int?,
    val lat: Double?,
    val lon: Double?,
    val score: Int
)

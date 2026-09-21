package ch.swhizkid.tailtrace.data.targets

/**
 * BLE OUIs that are meaningful in Switzerland.
 *
 * Axon bodycams (some cantonal pilots) plus [VendorOuis] (Axis, Hikvision,
 * Dahua, Mobotix, …). Upstream Flock Safety / Espressif supply-chain
 * prefixes were dropped — they are US infrastructure and false-positive
 * on everyday ESP32 devices here.
 */
object BleOuis {

    /** Axon body cameras / dash cams (high-confidence if present). */
    const val AXON = "00:25:df"

    val ALL: Set<String> = setOf(AXON) + VendorOuis.ALL

    fun matches(mac: String): Boolean {
        val lower = mac.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in ALL
    }

    fun isAxon(mac: String): Boolean = mac.lowercase().startsWith(AXON)
}

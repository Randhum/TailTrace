package ch.swhizkid.tailtrace.data.targets

/**
 * BLE names and WiFi SSIDs that show up on Swiss / European camera gear
 * and public-authority radios. Flock Safety name formats are not used here.
 *
 * Do not match `SBB-Free` / `Swisscom` — those are public access, not cameras.
 */
object Patterns {

    val BLE_NAME_PATTERNS: List<String> = listOf(
        "HIKVISION",
        "Hikvision",
        "HIK-",
        "Axis-",
        "AXIS",
        "Dahua",
        "DH-",
        "Mobotix",
        "MOBOTIX",
        "IPC-",
        "IPC_",
        "Axon",
        "WatchGuard"
    )

    val SSID_GENERIC: List<String> = listOf(
        "hikvision", "hik-", "axis-", "dahua", "dh-",
        "mobotix", "ipc-", "ipc_",
        "jenoptik", "vitronic", "multanova",
        "kantonspolizei", "stadtpolizei", "polizei-",
        "securitas"
    )

    /** Axis factory / setup SSIDs: axis-<hex serial>. */
    val SSID_AXIS_REGEX = Regex("^axis-[0-9A-Fa-f]{4,12}$", RegexOption.IGNORE_CASE)

    fun bleNameMatch(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return BLE_NAME_PATTERNS.any { name.contains(it, ignoreCase = false) }
    }

    /** Disabled for CH — upstream used this for Flock Penguin numeric IDs. */
    fun isPenguinNumeric(name: String?): Boolean = false

    fun ssidGenericMatch(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return SSID_GENERIC.any { ssid.contains(it, ignoreCase = true) }
    }

    fun ssidFlockFormat(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return SSID_AXIS_REGEX.matches(ssid)
    }
}

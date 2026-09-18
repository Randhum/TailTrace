package ch.swhizkid.tailtrace.data.targets

/**
 * WiFi BSSID OUIs for camera / access-control vendors seen in Switzerland.
 *
 * Android only exposes BSSID via WifiManager. The US Flock 31-prefix list
 * is omitted (those cameras are not deployed here; several prefixes are
 * generic modules).
 */
object WifiOuis {

    val ALL: Set<String> = VendorOuis.ALL

    fun matches(bssid: String): Boolean {
        val lower = bssid.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in ALL
    }
}

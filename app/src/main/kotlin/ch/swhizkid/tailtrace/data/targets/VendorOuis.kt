package ch.swhizkid.tailtrace.data.targets

/**
 * Camera / municipal-surveillance vendor OUI prefixes used in Switzerland
 * and the rest of Europe. Shared by [BleOuis] and [WifiOuis].
 *
 * Prefixes below were checked against the IEEE MA-L registry snapshot
 * (standards-oui.ieee.org, fetched 2026-09-10) unless noted as already
 * present in upstream OVERWATCH (Axis, Mobotix, Hanwha, FLIR, Verkada,
 * Avigilon, WatchGuard, ShotSpotter).
 *
 * Flock Safety supply-chain OUIs (Espressif, LiteOn, Murata, …) are
 * intentionally absent — they fire on ordinary IoT in CH and those
 * cameras are not deployed here.
 */
object VendorOuis {

    /** OUI prefix → short human label shown in the drill-down. */
    val LABELS: Map<String, String> = mapOf(
        // Axis — very common on CH municipal / transport CCTV
        "00:40:8c" to "Axis camera",
        "ac:cc:8e" to "Axis camera",
        "b8:a4:4f" to "Axis camera",
        "e8:27:25" to "Axis camera",
        // Mobotix (DE) — frequent on CH sites
        "00:03:c5" to "Mobotix camera",
        // Hikvision — IEEE Hangzhou Hikvision Digital Technology
        "0c:75:d2" to "Hikvision camera",
        "c0:56:e3" to "Hikvision camera",
        "bc:ad:28" to "Hikvision camera",
        "64:db:8b" to "Hikvision camera",
        "94:e1:ac" to "Hikvision camera",
        "24:32:ae" to "Hikvision camera",
        "44:47:cc" to "Hikvision camera",
        // Dahua — IEEE Zhejiang Dahua Technology
        "74:c9:29" to "Dahua camera",
        "6c:1c:71" to "Dahua camera",
        "08:ed:ed" to "Dahua camera",
        "e0:2e:fe" to "Dahua camera",
        // Hanwha (ex-Samsung Techwin)
        "44:b4:23" to "Hanwha camera",
        "8c:1d:55" to "Hanwha camera",
        "e4:30:22" to "Hanwha camera",
        // FLIR thermal
        "00:40:7f" to "FLIR device",
        "00:1b:d8" to "FLIR device",
        // Verkada / Avigilon — present in some CH offices
        "e0:a7:00" to "Verkada device",
        "70:1a:d5" to "Avigilon Alta device",
        // Police video (rare in CH; kept because a hit is high-signal)
        "00:1d:96" to "WatchGuard police video",
        "d4:11:d6" to "ShotSpotter sensor"
    )

    /** Product lines that are police-only. ShotSpotter is US acoustic;
     *  WatchGuard body/in-car video has been piloted in Europe. */
    val POLICE_EXCLUSIVE: Set<String> = setOf(
        "00:1d:96"
    )

    val ALL: Set<String> = LABELS.keys

    fun label(mac: String): String? {
        val lower = mac.lowercase()
        if (lower.length < 8) return null
        return LABELS[lower.substring(0, 8)]
    }

    fun isPoliceExclusive(mac: String): Boolean {
        val lower = mac.lowercase()
        if (lower.length < 8) return false
        return lower.substring(0, 8) in POLICE_EXCLUSIVE
    }
}

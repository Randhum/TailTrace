package ch.swhizkid.tailtrace.fusion

import kotlin.math.roundToInt

/**
 * Confidence scoring — port of flock-detection's algorithm with weights from the OVERWATCH plan.
 *
 * One [BleObservation] (a single ScanResult) → one score. Multi-method bonus and RSSI bonuses
 * apply within a single observation. Cross-source corroboration is handled at the [DetectionStore]
 * level (multiple sources hitting the same area push the global max upward).
 */
object ConfidenceEngine {

    // Single-method base weights (BLE)
    const val W_BLE_OUI = 40
    const val W_BLE_OUI_AXON = 80
    // Police-exclusive vendor OUIs (WatchGuard, ShotSpotter) — same rationale as
    // Axon: these prefixes appear on nothing consumer, so a hit is ORANGE-grade
    // on its own.
    const val W_BLE_OUI_POLICE = 75
    const val W_BLE_NAME = 45
    const val W_BLE_NAME_PENGUIN_NUMERIC = 15
    const val W_BLE_MFG_XUNTONG = 60
    const val W_BLE_TN_SERIAL_BONUS = 20  // added on top of mfg
    const val W_BLE_RAVEN_UUID = 70
    const val W_BLE_RAVEN_UUID_MULTI = 90 // 3+ UUIDs

    // Single-method base weights (WiFi — wired in Phase 2)
    const val W_WIFI_OUI = 40
    const val W_WIFI_OUI_POLICE = 75  // WatchGuard 4RE cruiser APs, ShotSpotter backhaul
    const val W_WIFI_SSID_GENERIC = 50
    const val W_WIFI_SSID_FLOCK_FMT = 65  // reused for Axis factory SSID format

    // Map (Phase 3)
    const val W_DEFLOCK_NEAR = 60   // <= 200m
    const val W_DEFLOCK_VERY_NEAR = 85 // <= 50m

    // Waze (live POLICE alerts via the OpenWeb Ninja hosted feed)
    const val W_WAZE_POLICE = 55

    // Bonuses
    const val B_MULTI_METHOD = 20
    const val B_STRONG_RSSI = 10   // > -50 dBm
    const val B_STATIONARY = 15    // RSSI rise-peak-fall

    // MIC channel — smart-home/voice-assistant detection. Capped so a Ring or
    // Echo cluster can't push the global tier above ORANGE; RED stays reserved
    // for ALPR/Axon-grade evidence.
    const val MIC_SCORE_CAP = 84
    const val W_MIC_OUI = 30
    const val W_MIC_NAME = 45
    const val W_MIC_MFG = 30
    const val W_MIC_AVS_UUID = 50
    const val W_MIC_SSID = 45
    const val B_MIC_MULTI = 10
    const val B_MIC_STATIONARY = 8
    const val B_MIC_STRONG_RSSI = 5

    /** What we observed about one BLE device on a single scan callback. */
    data class BleObservation(
        val mac: String,
        val rssi: Int,
        val deviceName: String?,
        val advertisedUuids: List<java.util.UUID>?,
        val manufacturerCompanyId: Int?,
        val manufacturerPayload: ByteArray?,
        val isStationary: Boolean = false
    )

    /** What we observed about one WiFi AP on a single scan result. */
    data class WifiObservation(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val isStationary: Boolean = false
    )

    /** An OSM Switzerland map hit (radar, section control, public CCTV, ANPR). */
    data class DeflockObservation(
        val osmId: Long,
        val distanceMeters: Float,
        val operator: String?,
        val manufacturer: String?,
        val kind: ch.swhizkid.tailtrace.data.targets.OsmSurveillance.Kind =
            ch.swhizkid.tailtrace.data.targets.OsmSurveillance.Kind.PUBLIC_CCTV
    )

    /** An aircraft seen overhead by [ch.swhizkid.tailtrace.scan.AircraftScanner]. */
    data class AircraftObservation(
        val icaoHex: String,
        val distanceMeters: Float,
        val altitudeFt: Int?,
        val isKnownLawEnforcement: Boolean,
        val isLoitering: Boolean,
        val isLadd: Boolean,
        val owner: String?,
        val registration: String?,
        val aircraftType: String?,
        val callsign: String?
    )

    /** A Waze POLICE alert observed within proximity + freshness thresholds. */
    data class WazeObservation(
        val uuid: String,
        val distanceMeters: Float,
        val ageMs: Long,
        val confidence: Int,   // raw 0-5
        val reliability: Int,  // raw 0-10
        val subtype: String?
    )

    data class Scored(
        val score: Int,
        val methods: String,
        val label: String,
        /** True if the BLE OUI specifically matched Axon (drives the "Axon body cam" labeling). */
        val isAxon: Boolean
    )

    fun scoreBle(obs: BleObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0
        var ouiHit = false
        var nameHit = false
        var mfgHit = false
        var ravenHit = false
        var isAxon = false

        // OUI prefix
        if (ch.swhizkid.tailtrace.data.targets.BleOuis.isAxon(obs.mac)) {
            score += W_BLE_OUI_AXON
            methods.append("axon_oui ")
            ouiHit = true; isAxon = true
        } else if (ch.swhizkid.tailtrace.data.targets.VendorOuis.isPoliceExclusive(obs.mac)) {
            score += W_BLE_OUI_POLICE
            methods.append("police_oui ")
            ouiHit = true
        } else if (ch.swhizkid.tailtrace.data.targets.BleOuis.matches(obs.mac)) {
            score += W_BLE_OUI
            methods.append("oui ")
            ouiHit = true
        }
        if (ouiHit) methodCount++

        // Device name patterns
        if (ch.swhizkid.tailtrace.data.targets.Patterns.bleNameMatch(obs.deviceName)) {
            score += W_BLE_NAME
            methods.append("name ")
            nameHit = true
        } else if (ch.swhizkid.tailtrace.data.targets.Patterns.isPenguinNumeric(obs.deviceName)) {
            score += W_BLE_NAME_PENGUIN_NUMERIC
            methods.append("penguin_num ")
            nameHit = true
        }
        if (nameHit) methodCount++

        // Manufacturer-data signature
        if (obs.manufacturerCompanyId == ch.swhizkid.tailtrace.data.targets.Manufacturers.XUNTONG_COMPANY_ID) {
            score += W_BLE_MFG_XUNTONG
            methods.append("mfg_0x09C8 ")
            mfgHit = true
            if (ch.swhizkid.tailtrace.data.targets.Manufacturers.hasTnSerial(obs.manufacturerPayload)) {
                score += W_BLE_TN_SERIAL_BONUS
                methods.append("tn_serial ")
            }
        }
        if (mfgHit) methodCount++

        // Raven service UUIDs
        val ravenCount = ch.swhizkid.tailtrace.data.targets.RavenUuids.countMatches(obs.advertisedUuids)
        if (ravenCount > 0) {
            if (ravenCount >= 3) {
                score += W_BLE_RAVEN_UUID_MULTI
                methods.append("raven_multi ")
            } else {
                score += W_BLE_RAVEN_UUID
                methods.append("raven_uuid ")
            }
            ravenHit = true
            methodCount++
        }

        // Multi-method corroboration bonus
        if (methodCount >= 2) {
            score += B_MULTI_METHOD
            methods.append("multi ")
        }

        // Strong RSSI (very close)
        if (obs.rssi > -50) {
            score += B_STRONG_RSSI
            methods.append("strong_rssi ")
        }

        // Stationary RSSI trend
        if (obs.isStationary) {
            score += B_STATIONARY
            methods.append("stationary ")
        }

        score = score.coerceAtMost(100)

        val vendor = ch.swhizkid.tailtrace.data.targets.VendorOuis.label(obs.mac)
        val label = when {
            isAxon -> "Axon body cam (${obs.mac})"
            ravenHit -> "Raven gunshot detector (${obs.mac})"
            vendor != null && !obs.deviceName.isNullOrBlank() ->
                "$vendor — ${obs.deviceName} (${obs.mac})"
            vendor != null -> "$vendor (${obs.mac})"
            !obs.deviceName.isNullOrBlank() -> "${obs.deviceName} (${obs.mac})"
            else -> "Surveillance BLE (${obs.mac})"
        }

        return Scored(score, methods.toString().trim(), label, isAxon)
    }

    /**
     * Aircraft falloff, over ground distance. Far wider than the ground
     * sources because an aircraft orbiting 5 km away is still watching you.
     */
    private val AIRCRAFT_FALLOFF = arrayOf(
        0f to 88f, 1000f to 80f, 3000f to 68f, 6000f to 55f, 10000f to 42f, 15000f to 30f
    )

    /** An unregistered aircraft is judged on behaviour alone, so it is capped
     *  below the "certain" band — circling could still be news or survey work. */
    const val AIRCRAFT_UNKNOWN_CAP = 69

    private fun falloff(distanceMeters: Float, anchors: Array<Pair<Float, Float>>): Float {
        val d = distanceMeters.coerceAtLeast(0f)
        if (d <= anchors.first().first) return anchors.first().second
        for (i in 0 until anchors.size - 1) {
            val (d0, s0) = anchors[i]
            val (d1, s1) = anchors[i + 1]
            if (d <= d1) return s0 + (d - d0) / (d1 - d0) * (s1 - s0)
        }
        return anchors.last().second
    }

    fun scoreAircraft(obs: AircraftObservation): Scored {
        var score = falloff(obs.distanceMeters, AIRCRAFT_FALLOFF)
        val tags = StringBuilder("aircraft ")

        val alt = obs.altitudeFt
        when {
            alt == null -> Unit
            alt <= 3_000 -> { score += 6f; tags.append("very_low ") }
            alt <= 8_000 -> tags.append("low ")
            alt <= 15_000 -> { score -= 12f; tags.append("mid_alt ") }
            else -> { score -= 30f; tags.append("high_alt ") }
        }

        if (obs.isLoitering) { score += 10f; tags.append("loitering ") }
        if (obs.isKnownLawEnforcement) {
            tags.append("known_le ")
        } else {
            score = minOf(score, AIRCRAFT_UNKNOWN_CAP.toFloat())
            tags.append("unidentified ")
        }
        if (obs.isLadd) { score += 4f; tags.append("ladd ") }

        val final = score.roundToInt().coerceIn(0, 100)
        val who = obs.owner
            ?: obs.registration
            ?: obs.callsign
            ?: "Unidentified aircraft"
        val what = obs.aircraftType?.let { " ($it)" } ?: ""
        val altText = obs.altitudeFt?.let { ", ${it} ft" } ?: ""
        val verb = if (obs.isLoitering) "circling" else "overhead"
        val label = "$who$what $verb @ ${obs.distanceMeters.toInt()}m$altText"
        tags.append("d=${obs.distanceMeters.toInt()}m hex=${obs.icaoHex}")
        return Scored(final, tags.toString().trim(), label, isAxon = false)
    }

    fun scoreWaze(obs: WazeObservation): Scored {
        // Baseline 55 for any POLICE alert within the proximity + age gate the
        // caller already applied. Small crowd-trust nudges for high reliability
        // and high confidence, capped well under the multi-method bonus so a
        // corroborating BLE/WiFi/DeFlock hit still dominates the global tier.
        var score = W_WAZE_POLICE
        if (obs.reliability >= 7) score += 5
        if (obs.confidence >= 4) score += 5
        score = score.coerceAtMost(100)
        val methods = "waze_police rel=${obs.reliability} conf=${obs.confidence}"
        val ageMin = (obs.ageMs / 60_000L).toInt()
        val sub = obs.subtype?.let { " ($it)" } ?: ""
        val label = "Police report$sub @ ${obs.distanceMeters.toInt()}m, ${ageMin}min ago"
        return Scored(score, methods, label, isAxon = false)
    }

    fun scoreDeflock(obs: DeflockObservation): Scored {
        val score = if (obs.distanceMeters <= 50f) W_DEFLOCK_VERY_NEAR else W_DEFLOCK_NEAR
        val rangeTag = if (obs.distanceMeters <= 50f) "osm<=50m" else "osm<=200m"
        val kindLabel = ch.swhizkid.tailtrace.data.targets.OsmSurveillance.label(obs.kind)
        val extra = listOfNotNull(obs.manufacturer, obs.operator).joinToString(" / ")
        val descriptor = if (extra.isBlank()) kindLabel else "$kindLabel — $extra"
        val label = "%s @ %dm (osm:%d)".format(descriptor, obs.distanceMeters.toInt(), obs.osmId)
        return Scored(score, "$rangeTag ${obs.kind.name.lowercase()}", label, isAxon = false)
    }

    /** A BLE mic-bearing-device observation, score-capped at ORANGE. */
    data class MicBleObservation(
        val mac: String,
        val rssi: Int,
        val deviceName: String?,
        val advertisedUuids: List<java.util.UUID>?,
        val manufacturerCompanyId: Int?,
        val isStationary: Boolean
    )

    /** A WiFi mic-bearing-device observation, score-capped at ORANGE. */
    data class MicWifiObservation(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val isStationary: Boolean
    )

    fun scoreMicBle(obs: MicBleObservation): Scored {
        var score = 0
        var methodCount = 0
        val methods = StringBuilder()
        val ouiFamily = ch.swhizkid.tailtrace.data.targets.MicTargets.matchOui(obs.mac)
        if (ouiFamily != null) {
            score += W_MIC_OUI
            methods.append("mic_oui ")
            methodCount++
        }
        val nameMatch = ch.swhizkid.tailtrace.data.targets.MicTargets.matchBleName(obs.deviceName)
        if (nameMatch != null) {
            score += W_MIC_NAME
            methods.append("mic_name ")
            methodCount++
        }
        val mfgFamily = ch.swhizkid.tailtrace.data.targets.MicTargets.matchManufacturer(obs.manufacturerCompanyId)
        if (mfgFamily != null) {
            score += W_MIC_MFG
            methods.append("mic_mfg ")
            methodCount++
        }
        if (ch.swhizkid.tailtrace.data.targets.MicTargets.matchAvsService(obs.advertisedUuids)) {
            score += W_MIC_AVS_UUID
            methods.append("mic_avs ")
            methodCount++
        }
        if (methodCount >= 2) {
            score += B_MIC_MULTI
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_MIC_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_MIC_STATIONARY
            methods.append("stationary ")
        }
        score = score.coerceAtMost(MIC_SCORE_CAP)
        val family = nameMatch?.family ?: ouiFamily ?: mfgFamily
            ?: ch.swhizkid.tailtrace.data.targets.MicTargets.Family.HIDDEN_CAM
        val familyLabel = ch.swhizkid.tailtrace.data.targets.MicTargets.familyLabel(family)
        val nameSuffix = if (!obs.deviceName.isNullOrBlank()) " — ${obs.deviceName}" else ""
        return Scored(score, methods.toString().trim(), "$familyLabel$nameSuffix (${obs.mac})", isAxon = false)
    }

    fun scoreMicWifi(obs: MicWifiObservation): Scored {
        var score = 0
        var methodCount = 0
        val methods = StringBuilder()
        val ouiFamily = ch.swhizkid.tailtrace.data.targets.MicTargets.matchOui(obs.bssid)
        if (ouiFamily != null) {
            score += W_MIC_OUI
            methods.append("mic_oui ")
            methodCount++
        }
        val ssidMatch = ch.swhizkid.tailtrace.data.targets.MicTargets.matchSsid(obs.ssid)
        if (ssidMatch != null) {
            score += W_MIC_SSID
            methods.append("mic_ssid ")
            methodCount++
        }
        if (methodCount >= 2) {
            score += B_MIC_MULTI
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_MIC_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_MIC_STATIONARY
            methods.append("stationary ")
        }
        score = score.coerceAtMost(MIC_SCORE_CAP)
        val family = ssidMatch?.family ?: ouiFamily
            ?: ch.swhizkid.tailtrace.data.targets.MicTargets.Family.HIDDEN_CAM
        val familyLabel = ch.swhizkid.tailtrace.data.targets.MicTargets.familyLabel(family)
        val ssidSuffix = if (!obs.ssid.isNullOrBlank()) " — ${obs.ssid}" else ""
        return Scored(score, methods.toString().trim(), "$familyLabel$ssidSuffix (${obs.bssid})", isAxon = false)
    }

    fun scoreWifi(obs: WifiObservation): Scored {
        var score = 0
        val methods = StringBuilder()
        var methodCount = 0

        val policeOui = ch.swhizkid.tailtrace.data.targets.VendorOuis.isPoliceExclusive(obs.bssid)
        val ouiHit = policeOui || ch.swhizkid.tailtrace.data.targets.WifiOuis.matches(obs.bssid)
        if (policeOui) {
            score += W_WIFI_OUI_POLICE
            methods.append("police_oui ")
            methodCount++
        } else if (ouiHit) {
            score += W_WIFI_OUI
            methods.append("oui ")
            methodCount++
        }

        var ssidHit = false
        if (ch.swhizkid.tailtrace.data.targets.Patterns.ssidFlockFormat(obs.ssid)) {
            score += W_WIFI_SSID_FLOCK_FMT
            methods.append("ssid_camera ")
            ssidHit = true
        } else if (ch.swhizkid.tailtrace.data.targets.Patterns.ssidGenericMatch(obs.ssid)) {
            score += W_WIFI_SSID_GENERIC
            methods.append("ssid_generic ")
            ssidHit = true
        }
        if (ssidHit) methodCount++

        if (methodCount >= 2) {
            score += B_MULTI_METHOD
            methods.append("multi ")
        }
        if (obs.rssi > -50) {
            score += B_STRONG_RSSI
            methods.append("strong_rssi ")
        }
        if (obs.isStationary) {
            score += B_STATIONARY
            methods.append("stationary ")
        }

        score = score.coerceAtMost(100)

        val vendor = ch.swhizkid.tailtrace.data.targets.VendorOuis.label(obs.bssid)
        val label = when {
            vendor != null && !obs.ssid.isNullOrBlank() -> "$vendor — ${obs.ssid} (${obs.bssid})"
            vendor != null -> "$vendor (${obs.bssid})"
            !obs.ssid.isNullOrBlank() -> "${obs.ssid} (${obs.bssid})"
            else -> "Surveillance WiFi (${obs.bssid})"
        }

        return Scored(score, methods.toString().trim(), label, isAxon = false)
    }
}

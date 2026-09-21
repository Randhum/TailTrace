package ch.swhizkid.tailtrace.tracker.scan

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import ch.swhizkid.tailtrace.tracker.model.SeparatedState
import ch.swhizkid.tailtrace.tracker.model.TrackerEcosystem
import ch.swhizkid.tailtrace.tracker.model.TrackerObservation
import ch.swhizkid.tailtrace.tracker.scan.TrackerSignatures as Sig

/**
 * Parses a raw [ScanResult] into a [TrackerObservation] if it matches one of the
 * known tracker wire formats, else null. Pure/stateless.
 *
 * Identity strategy per ecosystem:
 *  - Apple: hash the advertised public-key bytes — stable across a ~24h epoch.
 *  - Google FMDN / Samsung / DULT: MAC is ~24h-static once separated; key on it.
 *  - Tile: MAC is permanently static; key on it directly.
 */
object TrackerParser {

    @SuppressLint("MissingPermission")
    fun parse(result: ScanResult): TrackerObservation? {
        val record = result.scanRecord ?: return null
        val mac = result.device?.address ?: return null
        val rssi = result.rssi

        record.getManufacturerSpecificData(Sig.APPLE_COMPANY_ID)?.let { d ->
            // Require the FULL offline-finding frame. After Android strips the company
            // id, d = [type, len, status, key(22), keyTopBits, hint] (~27 bytes). Short
            // "nearby" frames carry no key — ignore them rather than fabricate a shared
            // identity from the type/status bytes.
            if (d.size >= 25 && (d[0].toInt() and 0xFF) == Sig.APPLE_TYPE_FINDMY) {
                val status = d[2].toInt() and 0xFF
                val separated = if ((status and Sig.APPLE_STATUS_MAINTAINED_BIT) != 0)
                    SeparatedState.NEAR_OWNER else SeparatedState.SEPARATED
                val keyBytes = d.copyOfRange(3, 25)
                return TrackerObservation(
                    stableId = "apple:" + toHex(keyBytes),
                    ecosystem = TrackerEcosystem.APPLE_FIND_MY,
                    mac = mac, rssi = rssi, separated = separated,
                    label = "AirTag / Find My"
                )
            }
        }

        val sd = record.serviceData ?: emptyMap()

        sd[Sig.FMDN_UUID]?.let { d ->
            val frame = if (d.isNotEmpty()) d[0].toInt() and 0xFF else -1
            if (frame == Sig.FMDN_FRAME_NORMAL || frame == Sig.FMDN_FRAME_SEPARATED) {
                val separated = if (frame == Sig.FMDN_FRAME_SEPARATED)
                    SeparatedState.SEPARATED else SeparatedState.NEAR_OWNER
                return TrackerObservation(
                    stableId = "fmdn:$mac",
                    ecosystem = TrackerEcosystem.GOOGLE_FMDN,
                    mac = mac, rssi = rssi, separated = separated,
                    label = "Find My Device tag"
                )
            }
        }

        sd[Sig.SAMSUNG_UUID]?.let { d ->
            val state = if (d.isNotEmpty()) (d[0].toInt() shr 5) and 0x07 else -1
            val separated = when (state) {
                2, 3 -> SeparatedState.SEPARATED
                4, 5, 6 -> SeparatedState.NEAR_OWNER
                else -> SeparatedState.UNKNOWN
            }
            return TrackerObservation(
                stableId = "samsung:$mac",
                ecosystem = TrackerEcosystem.SAMSUNG_SMARTTAG,
                mac = mac, rssi = rssi, separated = separated,
                label = "Galaxy SmartTag"
            )
        }

        (sd[Sig.TILE_ACTIVE_UUID] ?: sd[Sig.TILE_PREACT_UUID] ?: sd[Sig.TILE_LEGACY_UUID])?.let {
            return TrackerObservation(
                stableId = "tile:$mac",
                ecosystem = TrackerEcosystem.TILE,
                mac = mac, rssi = rssi, separated = SeparatedState.UNKNOWN,
                label = "Tile"
            )
        }

        sd[Sig.DULT_UUID]?.let { d ->
            val nearOwner = d.size > 14 && (d[14].toInt() and 0x01) != 0
            val separated = if (nearOwner) SeparatedState.NEAR_OWNER else SeparatedState.SEPARATED
            return TrackerObservation(
                stableId = "dult:$mac",
                ecosystem = TrackerEcosystem.DULT,
                mac = mac, rssi = rssi, separated = separated,
                label = "DULT tracker"
            )
        }

        return null
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

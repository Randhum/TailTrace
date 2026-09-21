package ch.swhizkid.tailtrace.scan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Radio the Finder is currently hunting on. */
enum class FinderBand(val label: String) {
    BLE("BLE / BT"),
    WIFI("WI-FI")
}

/**
 * One emitter the Finder can home in on — a Bluetooth device or a Wi-Fi AP.
 * Band-agnostic on purpose: the proximity meter only needs an identity, a
 * smoothed RSSI and enough radio context to pick a path-loss reference.
 */
data class FinderSignal(
    /** MAC / BSSID, uppercased — the map key. */
    val address: String,
    val band: FinderBand,
    /** BT device name or Wi-Fi SSID; null when not advertised. */
    val name: String?,
    val vendor: String?,
    /** Locally-administered bit set → randomized address, may rotate mid-hunt. */
    val randomized: Boolean,
    /** Display chips: "BLE"/"Classic", device class, Wi-Fi band + channel. */
    val tags: List<String>,
    val rssi: Int,
    val smoothedRssi: Double,
    /** BLE advertised TX Power (dBm radiated), when the advert carries it. */
    val txPower: Int?,
    /** Wi-Fi channel centre frequency — drives the free-space correction. */
    val frequencyMhz: Int?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sightings: Int
)

/**
 * Common plumbing for the Finder's per-band scanners: the signal map, the
 * scanning flag, a human-readable status line, and RSSI smoothing.
 *
 * Session-scoped — signals accumulate from [start] until [stop]; the UI
 * decides how to render stale entries (lastSeenMs is authoritative).
 */
abstract class SignalFinder(protected val context: Context) {

    abstract val band: FinderBand

    /**
     * Exponential-moving-average weight for new samples. BLE lands adverts
     * several times a second, so it can afford heavy smoothing; Wi-Fi gets one
     * sample per scan cycle, so it must weight each one more or the meter
     * would never catch up with the user walking.
     */
    protected open val emaAlpha: Double = 0.35

    protected val _signals = MutableStateFlow<Map<String, FinderSignal>>(emptyMap())
    val signals: StateFlow<Map<String, FinderSignal>> = _signals.asStateFlow()

    protected val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Why the list is empty or degraded, or null when everything is nominal. */
    protected val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * @param rfSilent Emit nothing: no scan requests, no inquiry, no probe
     *   requests — only relay scans the system or other apps already run.
     */
    abstract fun start(rfSilent: Boolean = false): Boolean

    abstract fun stop()

    /**
     * Proximity-meter hunt: park every other radio and stay on this
     * emitter's band. Null = survey mode (list / main) — all radios resume.
     */
    open fun setHunt(signal: FinderSignal?) {}

    protected fun clearSignals() {
        _signals.value = emptyMap()
    }

    /**
     * @param rssi Null means "seen, no strength this time" — keep the last
     *   reading (classic inquiry sometimes omits EXTRA_RSSI). A first sighting
     *   with no RSSI still lands in the list at −100 dBm so it isn't dropped.
     */
    protected fun upsert(
        address: String,
        name: String?,
        vendor: String?,
        tags: List<String>,
        rssi: Int?,
        txPower: Int? = null,
        frequencyMhz: Int? = null
    ) {
        val now = System.currentTimeMillis()
        val key = address.uppercase()
        // Atomic CAS: samples arrive on the BLE callback thread, a broadcast
        // receiver and the poll coroutine at once — a read-modify-write on
        // .value would drop entries.
        _signals.update { current ->
            val old = current[key]
            val newRssi = rssi ?: old?.rssi ?: -100
            current + (
                key to FinderSignal(
                    address = key,
                    band = band,
                    // Fill blanks, never wipe — a later nameless advert must not
                    // erase a name we already learned (same rule as the catalog).
                    name = old?.name ?: name,
                    vendor = old?.vendor ?: vendor,
                    randomized = isRandomized(key),
                    // Union: a dual-mode device picks up "Classic" on top of
                    // "BLE", and a class label learned later gets appended.
                    tags = if (old == null) tags else (old.tags + tags).distinct(),
                    rssi = newRssi,
                    smoothedRssi = if (rssi != null) {
                        old?.smoothedRssi
                            ?.let { it * (1 - emaAlpha) + rssi * emaAlpha }
                            ?: rssi.toDouble()
                    } else {
                        old?.smoothedRssi ?: newRssi.toDouble()
                    },
                    txPower = old?.txPower ?: txPower,
                    frequencyMhz = frequencyMhz ?: old?.frequencyMhz,
                    firstSeenMs = old?.firstSeenMs ?: now,
                    lastSeenMs = now,
                    sightings = (old?.sightings ?: 0) + 1
                )
            )
        }
    }

    /**
     * Locally-administered bit (0x02 of the first octet) → randomized address
     * (BLE resolvable/static random, randomized classic MAC, or a randomized
     * softAP BSSID). Heuristic: IEEE-assigned addresses have the bit clear.
     */
    private fun isRandomized(address: String): Boolean {
        val first = address.substringBefore(':').toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }
}

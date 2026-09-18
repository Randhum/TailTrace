package ch.swhizkid.tailtrace.scan

import ch.swhizkid.tailtrace.util.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide 2.4 GHz radio gate.
 *
 * The adapter cannot run classic BR/EDR inquiry
 * ([android.bluetooth.BluetoothAdapter.startDiscovery]) at the same time as an
 * LE scan or a Wi-Fi start-scan: inquiry occupies the hop set, LE scans are
 * suspended or starved, and 2.4 GHz probe requests collide with both.
 *
 * Rules:
 *  - **BT exclusive**: refcounted by owner id. Held while the Finder hunts
 *    one emitter, or during a classic-inquiry slot. [BleClient]s
 *    (DetectionService) stop their LE scan until the last owner releases.
 *    The Signal Finder *list* does not hold this for the whole visit.
 *  - **Inquiry**: extra flag so Wi-Fi scanners skip `startScan` for the
 *    ~12 s inquiry slot.
 *  - **Wi-Fi owner**: at most one party triggers scans. The Finder takes
 *    this while its Wi-Fi band is open so Detection doesn't double-probe;
 *    Detection still consumes the result broadcasts. A Wi-Fi hunt also
 *    takes BT exclusive (to park BLE) but may still probe because it is
 *    the Wi-Fi owner.
 */
object RadioGate {

    private const val TAG = "RadioGate"

    const val WIFI_DETECTION = "detection"
    const val WIFI_FINDER = "finder"

    const val OWNER_BT_FINDER = "finder-bt"
    const val OWNER_WIFI_FINDER = "finder-wifi"

    interface BleClient {
        /** Stop occupying the LE scanner. Must be safe to call if already idle. */
        fun yieldLeScan()
        /** Resume an LE scan that was running before [yieldLeScan]. */
        fun resumeLeScan()
    }

    private val bleClients = CopyOnWriteArrayList<BleClient>()
    private val lock = Any()
    private val btExclusiveOwners = HashSet<String>()

    @Volatile
    var btExclusive: Boolean = false
        private set

    @Volatile
    var inquiry: Boolean = false
        private set

    @Volatile
    var wifiOwner: String? = null
        private set

    fun registerBle(client: BleClient) {
        bleClients.addIfAbsent(client)
        if (btExclusive) {
            runCatching { client.yieldLeScan() }
        }
    }

    fun unregisterBle(client: BleClient) {
        bleClients.remove(client)
    }

    fun acquireBtExclusive(owner: String) {
        val toYield: List<BleClient>?
        synchronized(lock) {
            if (!btExclusiveOwners.add(owner)) {
                toYield = null
                return@synchronized
            }
            btExclusive = true
            toYield = if (btExclusiveOwners.size == 1) bleClients.toList() else null
        }
        if (toYield != null) {
            AppLog.i(TAG, "BT exclusive +$owner — yielding ${toYield.size} LE client(s)")
            toYield.forEach { runCatching { it.yieldLeScan() } }
        }
    }

    fun releaseBtExclusive(owner: String) {
        val toResume: List<BleClient>?
        synchronized(lock) {
            if (!btExclusiveOwners.remove(owner)) {
                toResume = null
                return@synchronized
            }
            if (btExclusiveOwners.isNotEmpty()) {
                toResume = null
                return@synchronized
            }
            btExclusive = false
            toResume = bleClients.toList()
        }
        if (toResume != null) {
            AppLog.i(TAG, "BT exclusive -$owner released — resuming ${toResume.size} LE client(s)")
            toResume.forEach { runCatching { it.resumeLeScan() } }
        }
    }

    fun setInquiry(active: Boolean) {
        synchronized(lock) {
            if (inquiry == active) return
            inquiry = active
        }
        AppLog.i(TAG, if (active) "inquiry slot" else "inquiry slot done")
    }

    fun acquireWifi(owner: String) {
        synchronized(lock) { wifiOwner = owner }
        AppLog.i(TAG, "Wi-Fi owner=$owner")
    }

    fun releaseWifi(owner: String) {
        synchronized(lock) {
            if (wifiOwner == owner) wifiOwner = null
        }
    }

    /**
     * Whether [who] may call `startScan` / root `cmd wifi start-scan`.
     *
     * Classic inquiry always blocks probes (they collide with the hop set).
     * BT exclusive parks Detection's LE scan — and blocks Wi-Fi **unless**
     * [who] is the current owner. That exception is the Wi-Fi hunt meter:
     * it takes exclusive to pause Bluetooth, but must keep probing.
     */
    fun wifiTriggersAllowed(who: String): Boolean {
        if (inquiry) return false
        val owner = wifiOwner
        if (btExclusive) return owner == who
        return owner == null || owner == who
    }

    /** Test hook — not for production callers. */
    fun resetForTest() {
        synchronized(lock) {
            bleClients.clear()
            btExclusiveOwners.clear()
            btExclusive = false
            inquiry = false
            wifiOwner = null
        }
    }
}

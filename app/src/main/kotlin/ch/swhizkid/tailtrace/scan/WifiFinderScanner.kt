package ch.swhizkid.tailtrace.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import ch.swhizkid.tailtrace.data.targets.VendorOuis
import ch.swhizkid.tailtrace.util.AppLog
import ch.swhizkid.tailtrace.util.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wi-Fi band of the Finder: hunt an access point by its beacon RSSI.
 *
 * The hard constraint is cadence. Unlike BLE (adverts several times a second)
 * a Wi-Fi RSSI sample only arrives per scan cycle, and stock Android throttles
 * foreground apps to 4 scans / 2 min. Two tiers (no root — priv-app only):
 *
 *  1. **throttle disabled** (PrivEnhancer via WRITE_SECURE_SETTINGS, or the
 *     Developer-options toggle) — 10 s cycles through the normal API.
 *  2. **stock** — 30 s cycles to stay under the cap; the meter lags badly and
 *     the UI says so.
 *
 * Location off is handled by NETWORK_SETTINGS (priv-app allowlist), which
 * WifiPermissionsUtil treats as a location-mode bypass — see [WifiScanAccess].
 *
 * RF-silent never calls startScan at all (a scan sprays probe requests on
 * every channel) — it only reads whatever the system already scanned.
 */
class WifiFinderScanner(context: Context) : SignalFinder(context) {

    companion object {
        private const val TAG = "WifiFinderScanner"
        /** How often we re-read the result cache. */
        private const val READ_POLL_MS = 3_000L
        private const val APP_SCAN_GAP_UNTHROTTLED_MS = 10_000L
        private const val APP_SCAN_GAP_THROTTLED_MS = 30_000L
        /** BSSID the framework hands back when it redacts the real one. */
        private const val REDACTED_BSSID = "02:00:00:00:00:00"
    }

    override val band = FinderBand.WIFI

    // One sample per scan cycle: weight new data heavily or the meter would
    // never catch up with someone walking across a room.
    override val emaAlpha = 0.5

    private val mgr: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var scope: CoroutineScope? = null
    private var receiverRegistered = false
    private var resultsCallback: WifiManager.ScanResultsCallback? = null
    private var lastScanTriggerMs = 0L
    @Volatile private var rfSilent = false
    @Volatile private var hunting = false
    @Volatile private var btExclusiveHeld = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                readResults()
            }
        }
    }

    fun hasScanPermission(): Boolean {
        val locOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyOk = ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return nearbyOk || locOk
        }
        return locOk
    }

    val isAvailable: Boolean
        get() = mgr?.isWifiEnabled == true

    override fun start(rfSilent: Boolean): Boolean {
        if (_scanning.value) return true
        if (!hasScanPermission()) {
            _status.value = "Wi-Fi scan permission missing"
            return false
        }
        val m = mgr ?: run {
            _status.value = "No Wi-Fi service"
            return false
        }
        if (!m.isWifiEnabled) {
            _status.value = "Wi-Fi is off — enable it to hunt access points"
            return false
        }
        this.rfSilent = rfSilent
        hunting = false
        dropExclusive()
        clearSignals()
        lastScanTriggerMs = 0L
        RadioGate.acquireWifi(RadioGate.WIFI_FINDER)
        registerListeners(m)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        _scanning.value = true
        s.launch {
            while (isActive) {
                maybeTriggerScan(m, rfSilent)
                readResults()
                delay(READ_POLL_MS)
            }
        }
        AppLog.i(TAG, "wifi finder started (rfSilent=$rfSilent)")
        return true
    }

    override fun stop() {
        if (!_scanning.value && !receiverRegistered && !btExclusiveHeld) return
        _scanning.value = false
        scope?.cancel()
        scope = null
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        resultsCallback?.let { cb ->
            resultsCallback = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    mgr?.unregisterScanResultsCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
        dropExclusive()
        hunting = false
        RadioGate.releaseWifi(RadioGate.WIFI_FINDER)
        AppLog.i(TAG, "wifi finder stopped")
    }

    override fun setHunt(signal: FinderSignal?) {
        if (!_scanning.value) return
        val next = signal != null
        if (next == hunting) return
        hunting = next
        if (rfSilent) {
            _status.value = "RF-silent — cannot take the radio exclusively"
            return
        }
        if (hunting) {
            holdExclusive()
            _status.value = "Hunting Wi-Fi — Bluetooth paused"
            AppLog.i(TAG, "hunt wifi ${signal?.address}")
        } else {
            dropExclusive()
            AppLog.i(TAG, "wifi hunt ended — Bluetooth resumes")
        }
    }

    private fun holdExclusive() {
        if (btExclusiveHeld) return
        RadioGate.acquireBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        btExclusiveHeld = true
    }

    private fun dropExclusive() {
        if (!btExclusiveHeld) return
        RadioGate.releaseBtExclusive(RadioGate.OWNER_WIFI_FINDER)
        btExclusiveHeld = false
    }

    private fun registerListeners(m: WifiManager) {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && resultsCallback == null) {
            val cb = object : WifiManager.ScanResultsCallback() {
                override fun onScanResultsAvailable() = readResults()
            }
            try {
                m.registerScanResultsCallback(ContextCompat.getMainExecutor(context), cb)
                resultsCallback = cb
            } catch (e: Exception) {
                AppLog.w(TAG, "registerScanResultsCallback failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun maybeTriggerScan(m: WifiManager, rfSilent: Boolean) {
        if (rfSilent) {
            _status.value = "RF-silent — no probe requests; updates ride on system scans"
            return
        }
        if (!RadioGate.wifiTriggersAllowed(RadioGate.WIFI_FINDER)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val throttleOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !m.isScanThrottleEnabled
        val gap = if (throttleOff) APP_SCAN_GAP_UNTHROTTLED_MS else APP_SCAN_GAP_THROTTLED_MS
        if (lastScanTriggerMs != 0L && now - lastScanTriggerMs < gap) return
        lastScanTriggerMs = now

        val ok = try {
            m.startScan()
        } catch (e: SecurityException) {
            AppLog.w(TAG, "startScan denied: ${e.message}")
            false
        }
        publishStatus(
            when {
                throttleOff -> null // 10s cycles
                !ok -> "Scan throttled — one update per ~30 s. Disable " +
                    "Developer options → Wi-Fi scan throttling, or grant " +
                    "WRITE_SECURE_SETTINGS via the priv-app allowlist."
                else -> "Stock throttle: one update per ~30 s — the meter lags. " +
                    "Disable Wi-Fi scan throttling for ~10 s updates."
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun readResults() {
        val locOn = SystemState.isLocationEnabled(context)
        val bypass = WifiScanAccess.hasPrivilegedBypass(context)
        if (!locOn && !bypass) {
            publishStatus(
                WifiScanAccess.locationOffHint(context)
                    ?: "Location off — Wi-Fi results withheld"
            )
            return
        }
        val results = try {
            mgr?.scanResults
        } catch (e: SecurityException) {
            AppLog.w(TAG, "scanResults denied: ${e.message}")
            null
        }
        if (results == null) {
            publishStatus("Couldn't read scan results")
            return
        }
        val n = ingestFramework(results)
        publishStatus(
            when {
                n > 0 && !locOn -> "Location off — scanning without Location services"
                n > 0 -> null
                !locOn -> "Location off — no APs in cache yet"
                else -> null // Location on: empty cache is a real answer.
            }
        )
    }

    /** Hunt line wins; keep a useful detail behind it when there is one. */
    private fun publishStatus(detail: String?) {
        _status.value = if (hunting && !rfSilent) {
            listOfNotNull("Hunting Wi-Fi — Bluetooth paused", detail)
                .joinToString(" · ")
        } else {
            detail
        }
    }

    /** @return number of non-redacted APs ingested. */
    private fun ingestFramework(results: List<ScanResult>): Int {
        var n = 0
        for (r in results) {
            val bssid = r.BSSID ?: continue
            if (bssid == REDACTED_BSSID) continue
            n++
            upsert(
                address = bssid,
                name = readSsid(r),
                vendor = VendorOuis.label(bssid),
                tags = wifiTags(r.frequency),
                rssi = r.level,
                frequencyMhz = r.frequency.takeIf { it > 0 }
            )
        }
        return n
    }

    private fun wifiTags(freqMhz: Int): List<String> {
        if (freqMhz <= 0) return emptyList()
        return listOfNotNull(
            WifiBands.bandLabel(freqMhz),
            WifiBands.channelOf(freqMhz)?.let { "ch $it" }
        )
    }

    private fun readSsid(r: ScanResult): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return r.wifiSsid?.toString()?.trim('"')?.ifBlank { null }
        }
        @Suppress("DEPRECATION")
        return r.SSID?.trim('"')?.ifBlank { null }
    }
}

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
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import ch.swhizkid.tailtrace.util.AppLog
import ch.swhizkid.tailtrace.util.SystemState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ch.swhizkid.tailtrace.data.catalog.Radio
import ch.swhizkid.tailtrace.data.catalog.RadioObservation
import ch.swhizkid.tailtrace.data.targets.MicTargets
import ch.swhizkid.tailtrace.data.targets.Patterns
import ch.swhizkid.tailtrace.data.targets.WifiOuis
import ch.swhizkid.tailtrace.fusion.ConfidenceEngine
import ch.swhizkid.tailtrace.fusion.DetectionEvent
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.fusion.DetectionStore
import ch.swhizkid.tailtrace.fusion.RssiTracker
import ch.swhizkid.tailtrace.fusion.SourceHealth

/**
 * WiFi scanner — BSSID OUI + SSID-pattern matching via [WifiManager.getScanResults].
 *
 * Android 11+ throttles foreground apps to 4 scans per 2 minutes. We poll every 35s
 * (≈3.4 scans / 2 min) and rely on the system to deliver SCAN_RESULTS_AVAILABLE_ACTION.
 * If [WifiManager.startScan] returns false (throttled or radio busy) we still consume
 * whatever cached results the next broadcast carries.
 *
 * [ch.swhizkid.tailtrace.root.PrivEnhancer] clears the global scan-throttle
 * toggle via WRITE_SECURE_SETTINGS (priv-app allowlist — no root); we verify
 * via [WifiManager.isScanThrottleEnabled] each cycle (API 30+) and drop to a
 * 10s poll while it stays off.
 *
 * **Location services OFF**: the framework withholds WiFi scan results from
 * apps unless they hold a privileged bypass ([WifiScanAccess] —
 * NETWORK_SETTINGS as /system/priv-app) or RADIO_SCAN_WITHOUT_LOCATION
 * (signature|companion, unreachable). neverForLocation on
 * NEARBY_WIFI_DEVICES does not skip the location-mode check. Without the
 * priv-app permission and with location off, WiFi scanning is not possible —
 * we surface that in the source health line instead of pretending.
 *
 * API 30+ also exposes the raw beacon information elements. We fingerprint the
 * element-ID sequence plus vendor-IE OUIs into the signature catalog — a per-model
 * fingerprint that survives BSSID randomization better than the OUI alone.
 *
 * The flock-you promiscuous-mode addr1 / wildcard-probe trick from the reference repo
 * is **not portable to Android** — userspace can only see results WifiManager surfaces.
 */
class WifiScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val rssi: RssiTracker = RssiTracker(),
    /** When true, also evaluate each scan against MicTargets and submit MIC events. */
    private val micEnabled: () -> Boolean = { false },
    /** Every AP in the scan list, matched or not — TailTrace catalog ingest. */
    var onObservation: ((RadioObservation) -> Unit)? = null,
    /**
     * RF-silent: never call startScan (each scan emits probe requests on every
     * channel). We keep the results receiver registered and consume scans the
     * system or other apps trigger — zero app TX, slower refresh.
     */
    private val rfSilent: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "WifiScanner"
        private const val ALARM_THRESHOLD = 40
        /** Stay under the stock 4-scans / 2 min foreground cap, with headroom
         *  for other apps sharing the same quota. */
        private const val SCAN_INTERVAL_MS = 45_000L
        private const val SCAN_INTERVAL_UNTHROTTLED_MS = 10_000L
        private const val SCAN_BACKOFF_MS = 60_000L
        private const val CACHE_POLL_MS = 15_000L
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var running = false
    private var scanJob: Job? = null
    private var receiverRegistered = false
    private var scanCallbackApi30: WifiManager.ScanResultsCallback? = null
    private var lastStartScanElapsed = 0L
    private var lastStartScanOk = true
    private var lastLoggedApCount = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            AppLog.d(TAG, "SCAN_RESULTS_AVAILABLE updated=$updated")
            handleResults()
        }
    }

    val isAvailable: Boolean
        get() = wifiManager?.isWifiEnabled == true

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

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (running) return true
        if (!hasScanPermission()) {
            AppLog.w(TAG, "WiFi scan permission missing")
            SourceHealth.record(DetectionSource.WIFI, ok = false, message = "Permission missing")
            return false
        }
        val mgr = wifiManager ?: run {
            AppLog.w(TAG, "WifiManager unavailable")
            SourceHealth.record(DetectionSource.WIFI, ok = false, message = "WifiManager unavailable")
            return false
        }
        if (!mgr.isWifiEnabled) {
            AppLog.w(TAG, "WiFi disabled — scanner won't return results")
            SourceHealth.record(
                DetectionSource.WIFI, ok = false,
                message = "WiFi disabled — enable in system settings"
            )
            // We still register the receiver so results arrive when the user enables WiFi.
        } else {
            SourceHealth.record(DetectionSource.WIFI, ok = true, message = "listening")
        }
        AppLog.i(
            TAG,
            "WiFi access: locationOn=${locationEnabled()} " +
                "NETWORK_SETTINGS=${WifiScanAccess.hasPrivilegedBypass(context)}"
        )
        tryDisableThrottle(mgr)
        registerReceiver()
        registerScanCallback(mgr)
        running = true
        handleResults()
        scanJob = scope.launch {
            while (isActive) {
                requestScan(mgr)
                handleResults()
                delay(CACHE_POLL_MS)
            }
        }
        AppLog.i(
            TAG,
            "WiFi scan started (interval=${currentIntervalMs()}ms, throttle=${throttleLabel(mgr)})"
        )
        return true
    }

    fun stop() {
        if (!running) return
        scanJob?.cancel()
        scanJob = null
        unregisterReceiver()
        unregisterScanCallback()
        running = false
        AppLog.i(TAG, "WiFi scan stopped")
    }

    @Suppress("DEPRECATION")
    private fun requestScan(mgr: WifiManager) {
        if (rfSilent()) {
            // Passive: startScan would emit probe requests on every channel.
            // The receiver/callback still consume scans the system or other
            // apps trigger; we just never initiate one ourselves.
            SourceHealth.record(
                DetectionSource.WIFI,
                ok = true,
                message = "RF-silent — reading system scans only"
            )
            return
        }
        if (!RadioGate.wifiTriggersAllowed(RadioGate.WIFI_DETECTION)) {
            SourceHealth.record(
                DetectionSource.WIFI, ok = true,
                message = when {
                    RadioGate.inquiry -> "paused — classic inquiry using the radio"
                    RadioGate.btExclusive -> "paused — Finder using the radio"
                    else -> "paused — Finder driving Wi-Fi scans"
                }
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val minGap = if (lastStartScanOk) currentIntervalMs() else SCAN_BACKOFF_MS
        if (now - lastStartScanElapsed < minGap && lastStartScanElapsed != 0L) {
            return
        }
        try {
            val ok = mgr.startScan()
            lastStartScanElapsed = now
            lastStartScanOk = ok
            if (ok) {
                AppLog.d(TAG, "startScan accepted")
            } else {
                AppLog.w(
                    TAG,
                    "startScan returned false (throttled or radio busy); " +
                        "using cached results. throttle=${throttleLabel(mgr)}"
                )
                SourceHealth.record(
                    DetectionSource.WIFI,
                    ok = true,
                    message = "scan throttled — reading cache"
                )
            }
        } catch (e: SecurityException) {
            lastStartScanOk = false
            AppLog.e(TAG, "SecurityException starting WiFi scan", e)
            SourceHealth.record(DetectionSource.WIFI, ok = false, message = "Permission revoked")
        }
    }

    /** Priv-app with WRITE_SECURE_SETTINGS clears the global throttle — no root needed. */
    private fun tryDisableThrottle(mgr: WifiManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !mgr.isScanThrottleEnabled) {
            AppLog.i(TAG, "WiFi scan throttle already off")
            return
        }
        val written = try {
            AndroidSettings.Global.putInt(
                context.contentResolver,
                "wifi_scan_throttle_enabled",
                0
            )
        } catch (e: SecurityException) {
            AppLog.w(
                TAG,
                "cannot write wifi_scan_throttle_enabled (${e.message}). " +
                    "Need WRITE_SECURE_SETTINGS (priv-app allowlist)."
            )
            false
        }
        if (written) {
            AppLog.i(TAG, "wrote wifi_scan_throttle_enabled=0")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AppLog.i(TAG, "isScanThrottleEnabled=${mgr.isScanThrottleEnabled}")
        }
    }

    private fun throttleLabel(mgr: WifiManager): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (mgr.isScanThrottleEnabled) "on" else "off"
        } else {
            "unknown"
        }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun registerScanCallback(mgr: WifiManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (scanCallbackApi30 != null) return
        val cb = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                handleResults()
            }
        }
        try {
            mgr.registerScanResultsCallback(ContextCompat.getMainExecutor(context), cb)
            scanCallbackApi30 = cb
        } catch (e: Exception) {
            AppLog.w(TAG, "registerScanResultsCallback failed: ${e.message}")
        }
    }

    private fun unregisterScanCallback() {
        val cb = scanCallbackApi30 ?: return
        scanCallbackApi30 = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            wifiManager?.unregisterScanResultsCallback(cb)
        } catch (_: Exception) { }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already gone
        }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun handleResults() {
        val mgr = wifiManager ?: return
        val results: List<ScanResult> = try {
            mgr.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "SecurityException reading scanResults", e)
            WifiScanAccess.locationOffHint(context)?.let { hint ->
                SourceHealth.record(DetectionSource.WIFI, ok = false, message = hint)
            }
            return
        }

        if (results.size != lastLoggedApCount) {
            AppLog.i(TAG, "WiFi cache: ${results.size} APs")
            lastLoggedApCount = results.size
        }
        if (results.isEmpty()) {
            val locOff = !locationEnabled()
            val bypass = WifiScanAccess.hasPrivilegedBypass(context)
            if (locOff && !bypass) {
                // Framework withheld everything — only NETWORK_SETTINGS
                // (priv-app allowlist) or Location on can fix this.
                SourceHealth.record(
                    DetectionSource.WIFI,
                    ok = false,
                    message = WifiScanAccess.locationOffHint(context)
                        ?: "Location off — WiFi results withheld"
                )
                return
            }
            SourceHealth.record(
                DetectionSource.WIFI,
                ok = lastStartScanOk,
                message = when {
                    lastStartScanOk -> "0 APs in cache"
                    else -> "throttled, cache empty"
                }
            )
        } else if (lastStartScanOk) {
            SourceHealth.record(
                DetectionSource.WIFI, ok = true,
                message = "${results.size} APs"
            )
        }

        for (r in results) {
            val bssid = r.BSSID ?: continue
            processAp(bssid, readSsid(r), r.level, ieFingerprint(r))
        }
    }

    private fun locationEnabled(): Boolean = SystemState.isLocationEnabled(context)

    /** Per-AP pipeline. */
    private fun processAp(bssid: String, ssid: String?, level: Int, fingerprint: String?) {
        val isSurveillance = WifiOuis.matches(bssid) ||
            Patterns.ssidGenericMatch(ssid) ||
            Patterns.ssidFlockFormat(ssid)
        val isMic = micEnabled() && MicTargets.couldBeMicWifi(bssid, ssid)

        rssi.update(bssid, level)
        val stationary = rssi.isStationary(bssid)
        var catalogHint: String? = null
        var catalogScore = 0

        if (isSurveillance) {
            val obs = ConfidenceEngine.WifiObservation(
                bssid = bssid, ssid = ssid, rssi = level, isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreWifi(obs)
            catalogHint = scored.label
            catalogScore = scored.score
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.WIFI,
                        key = bssid,
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = level
                    )
                )
            }
        }
        if (isMic) {
            val obs = ConfidenceEngine.MicWifiObservation(
                bssid = bssid, ssid = ssid, rssi = level, isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreMicWifi(obs)
            if (catalogHint == null || scored.score > catalogScore) {
                catalogHint = scored.label
                catalogScore = scored.score
            }
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.COMMERCIAL,
                        key = "mic:$bssid",
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = level
                    )
                )
            }
        }

        onObservation?.invoke(
            RadioObservation(
                radio = Radio.WIFI,
                address = bssid,
                ssid = ssid,
                rssi = level,
                fingerprint = fingerprint,
                catalogHint = catalogHint,
                score = catalogScore
            )
        )
    }

    /**
     * Poll faster when the system scan throttle is verified off (PrivEnhancer
     * via WRITE_SECURE_SETTINGS, or the user's Developer-options toggle).
     */
    private fun currentIntervalMs(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            wifiManager?.isScanThrottleEnabled == false
        ) {
            return SCAN_INTERVAL_UNTHROTTLED_MS
        }
        return SCAN_INTERVAL_MS
    }

    /**
     * Compact beacon fingerprint: ordered element-ID list plus vendor-IE OUIs
     * (EID 221). Two APs of the same model/firmware emit near-identical IE
     * layouts, so this groups randomized BSSIDs in the catalog. API 30+ only.
     */
    private fun ieFingerprint(r: ScanResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val elements = try {
            r.informationElements
        } catch (_: Exception) {
            return null
        }
        if (elements.isNullOrEmpty()) return null
        val ids = StringBuilder()
        val vendorOuis = sortedSetOf<String>()
        for (ie in elements) {
            if (ids.isNotEmpty()) ids.append(',')
            ids.append(ie.id)
            if (ie.idExt != 0) ids.append('.').append(ie.idExt)
            if (ie.id == 221) {
                // Vendor-specific IE: first 3 bytes are the vendor OUI.
                val buf = ie.bytes // read-only ByteBuffer
                if (buf.remaining() >= 3) {
                    val b = ByteArray(3)
                    buf.duplicate().get(b)
                    vendorOuis.add(b.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                }
            }
        }
        val vendors = if (vendorOuis.isEmpty()) "" else "|v:" + vendorOuis.joinToString(",")
        return "ie:$ids$vendors"
    }

    private fun readSsid(r: ScanResult): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val raw = r.wifiSsid?.toString() ?: return null
            return raw.trim('"').ifBlank { null }
        }
        @Suppress("DEPRECATION")
        val raw = r.SSID ?: return null
        return raw.trim('"').ifBlank { null }
    }
}

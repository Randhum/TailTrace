package ch.swhizkid.tailtrace.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ch.swhizkid.tailtrace.util.AppLog
import ch.swhizkid.tailtrace.data.catalog.Radio
import ch.swhizkid.tailtrace.data.catalog.RadioObservation
import ch.swhizkid.tailtrace.data.targets.BleOuis
import ch.swhizkid.tailtrace.tracker.model.TrackerObservation
import ch.swhizkid.tailtrace.tracker.scan.TrackerParser
import ch.swhizkid.tailtrace.data.targets.MicTargets
import ch.swhizkid.tailtrace.data.targets.Patterns
import ch.swhizkid.tailtrace.data.targets.RavenUuids
import ch.swhizkid.tailtrace.fusion.ConfidenceEngine
import ch.swhizkid.tailtrace.fusion.DetectionEvent
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.fusion.DetectionStore
import ch.swhizkid.tailtrace.fusion.RssiTracker
import ch.swhizkid.tailtrace.fusion.SourceHealth

/**
 * BLE scanner — ported from AxonCadabra (scan side only; no advertise/fuzz).
 *
 * Strategy:
 *  - Run a low-latency unfiltered scan (cheap on modern Android).
 *  - In the callback, first reject anything that doesn't look like a candidate
 *    (no OUI hit, no name hit, no Raven UUID, no XUNTONG mfg) — saves CPU.
 *  - For candidates, build a [ConfidenceEngine.BleObservation] and score it.
 *  - Push to [DetectionStore] if score crosses ALARM_THRESHOLD (40).
 *
 * Permissions: caller must hold BLUETOOTH_SCAN (API 31+) or BLUETOOTH+LOCATION (legacy).
 */
class BleScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val rssi: RssiTracker = RssiTracker(),
    /** When true, also evaluate each scan against MicTargets and submit MIC events. */
    private val micEnabled: () -> Boolean = { false },
    /** Every advertisement, matched or not — TailTrace catalog ingest. */
    var onObservation: ((RadioObservation) -> Unit)? = null,
    /** Parsed personal item-trackers (AirTag / Tile / SmartTag / FMDN / DULT). */
    var onTracker: ((TrackerObservation) -> Unit)? = null,
    /**
     * RF-silent: use SCAN_MODE_OPPORTUNISTIC — we register a listener but never
     * own scan radio time, so the stack sends no SCAN_REQ on our behalf. We only
     * relay results of scans the system/other apps run. Zero TX, sparse results.
     * Evaluated at scan (re)start, like the other source toggles.
     */
    private val rfSilent: () -> Boolean = { false }
) : RadioGate.BleClient {

    companion object {
        private const val TAG = "BleScanner"
        private const val ALARM_THRESHOLD = 40
        private const val HEARTBEAT_MS = 15_000L
        private const val STUCK_RESTART_MS = 20_000L
        private const val PERIODIC_RESTART_MS = 8 * 60_000L
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var leScanner: BluetoothLeScanner? = null
    private var running = false
    @Volatile private var yielded = false
    private var useFallbackSettings = false
    private var watchdog: Job? = null
    private val adsInWindow = AtomicInteger(0)
    private val uniqueMacs = HashSet<String>()

    private fun scanMode(): Int =
        if (rfSilent()) ScanSettings.SCAN_MODE_OPPORTUNISTIC
        else ScanSettings.SCAN_MODE_LOW_LATENCY

    /** Aggressive: extended + legacy ads. Some stacks reject this combo. */
    private fun primarySettings(): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(scanMode())
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            b.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // false = legacy AND extended advertising (AirTags etc. on coded PHY).
            b.setLegacy(false)
            b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        return b.build()
    }

    /** Stock defaults besides scan mode — most compatible with OEM scanners. */
    private fun fallbackSettings(): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(scanMode())
            .setReportDelay(0)
            .build()

    private fun currentSettings(): ScanSettings =
        if (useFallbackSettings) fallbackSettings() else primarySettings()

    /**
     * A single match-all filter (no criteria set → passes every advertisement).
     * Passing a NON-EMPTY filter list flips the AOSP scan client to "filtered",
     * which — per ScanManager.requiresScreenOn (`!opportunistic && !isFiltered`)
     * — exempts the scan from being SUSPENDED while the screen is off.
     * Location-off no longer matters: BLUETOOTH_SCAN is declared with
     * neverForLocation, so both the suspension and the result-delivery gates
     * treat us as location-disavowed and keep results flowing with Location
     * services off (Android 12+; on API ≤30 the legacy location gate remains).
     * The disavowal denylist (Eddystone/iBeacon frames) is empty on degoogled
     * LineageOS (no GMS to push it) — see AndroidManifest comment.
     */
    private val matchAllFilters: List<ScanFilter> = listOf(ScanFilter.Builder().build())

    /** True if the device supports BLE and the adapter is on. */
    val isAvailable: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (running) return true
        if (!hasScanPermission()) {
            AppLog.w(TAG, "BLE scan permission missing")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission missing")
            return false
        }
        val adapter = bluetoothAdapter ?: run {
            AppLog.w(TAG, "BLE not supported")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "BLE not supported")
            return false
        }
        if (!adapter.isEnabled) {
            AppLog.w(TAG, "Bluetooth disabled")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Bluetooth disabled")
            return false
        }
        if (!locationEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AppLog.i(TAG, "Location off — fine, BLUETOOTH_SCAN is neverForLocation")
            } else {
                // Pre-12 there is no disavowal mechanism; the legacy stack
                // withholds results while Location services are off.
                AppLog.w(TAG, "Location off on API ${Build.VERSION.SDK_INT} (<31) — results withheld")
            }
        }
        leScanner = adapter.bluetoothLeScanner ?: run {
            AppLog.w(TAG, "BLE scanner unavailable")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "BLE scanner unavailable")
            return false
        }
        useFallbackSettings = false
        RadioGate.registerBle(this)
        if (RadioGate.btExclusive) {
            // Finder already owns the adapter — sit idle until it releases.
            yielded = true
            running = true
            startWatchdog(scope)
            SourceHealth.record(
                DetectionSource.BLE, ok = true,
                message = "paused — Finder using the radio"
            )
            AppLog.i(TAG, "BLE start deferred — Finder holds the adapter")
            return true
        }
        if (!beginScan()) {
            RadioGate.unregisterBle(this)
            return false
        }
        startWatchdog(scope)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        RadioGate.unregisterBle(this)
        yielded = false
        watchdog?.cancel()
        watchdog = null
        if (!running && leScanner == null) return
        try {
            leScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            AppLog.e(TAG, "SecurityException stopping scan", e)
        }
        running = false
        AppLog.i(TAG, "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    override fun yieldLeScan() {
        if (!running) return
        yielded = true
        try {
            leScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        AppLog.i(TAG, "BLE yielded")
        SourceHealth.record(
            DetectionSource.BLE, ok = true,
            message = "paused — Finder using the radio"
        )
    }

    @SuppressLint("MissingPermission")
    override fun resumeLeScan() {
        if (!running || !yielded) return
        yielded = false
        if (leScanner == null) {
            leScanner = bluetoothAdapter?.bluetoothLeScanner
        }
        if (leScanner == null) {
            AppLog.w(TAG, "resume LE: scanner unavailable")
            return
        }
        beginScan()
        AppLog.i(TAG, "BLE resumed")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            if (yielded) {
                AppLog.w(TAG, "BLE scan failed while yielded ($errorCode) — ignoring")
                return
            }
            running = false
            val why = scanFailName(errorCode)
            AppLog.e(TAG, "BLE scan failed: $why ($errorCode)")
            if (!useFallbackSettings &&
                errorCode != ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
            ) {
                useFallbackSettings = true
                AppLog.w(TAG, "retrying BLE scan with fallback ScanSettings")
                beginScan()
                return
            }
            SourceHealth.record(
                DetectionSource.BLE,
                ok = false,
                message = "BLE scan failed ($why)"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginScan(): Boolean {
        val scanner = leScanner ?: return false
        return try {
            // Match-all filter (not null): keeps the scan from being suspended
            // when Location services / screen are off. See [matchAllFilters].
            scanner.startScan(matchAllFilters, currentSettings(), scanCallback)
            running = true
            SourceHealth.record(DetectionSource.BLE, ok = true, message = "listening")
            AppLog.i(
                TAG,
                "BLE scan started (${if (useFallbackSettings) "fallback" else "primary"} settings, " +
                    "locationEnabled=${locationEnabled()})"
            )
            true
        } catch (e: SecurityException) {
            AppLog.e(TAG, "SecurityException starting scan", e)
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission revoked")
            false
        } catch (e: IllegalArgumentException) {
            AppLog.e(TAG, "startScan rejected", e)
            if (!useFallbackSettings) {
                useFallbackSettings = true
                return beginScan()
            }
            SourceHealth.record(DetectionSource.BLE, ok = false, message = e.message)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScan() {
        if (yielded) return
        val scanner = leScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) { }
        running = false
        beginScan()
    }

    private fun startWatchdog(scope: CoroutineScope) {
        watchdog?.cancel()
        adsInWindow.set(0)
        synchronized(uniqueMacs) { uniqueMacs.clear() }
        watchdog = scope.launch {
            var elapsedQuiet = 0L
            var sincePeriodic = 0L
            var didStuckRestart = false
            while (isActive) {
                delay(HEARTBEAT_MS)
                sincePeriodic += HEARTBEAT_MS
                if (yielded || RadioGate.btExclusive) {
                    SourceHealth.record(
                        DetectionSource.BLE, ok = true,
                        message = "paused — Finder using the radio"
                    )
                    continue
                }
                val ads = adsInWindow.getAndSet(0)
                val macs = synchronized(uniqueMacs) {
                    val n = uniqueMacs.size
                    uniqueMacs.clear()
                    n
                }
                val locOn = locationEnabled()
                // neverForLocation disavows location for BLE scans, so
                // location-off only blocks results on API ≤30 (no disavowal
                // mechanism there).
                val locBlocks = !locOn && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                if (ads == 0) {
                    elapsedQuiet += HEARTBEAT_MS
                    AppLog.w(
                        TAG,
                        "BLE heartbeat: 0 advertisements in ${HEARTBEAT_MS / 1000}s " +
                            "(locationEnabled=$locOn, rfSilent=${rfSilent()})."
                    )
                    SourceHealth.record(
                        DetectionSource.BLE,
                        ok = !locBlocks,
                        message = when {
                            locBlocks -> "Location off — blocks BLE results on Android 11 and older"
                            rfSilent() -> "RF-silent — waiting for system scans"
                            else -> "listening — 0 ads in 15s"
                        }
                    )
                    // Opportunistic scans are legitimately sparse — silence is
                    // not "stuck", so no restart in RF-silent mode. Nor when
                    // location-off is withholding results — a restart can't fix
                    // that and would only thrash the radio.
                    if (!rfSilent() && !locBlocks && !didStuckRestart &&
                        elapsedQuiet >= STUCK_RESTART_MS) {
                        didStuckRestart = true
                        AppLog.w(TAG, "restarting BLE scan after silence")
                        restartScan()
                    }
                } else {
                    elapsedQuiet = 0L
                    didStuckRestart = false
                    AppLog.i(TAG, "BLE heartbeat: $ads ads, $macs devices in 15s")
                    SourceHealth.record(
                        DetectionSource.BLE, ok = true,
                        message = "$ads ads / $macs devices in 15s"
                    )
                }
                if (sincePeriodic >= PERIODIC_RESTART_MS) {
                    sincePeriodic = 0L
                    AppLog.i(TAG, "periodic BLE scan restart")
                    restartScan()
                }
            }
        }
    }

    private fun locationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun scanFailName(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCANNING_TOO_FREQUENTLY"
        else -> "code $code"
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val device = result.device
        val mac = device.address ?: return
        adsInWindow.incrementAndGet()
        synchronized(uniqueMacs) { uniqueMacs.add(mac) }
        val name = try { device.name } catch (e: SecurityException) { null }
        val record = result.scanRecord

        val advertisedUuids = record?.serviceUuids?.map { it.uuid }
        val advertisedName = name ?: record?.deviceName
        val mfgSpecific = record?.manufacturerSpecificData
        // Iterate ALL manufacturer-data entries; some devices advertise multiple
        // and XUNTONG might not be the first one. Prefer the XUNTONG match if
        // present, otherwise fall back to the first entry so we still surface
        // *some* mfg signal in the observation.
        var companyId: Int? = null
        var payload: ByteArray? = null
        val companyIds = mutableListOf<Int>()
        if (mfgSpecific != null && mfgSpecific.size() > 0) {
            for (i in 0 until mfgSpecific.size()) {
                val cid = mfgSpecific.keyAt(i)
                val data = mfgSpecific.valueAt(i)
                companyIds += cid
                if (cid == ch.swhizkid.tailtrace.data.targets.Manufacturers.XUNTONG_COMPANY_ID) {
                    companyId = cid
                    payload = data
                } else if (companyId == null) {
                    companyId = cid
                    payload = data
                }
            }
        }

        val uuidStrings = advertisedUuids?.map { it.toString() } ?: emptyList()
        var catalogHint: String? = null
        var catalogScore = 0

        val isSurveillance = BleOuis.matches(mac) ||
            Patterns.bleNameMatch(advertisedName) ||
            Patterns.isPenguinNumeric(advertisedName) ||
            RavenUuids.countMatches(advertisedUuids) > 0 ||
            companyId == ch.swhizkid.tailtrace.data.targets.Manufacturers.XUNTONG_COMPANY_ID
        val isMic = micEnabled() &&
            MicTargets.couldBeMicBle(mac, advertisedName, advertisedUuids, companyId)

        rssi.update(mac, result.rssi)
        val stationary = rssi.isStationary(mac)

        if (isSurveillance) {
            val obs = ConfidenceEngine.BleObservation(
                mac = mac,
                rssi = result.rssi,
                deviceName = advertisedName,
                advertisedUuids = advertisedUuids,
                manufacturerCompanyId = companyId,
                manufacturerPayload = payload,
                isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreBle(obs)
            catalogHint = scored.label
            catalogScore = scored.score
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.BLE,
                        key = mac,
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = result.rssi
                    )
                )
            }
        }
        if (isMic) {
            val obs = ConfidenceEngine.MicBleObservation(
                mac = mac,
                rssi = result.rssi,
                deviceName = advertisedName,
                advertisedUuids = advertisedUuids,
                manufacturerCompanyId = companyId,
                isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreMicBle(obs)
            if (catalogHint == null || scored.score > catalogScore) {
                catalogHint = scored.label
                catalogScore = scored.score
            }
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.COMMERCIAL,
                        // Disambiguate from any BLE event on the same MAC so the
                        // store's (source, key) dedup doesn't collide.
                        key = "mic:$mac",
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = result.rssi
                    )
                )
            }
        }

        TrackerParser.parse(result)?.let { onTracker?.invoke(it) }

        onObservation?.invoke(
            RadioObservation(
                radio = Radio.BLE,
                address = mac,
                advertisedName = advertisedName,
                companyIds = companyIds,
                serviceUuids = uuidStrings,
                payload = payload,
                rssi = result.rssi,
                catalogHint = catalogHint,
                score = catalogScore
            )
        )
    }
}

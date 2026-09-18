package ch.swhizkid.tailtrace.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ch.swhizkid.tailtrace.data.targets.BtCompanyIds
import ch.swhizkid.tailtrace.data.targets.VendorOuis
import ch.swhizkid.tailtrace.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Bluetooth band of the Finder: BLE listen and classic inquiry on a duty
 * cycle so they never share the adapter. Independent of [DetectionService]
 * except through [RadioGate], which parks Detection's LE scan while this
 * scanner holds exclusive (inquiry slot or a hunt).
 *
 * Survey (list): inquiry ~12 s then BLE ~10 s, never overlapping. Exclusive
 * is held only for the inquiry slot so Detection can scan between slots.
 * Hunt (proximity meter): exclusive for the whole stay — BLE-only or
 * classic-only, never both, and never Wi-Fi.
 */
class BtFinderScanner(context: Context) : SignalFinder(context) {

    companion object {
        private const val TAG = "BtFinderScanner"
        /** Long enough for several advert intervals; short enough that a
         *  classic-only device stays inside the Finder's live window. */
        private const val BLE_WINDOW_MS = 10_000L
        /** HCI needs a beat after stopScan / cancelDiscovery before the other
         *  mode will actually start. 400 ms was not enough on some stacks. */
        private const val SETTLE_MS = 1_000L
        /** AOSP inquiry is ~12.08 s; pad so a stuck adapter unblocks. */
        private const val INQUIRY_TIMEOUT_MS = 13_000L
    }

    override val band = FinderBand.BLE

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var leScanner: BluetoothLeScanner? = null
    private var classicRegistered = false
    private var btExclusiveHeld = false
    private var rfSilent = false
    private var hunt = BluetoothHuntMode.SURVEY
    private var huntAddress: String? = null
    /** True once a radio loop (or the RF-silent scan) is up. [setHunt] must
     *  not early-return on "mode unchanged" before the first loop starts —
     *  start() defers to setHunt, and the initial mode is already SURVEY. */
    private var radioStarted = false
    /** Bumped on every [startLoop]/[stop] so a cancelled inquiry finally
     *  cannot drop exclusive that a newer loop just took. */
    private var loopGen = 0
    private var scope: CoroutineScope? = null
    @Volatile private var discoveryDone: CompletableDeferred<Unit>? = null

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

    val isAvailable: Boolean
        get() = adapter?.isEnabled == true

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleBle(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleBle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.w(TAG, "BLE finder scan failed: $errorCode")
            // Keep the duty cycle alive — inquiry can still run next slot.
            _status.value = "BLE scan failed (code $errorCode) — classic inquiry continues"
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> handleClassic(intent)
                BluetoothAdapter.ACTION_DISCOVERY_STARTED ->
                    AppLog.i(TAG, "classic inquiry started")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    AppLog.i(TAG, "classic inquiry finished")
                    val done = discoveryDone
                    if (done != null && !done.isCompleted) done.complete(Unit)
                }
            }
        }
    }

    /**
     * @param rfSilent Classic inquiry is skipped and the BLE scan runs
     *   opportunistic — no scan radio time of our own. Detection's LE scan
     *   stays up so we have something to ride on.
     */
    @SuppressLint("MissingPermission")
    override fun start(rfSilent: Boolean): Boolean {
        if (_scanning.value) return true
        if (!hasScanPermission()) {
            AppLog.w(TAG, "finder: scan permission missing")
            _status.value = "Bluetooth scan permission missing"
            return false
        }
        val a = adapter ?: run {
            _status.value = "No Bluetooth adapter"
            return false
        }
        if (!a.isEnabled) {
            AppLog.w(TAG, "finder: bluetooth off")
            _status.value = "Bluetooth is off — enable it to hunt BLE/BT"
            return false
        }
        clearSignals()
        leScanner = a.bluetoothLeScanner

        // EXPORTED: ACTION_FOUND is an implicit system broadcast. On several
        // Android 13+ builds RECEIVER_NOT_EXPORTED silently drops it, which
        // is why classic devices showed in Settings but never here.
        ContextCompat.registerReceiver(
            context,
            classicReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        classicRegistered = true

        this.rfSilent = rfSilent
        hunt = BluetoothHuntMode.SURVEY
        huntAddress = null
        _scanning.value = true
        seedKnownDevices(a)
        if (rfSilent) {
            _status.value = "RF-silent — passive listening only, no inquiry or scan requests"
            startBle(opportunistic = true)
            radioStarted = true
            AppLog.i(TAG, "finder started (rfSilent=true)")
            return true
        }

        // Do not startLoop here. FinderScreen always calls setHunt right
        // after scanning becomes true; setHunt starts the first loop
        // (radioStarted=false disables its "mode unchanged" early-return).
        // Starting survey here would fire a ~12 s inquiry (TX) when the user
        // resumes straight onto the hunt pane, then tear it down.
        radioStarted = false
        _status.value = "Starting…"
        AppLog.i(TAG, "finder started — waiting for setHunt")
        return true
    }

    /**
     * List = [BluetoothHuntMode.SURVEY] (BLE + classic, Detection resumes
     * between slots). Hunt pane = BLE-only or classic-only; every other
     * radio is parked.
     */
    override fun setHunt(signal: FinderSignal?) {
        if (!_scanning.value) return
        val next = bluetoothHuntMode(signal)
        val addr = signal?.address
        if (radioStarted && next == hunt && addr == huntAddress) return
        radioStarted = true
        hunt = next
        huntAddress = addr
        AppLog.i(TAG, "hunt=$hunt address=$addr tags=${signal?.tags}")
        if (rfSilent) {
            applyRfSilentHunt(next, addr)
            return
        }
        startLoop()
    }

    /** Opportunistic only — never inquiry (that is TX). Filter to the MAC if we can. */
    private fun applyRfSilentHunt(mode: BluetoothHuntMode, address: String?) {
        when (mode) {
            BluetoothHuntMode.SURVEY -> {
                stopBle()
                startBle(opportunistic = true)
                _status.value = "RF-silent — passive listening only, no inquiry or scan requests"
            }
            BluetoothHuntMode.BLE -> {
                stopBle()
                startBle(opportunistic = true, address = address)
                _status.value = "RF-silent hunt — opportunistic BLE, Detection still scanning"
            }
            BluetoothHuntMode.CLASSIC -> {
                _status.value = "RF-silent — classic inquiry transmits; cannot hunt BR/EDR"
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!_scanning.value && !classicRegistered && !btExclusiveHeld) return
        _scanning.value = false
        loopGen++
        val done = discoveryDone
        if (done != null && !done.isCompleted) done.complete(Unit)
        scope?.cancel()
        scope = null
        stopBle()
        if (classicRegistered) {
            try {
                context.unregisterReceiver(classicReceiver)
            } catch (_: IllegalArgumentException) {
            }
            classicRegistered = false
        }
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        RadioGate.setInquiry(false)
        dropExclusive()
        hunt = BluetoothHuntMode.SURVEY
        huntAddress = null
        radioStarted = false
        AppLog.i(TAG, "finder stopped")
    }

    private fun startLoop() {
        loopGen++
        val done = discoveryDone
        if (done != null && !done.isCompleted) done.complete(Unit)
        scope?.cancel()
        stopBle()
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        RadioGate.setInquiry(false)
        if (hunt == BluetoothHuntMode.SURVEY) dropExclusive()
        else holdExclusive()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            when (hunt) {
                BluetoothHuntMode.SURVEY -> dutyCycle()
                BluetoothHuntMode.BLE -> huntBle()
                BluetoothHuntMode.CLASSIC -> huntClassic()
            }
        }
    }

    private fun holdExclusive() {
        if (btExclusiveHeld) return
        RadioGate.acquireBtExclusive(RadioGate.OWNER_BT_FINDER)
        btExclusiveHeld = true
    }

    private fun dropExclusive() {
        if (!btExclusiveHeld) return
        RadioGate.releaseBtExclusive(RadioGate.OWNER_BT_FINDER)
        btExclusiveHeld = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun huntBle() {
        val gen = loopGen
        _status.value = "Hunting BLE — Wi-Fi and classic inquiry paused"
        startBle(opportunistic = false, address = huntAddress)
        try {
            while (coroutineContext.isActive && _scanning.value) delay(1_000)
        } finally {
            // A newer startLoop() already owns the scanner — do not stopScan it.
            if (loopGen == gen) stopBle()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun huntClassic() {
        while (coroutineContext.isActive && _scanning.value) {
            _status.value = "Hunting classic — BLE and Wi-Fi paused"
            runInquiryPhase()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun dutyCycle() {
        while (coroutineContext.isActive && _scanning.value) {
            // Inquiry first so a classic-only module is visible immediately,
            // not after a 10 s BLE window that can never hear it.
            runInquiryPhase()
            if (!coroutineContext.isActive || !_scanning.value) break
            _status.value = "BLE listen — classic inquiry next"
            startBle(opportunistic = false)
            delay(BLE_WINDOW_MS)
            if (!coroutineContext.isActive || !_scanning.value) break
            stopBle()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runInquiryPhase() {
        val gen = loopGen
        if (hunt == BluetoothHuntMode.SURVEY) holdExclusive()
        RadioGate.setInquiry(true)
        if (hunt == BluetoothHuntMode.SURVEY) {
            _status.value = "Classic inquiry — BLE paused so BR/EDR can answer"
        }
        try {
            delay(SETTLE_MS)
            if (!coroutineContext.isActive || !_scanning.value) return
            val a = adapter ?: return
            try {
                if (a.isDiscovering) a.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            delay(SETTLE_MS)
            if (!coroutineContext.isActive || !_scanning.value) return
            val done = CompletableDeferred<Unit>()
            discoveryDone = done
            var started = tryStartDiscovery(a)
            if (!started) {
                AppLog.w(TAG, "startDiscovery false — settle and retry once")
                delay(SETTLE_MS)
                if (!coroutineContext.isActive || !_scanning.value) return
                started = tryStartDiscovery(a)
            }
            if (!started) {
                AppLog.w(TAG, "startDiscovery returned false twice — skipping this slot")
                _status.value = if (hunt == BluetoothHuntMode.CLASSIC) {
                    "Classic inquiry didn't start — retrying"
                } else {
                    "Classic inquiry didn't start — retrying after BLE"
                }
                discoveryDone = null
                return
            }
            AppLog.i(TAG, "startDiscovery accepted")
            try {
                withTimeout(INQUIRY_TIMEOUT_MS) { done.await() }
            } catch (_: TimeoutCancellationException) {
                AppLog.w(TAG, "inquiry timed out after ${INQUIRY_TIMEOUT_MS}ms")
                try {
                    a.cancelDiscovery()
                } catch (_: SecurityException) {
                }
            }
        } finally {
            // Stale generation: a newer startLoop() already cancelled discovery
            // and may have started a new scan. Touching the adapter here would
            // kill the hunt that replaced us.
            if (loopGen == gen) {
                discoveryDone = null
                try {
                    adapter?.cancelDiscovery()
                } catch (_: SecurityException) {
                }
                RadioGate.setInquiry(false)
                if (hunt == BluetoothHuntMode.SURVEY) dropExclusive()
                if (coroutineContext.isActive && _scanning.value) delay(SETTLE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBle(opportunistic: Boolean, address: String? = null) {
        val scanner = leScanner ?: adapter?.bluetoothLeScanner?.also { leScanner = it }
        if (scanner == null) {
            AppLog.w(TAG, "BLE scanner unavailable")
            return
        }
        try {
            scanner.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (opportunistic) ScanSettings.SCAN_MODE_OPPORTUNISTIC
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .setReportDelay(0)
            .build()
        val filters = bleFilters(address) ?: run {
            _status.value = "Hunt address $address is not a usable MAC — radio parked"
            AppLog.w(TAG, "refusing match-all fallback for hunt address $address")
            return
        }
        try {
            // Non-empty filter list so the scan isn't suspended while the
            // screen is off. A hunt filter is the target MAC; survey is
            // match-all. See BleScanner for neverForLocation.
            scanner.startScan(filters, settings, bleCallback)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "finder BLE start denied: ${e.message}")
            _status.value = "BLE scan denied"
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "finder BLE start rejected: ${e.message}")
        }
    }

    /**
     * @return filters to pass to [BluetoothLeScanner.startScan], or null
     *   when a hunt address is present but illegal — caller must not fall
     *   back to match-all (that would scan everyone while Detection is parked).
     */
    private fun bleFilters(address: String?): List<ScanFilter>? {
        if (address == null) return listOf(ScanFilter.Builder().build())
        return try {
            listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "ScanFilter rejected $address: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try {
            leScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
    }

    private fun handleBle(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val record = result.scanRecord
        val name = record?.deviceName ?: safeName(device)
        val companyIds = mutableListOf<Int>()
        record?.manufacturerSpecificData?.let { mfg ->
            for (i in 0 until mfg.size()) companyIds.add(mfg.keyAt(i))
        }
        val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        upsert(
            address = address,
            name = name,
            vendor = BtCompanyIds.bestName(companyIds) ?: VendorOuis.label(address),
            tags = listOf("BLE") + typeTags(device),
            rssi = result.rssi,
            txPower = txPower
        )
    }

    private fun handleClassic(intent: Intent) {
        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        val address = device?.address ?: return
        val rssi = classicRssi(intent)
        AppLog.i(TAG, "classic found $address rssi=$rssi name=${safeName(device)}")
        upsert(
            address = address,
            name = safeName(device),
            vendor = VendorOuis.label(address),
            tags = listOfNotNull("Classic", classLabel(device)) + typeTags(device),
            rssi = rssi
        )
    }

    @SuppressLint("MissingPermission")
    private fun tryStartDiscovery(a: BluetoothAdapter): Boolean = try {
        a.startDiscovery()
    } catch (e: SecurityException) {
        AppLog.w(TAG, "classic discovery denied: ${e.message}")
        false
    }

    /**
     * Paired / connected devices Settings already knows about. Inquiry may
     * still miss them if they are not discoverable; listing them means a
     * previously-seen Rayson-style module is at least on the screen.
     */
    @SuppressLint("MissingPermission")
    private fun seedKnownDevices(a: BluetoothAdapter) {
        try {
            a.bondedDevices?.forEach { upsertKnown(it, extra = "Paired") }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "bondedDevices denied: ${e.message}")
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return
        val profiles = buildList {
            add(BluetoothProfile.GATT)
            add(BluetoothProfile.GATT_SERVER)
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(BluetoothProfile.HID_DEVICE)
            }
        }
        for (profile in profiles) {
            val fallback = when (profile) {
                BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER -> listOf("BLE")
                BluetoothProfile.HEADSET, BluetoothProfile.A2DP -> listOf("Classic")
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    profile == BluetoothProfile.HID_DEVICE
                ) {
                    listOf("Classic")
                } else {
                    emptyList()
                }
            }
            try {
                mgr.getConnectedDevices(profile).forEach {
                    upsertKnown(it, extra = "Connected", fallbackIfUntyped = fallback)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun upsertKnown(
        device: BluetoothDevice,
        extra: String,
        fallbackIfUntyped: List<String> = emptyList()
    ) {
        val address = device.address ?: return
        val typed = typeTags(device)
        upsert(
            address = address,
            name = safeName(device),
            vendor = VendorOuis.label(address),
            tags = listOfNotNull(extra, classLabel(device)) + typed +
                if (typed.isEmpty()) fallbackIfUntyped else emptyList(),
            rssi = null
        )
    }

    private fun classicRssi(intent: Intent): Int? {
        if (!intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) return null
        val asShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        if (asShort != Short.MIN_VALUE) return asShort.toInt()
        val asInt = intent.getIntExtra(BluetoothDevice.EXTRA_RSSI, Int.MIN_VALUE)
        return asInt.takeIf { it != Int.MIN_VALUE }
    }

    @SuppressLint("MissingPermission")
    private fun typeTags(device: BluetoothDevice): List<String> = try {
        bluetoothTypeTags(device.type)
    } catch (_: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name?.ifBlank { null }
    } catch (_: SecurityException) {
        null // BLUETOOTH_CONNECT not granted
    }

    private fun classLabel(device: BluetoothDevice): String? {
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        } ?: return null
        return when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
            BluetoothClass.Device.Major.COMPUTER -> "Computer"
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            BluetoothClass.Device.Major.HEALTH -> "Health"
            BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
            BluetoothClass.Device.Major.IMAGING -> "Imaging"
            BluetoothClass.Device.Major.NETWORKING -> "Networking"
            BluetoothClass.Device.Major.TOY -> "Toy"
            else -> null
        }
    }
}

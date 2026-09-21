package ch.swhizkid.tailtrace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ch.swhizkid.tailtrace.MainActivity
import ch.swhizkid.tailtrace.R
import ch.swhizkid.tailtrace.data.catalog.RadioObservation
import ch.swhizkid.tailtrace.data.catalog.SignatureRepository
import ch.swhizkid.tailtrace.data.location.LocationProvider
import ch.swhizkid.tailtrace.data.settings.Settings
import ch.swhizkid.tailtrace.fusion.DetectionEvent
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.fusion.DetectionStore
import ch.swhizkid.tailtrace.fusion.SourceHealth
import ch.swhizkid.tailtrace.fusion.ThreatLevel
import ch.swhizkid.tailtrace.root.PrivEnhancer
import ch.swhizkid.tailtrace.tracker.data.TrackerRepository
import ch.swhizkid.tailtrace.tracker.detect.PresenceEngine
import ch.swhizkid.tailtrace.tracker.model.RiskState
import ch.swhizkid.tailtrace.tracker.model.SeparatedState
import ch.swhizkid.tailtrace.tracker.model.TrackerObservation
import ch.swhizkid.tailtrace.tracker.model.TrackerRow
import ch.swhizkid.tailtrace.scan.AircraftScanner
import ch.swhizkid.tailtrace.scan.BleScanner
import ch.swhizkid.tailtrace.scan.CellScanner
import ch.swhizkid.tailtrace.scan.DeflockClient
import ch.swhizkid.tailtrace.scan.DeflockScanner
import ch.swhizkid.tailtrace.scan.DeflockClient.AlprPoint
import ch.swhizkid.tailtrace.scan.WazeClient
import ch.swhizkid.tailtrace.scan.WazeScanner
import ch.swhizkid.tailtrace.scan.WifiScanner
import ch.swhizkid.tailtrace.util.AppLog

/**
 * Foreground service that owns scanners (BLE, WiFi, OSM, Waze, aircraft, CELL)
 * and the [DetectionStore]. UI observes companion-object state flows directly.
 *
 * Responsibilities beyond scanner orchestration:
 *  - Updates the foreground notification on every threat-tier change so a
 *    locked-screen user sees escalations.
 *  - Vibrates on upward tier transitions (gated by Settings.vibrateOnAlert).
 *  - Resets [SourceHealth] on start/stop.
 *
 * Returns START_NOT_STICKY so a system-killed service does not auto-restart
 * into a zombie state where the notification disappears but `_running` stays
 * stale. The user explicitly starts and stops; auto-restart isn't needed.
 */
class DetectionService : LifecycleService() {

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "overwatch_detection"
        private const val NOTIFICATION_ID = 0xBEEF
        private const val ALERT_CHANNEL_ID = "tailtrace_tracker_alerts"
        private const val ALERT_NOTIF_ID = 0x5162
        private const val CLONE_NOTIF_ID = 0x5163
        private const val PRUNE_TRACKERS_MS = 6 * 3_600_000L
        private const val CLONE_COOLDOWN_MS = 2 * 3_600_000L
        private const val MIN_RECORD_INTERVAL_MS = 15_000L

        const val ACTION_START = "ch.swhizkid.tailtrace.action.START"
        const val ACTION_STOP = "ch.swhizkid.tailtrace.action.STOP"

        /** Single shared store — UI observes this. */
        val store: DetectionStore = DetectionStore()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Latest ALPR cell cache — UI map renders these as pins. Mirrored from
         *  the active DeflockScanner while the service is running; cleared on stop. */
        private val _mapPoints = MutableStateFlow<List<AlprPoint>>(emptyList())
        val mapPoints: StateFlow<List<AlprPoint>> = _mapPoints.asStateFlow()

        /** Latest fused location fix — UI map centers on this. */
        private val _location = MutableStateFlow<Location?>(null)
        val location: StateFlow<Location?> = _location.asStateFlow()

        @Volatile private var finderTarget: String? = null
        private val _finderRssi = MutableStateFlow<Int?>(null)
        val finderRssi: StateFlow<Int?> = _finderRssi.asStateFlow()
        fun setFinderTarget(id: String?) {
            finderTarget = id
            _finderRssi.value = null
        }

        fun start(context: Context) {
            val intent = Intent(context, DetectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DetectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var settings: Settings
    private lateinit var catalog: SignatureRepository
    private lateinit var trackers: TrackerRepository
    private val presence = PresenceEngine()
    private val lastTrackerRecordedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastCloneAlertMs = 0L
    private var trackerPruneJob: Job? = null
    private lateinit var bleScanner: BleScanner
    private lateinit var wifiScanner: WifiScanner
    private lateinit var locationProvider: LocationProvider
    private lateinit var deflockScanner: DeflockScanner
    private lateinit var cellScanner: CellScanner
    private lateinit var wazeScanner: WazeScanner
    private lateinit var aircraftScanner: AircraftScanner
    private lateinit var overlayManager: OverlayManager
    private var pruneJob: Job? = null
    private var observerJob: Job? = null
    private var mapPointsJob: Job? = null
    private var locationJob: Job? = null
    private var deflockProxJob: Job? = null
    private var wazeProxJob: Job? = null
    private var overlayJob: Job? = null
    private var bleStarted = false
    private var wifiStarted = false
    private var deflockStarted = false
    private var cellStarted = false
    private var wazeStarted = false
    private var aircraftStarted = false
    /** Last threat tier the notification displayed; tracks upward transitions for vibration. */
    private var lastNotifiedTier: ThreatLevel = ThreatLevel.GREEN

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        catalog = SignatureRepository.get(this)
        trackers = TrackerRepository.get(this)
        bleScanner = BleScanner(
            this, store,
            micEnabled = { settings.micEnabled.value },
            rfSilent = { settings.rfSilent.value }
        )
        wifiScanner = WifiScanner(
            this, store,
            micEnabled = { settings.micEnabled.value },
            rfSilent = { settings.rfSilent.value }
        )
        bleScanner.onObservation = { persistObservation(it) }
        bleScanner.onTracker = { onTrackerObservation(it) }
        wifiScanner.onObservation = { persistObservation(it) }
        locationProvider = LocationProvider(this)
        lifecycleScope.launch { catalog.refresh() }
        lifecycleScope.launch { trackers.refresh() }
        deflockScanner = DeflockScanner(
            store, locationProvider, DeflockClient(this),
            proximityMeters = { settings.osmProximityM.value.toFloat() }
        )
        cellScanner = CellScanner(this, store, locationProvider)
        wazeScanner = WazeScanner(
            store, locationProvider,
            client = WazeClient(appToken = { settings.wazeProxyToken.value }),
            proximityMeters = { settings.wazeProximityM.value.toFloat() }
        )
        aircraftScanner = AircraftScanner(this, store, locationProvider)
        overlayManager = OverlayManager(
            context = this,
            // User dragged the bubble onto the X — flip the persisted toggle
            // so the setting and the bubble state stay aligned. The settings
            // collector below will call hide() again, but hide() is idempotent.
            onDismissed = { settings.setOverlayEnabled(false) }
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginScanning()
            ACTION_STOP -> {
                endScanning()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginScanning() {
        if (_running.value) return
        SourceHealth.reset()
        lastNotifiedTier = ThreatLevel.GREEN
        // Bring up the foreground notification BEFORE any scanner so we don't
        // accidentally call startForeground after work has already begun.
        startInForeground(ThreatLevel.GREEN, topEvent = null)

        // Priv-app extras: lift the WiFi scan throttle
        // via WRITE_SECURE_SETTINGS and report Doze-whitelist state. Runs
        // async; WifiScanner re-checks the throttle every cycle, so ordering
        // vs. scanner start doesn't matter. Silent no-op when the permission
        // isn't granted.
        if (settings.rootEnabled.value) {
            lifecycleScope.launch { PrivEnhancer.apply(this@DetectionService) }
        }

        if (settings.bleEnabled.value) {
            bleStarted = bleScanner.start(lifecycleScope)
            if (!bleStarted) AppLog.w(TAG, "BleScanner.start() returned false (permission/adapter)")
        }
        if (settings.wifiEnabled.value) {
            wifiStarted = wifiScanner.start(lifecycleScope)
            if (!wifiStarted) AppLog.w(TAG, "WifiScanner.start() returned false (permission/adapter)")
        }
        // Geotag radio sightings even when map feeds are off. Failure is
        // non-fatal — BLE/WiFi still persist signatures without coordinates.
        val locOk = locationProvider.start()
        if (!locOk) {
            AppLog.w(TAG, "LocationProvider.start() returned false (permission)")
        }
        val needsMapFeeds = settings.osmEnabled.value || settings.wazeEnabled.value ||
            settings.aircraftEnabled.value
        if (needsMapFeeds && locOk) {
            if (settings.osmEnabled.value) {
                deflockScanner.start(lifecycleScope); deflockStarted = true
            }
            if (settings.wazeEnabled.value) {
                wazeScanner.start(lifecycleScope); wazeStarted = true
            }
            if (settings.aircraftEnabled.value) {
                aircraftScanner.start(lifecycleScope); aircraftStarted = true
            }
        }
        if (settings.cellEnabled.value) {
            cellStarted = cellScanner.start(lifecycleScope)
            if (!cellStarted) AppLog.w(TAG, "CellScanner.start() returned false")
        }

        val anyStarted = bleStarted || wifiStarted || deflockStarted || wazeStarted ||
            aircraftStarted || cellStarted
        if (!anyStarted) {
            AppLog.w(TAG, "No scanner started — endScanning + stopSelf")
            endScanning()
            stopSelf()
            return
        }

        // MIC piggybacks on the BLE/WiFi scanners. Surface its health so the
        // user sees an explicit status row rather than a silent UNKNOWN.
        if (settings.micEnabled.value) {
            if (bleStarted || wifiStarted) {
                SourceHealth.record(DetectionSource.COMMERCIAL, ok = true)
            } else {
                SourceHealth.record(
                    DetectionSource.COMMERCIAL,
                    ok = false,
                    message = "Needs BLE or WiFi scanner enabled"
                )
            }
        }
        if (settings.trackerEnabled.value) {
            if (bleStarted) {
                SourceHealth.record(DetectionSource.TRACKER, ok = true)
            } else {
                SourceHealth.record(
                    DetectionSource.TRACKER,
                    ok = false,
                    message = "Needs BLE scanner enabled"
                )
            }
        }

        _running.value = true
        AppLog.i(
            TAG,
            "scanning started ble=$bleStarted wifi=$wifiStarted " +
                "osm=$deflockStarted waze=$wazeStarted air=$aircraftStarted cell=$cellStarted"
        )
        presence.reset()
        lastTrackerRecordedAt.clear()
        pruneJob?.cancel()
        pruneJob = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                store.pruneExpired()
            }
        }
        trackerPruneJob?.cancel()
        trackerPruneJob = lifecycleScope.launch {
            while (true) {
                runCatching { trackers.prune() }.onFailure { Log.w(TAG, "tracker prune: ${it.message}") }
                delay(PRUNE_TRACKERS_MS)
            }
        }
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            // Watch threat tier + the top event together; rebuild the notification
            // on either change. Vibrate only when the tier ratchets upward.
            store.threatLevel.combine(store.events) { tier, events ->
                tier to events.firstOrNull()
            }.collect { (tier, top) ->
                onTierChanged(tier, top)
            }
        }

        // Mirror scanner state to the companion StateFlows the UI observes.
        // These exist so the map widget doesn't need a direct handle on the
        // scanner instances (which are private to this service).
        mapPointsJob?.cancel()
        if (deflockStarted) {
            mapPointsJob = lifecycleScope.launch {
                deflockScanner.cachedPoints.collect { _mapPoints.value = it }
            }
        }
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            locationProvider.location.collect { _location.value = it }
        }

        // Live re-eval when the user moves a proximity slider. drop(1) skips
        // the StateFlow's initial replay so we don't redundantly clear+re-emit
        // the events the scanner just produced from its first handleFix call.
        deflockProxJob?.cancel()
        if (deflockStarted) {
            deflockProxJob = lifecycleScope.launch {
                settings.osmProximityM.drop(1).collect { deflockScanner.refresh() }
            }
        }
        wazeProxJob?.cancel()
        if (wazeStarted) {
            wazeProxJob = lifecycleScope.launch {
                settings.wazeProximityM.drop(1).collect { wazeScanner.refresh() }
            }
        }

        // Floating threat-circle overlay — observe the toggle and show/hide
        // accordingly. The OverlayManager re-checks SYSTEM_ALERT_WINDOW each
        // show() so a denied/revoked permission silently no-ops.
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            settings.overlayEnabled.collect { enabled ->
                if (enabled) overlayManager.show() else overlayManager.hide()
            }
        }
    }

    private fun endScanning() {
        if (!_running.value && !bleStarted && !wifiStarted && !deflockStarted && !wazeStarted &&
            !aircraftStarted && !cellStarted
        ) {
            return
        }
        _running.value = false
        if (bleStarted) { bleScanner.stop(); bleStarted = false }
        if (wifiStarted) { wifiScanner.stop(); wifiStarted = false }
        if (deflockStarted) { deflockScanner.stop(); deflockStarted = false }
        if (cellStarted) { cellScanner.stop(); cellStarted = false }
        if (wazeStarted) { wazeScanner.stop(); wazeStarted = false }
        if (aircraftStarted) { aircraftScanner.stop(); aircraftStarted = false }
        locationProvider.stop()
        store.clear()
        SourceHealth.reset()
        pruneJob?.cancel(); pruneJob = null
        trackerPruneJob?.cancel(); trackerPruneJob = null
        lastTrackerRecordedAt.clear()
        setFinderTarget(null)
        observerJob?.cancel(); observerJob = null
        mapPointsJob?.cancel(); mapPointsJob = null
        locationJob?.cancel(); locationJob = null
        deflockProxJob?.cancel(); deflockProxJob = null
        wazeProxJob?.cancel(); wazeProxJob = null
        overlayJob?.cancel(); overlayJob = null
        overlayManager.hide()
        _mapPoints.value = emptyList()
        _location.value = null
        lastNotifiedTier = ThreatLevel.GREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        endScanning()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun persistObservation(obs: RadioObservation) {
        val loc = locationProvider.location.value
        lifecycleScope.launch {
            catalog.ingest(
                obs.copy(
                    lat = obs.lat ?: loc?.latitude,
                    lon = obs.lon ?: loc?.longitude
                )
            )
        }
    }

    private fun onTrackerObservation(obs: TrackerObservation) {
        if (!settings.trackerEnabled.value) return
        val fix = locationProvider.location.value

        if (obs.stableId == finderTarget) _finderRssi.value = obs.rssi

        if (obs.separated == SeparatedState.SEPARATED) {
            val v = presence.onSighting(obs.stableId, obs.rssi, obs.timestampMs, fix?.latitude, fix?.longitude)
            if (v.tier == PresenceEngine.Tier.CONFIRMED &&
                obs.timestampMs - lastCloneAlertMs > CLONE_COOLDOWN_MS
            ) {
                lastCloneAlertMs = obs.timestampMs
                raiseCloneAlert()
            }
        }

        val last = lastTrackerRecordedAt[obs.stableId]
        if (last != null && obs.timestampMs - last < MIN_RECORD_INTERVAL_MS) return
        lastTrackerRecordedAt[obs.stableId] = obs.timestampMs
        if (lastTrackerRecordedAt.size > 4096) lastTrackerRecordedAt.clear()

        lifecycleScope.launch {
            val result = runCatching {
                trackers.record(obs, fix?.latitude, fix?.longitude, settings.trackerSensitivity.value)
            }.getOrNull() ?: return@launch
            val row = result.tracker
            when (row.riskState) {
                RiskState.ALERTING.name -> submitTrackerEvent(row, score = 90, methods = "co_movement")
                RiskState.SUSPICIOUS.name -> submitTrackerEvent(row, score = 55, methods = "watching")
                else -> store.remove(DetectionSource.TRACKER, row.stableId)
            }
            if (result.newlyAlerting) raiseTrackerAlert(row)
        }
    }

    private fun submitTrackerEvent(row: TrackerRow, score: Int, methods: String) {
        val loc = locationProvider.location.value
        store.submit(
            DetectionEvent(
                source = DetectionSource.TRACKER,
                key = row.stableId,
                label = row.label,
                score = score,
                matchedMethods = methods,
                rssi = row.lastRssi,
                lat = loc?.latitude,
                lon = loc?.longitude
            )
        )
    }

    private fun raiseTrackerAlert(tracker: TrackerRow) {
        val n = buildTrackerAlertNotification(
            title = "Tracker following you",
            text = "${tracker.label} has been moving with you"
        )
        getSystemService(NotificationManager::class.java)?.notify(ALERT_NOTIF_ID, n)
        if (settings.vibrateOnAlert.value) vibrateForTier(ThreatLevel.RED)
        Log.w(TAG, "TRACKER ALERT: ${tracker.stableId} (${tracker.ecosystem})")
    }

    private fun raiseCloneAlert() {
        store.submit(
            DetectionEvent(
                source = DetectionSource.TRACKER,
                key = "clone-presence",
                label = "Rotating-ID tracker",
                score = 95,
                matchedMethods = "presence_clone"
            )
        )
        val n = buildTrackerAlertNotification(
            title = "Possible hidden tracker",
            text = "A rotating-ID tracker appears to be moving with you"
        )
        getSystemService(NotificationManager::class.java)?.notify(CLONE_NOTIF_ID, n)
        if (settings.vibrateOnAlert.value) vibrateForTier(ThreatLevel.RED)
        Log.w(TAG, "CLONE ALERT: rotating-id presence confirmed")
    }

    private fun buildTrackerAlertNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun onTierChanged(tier: ThreatLevel, top: DetectionEvent?) {
        // Re-issue the foreground notification with the current tier + top event
        // so a locked-screen user sees the escalation even without opening the app.
        val notification = buildNotification(tier, top)
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, notification)

        if (tier.ordinal > lastNotifiedTier.ordinal && settings.vibrateOnAlert.value) {
            vibrateForTier(tier)
        }
        lastNotifiedTier = tier
    }

    private fun vibrateForTier(tier: ThreatLevel) {
        val v = currentVibrator() ?: return
        val effect = when (tier) {
            ThreatLevel.YELLOW -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            ThreatLevel.ORANGE -> VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1)
            ThreatLevel.RED -> VibrationEffect.createWaveform(
                longArrayOf(0, 250, 120, 250, 120, 400), -1
            )
            ThreatLevel.GREEN -> return
        }
        try { v.vibrate(effect) } catch (e: Exception) { Log.w(TAG, "vibrate failed: ${e.message}") }
    }

    private fun currentVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun startInForeground(tier: ThreatLevel, topEvent: DetectionEvent?) {
        val notification = buildNotification(tier, topEvent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the runtime type to cover every capability the
            // service uses. We declare both in the manifest; pass both here so
            // location-using sources (DeFlock, Citizen) keep working with the
            // screen off.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(tier: ThreatLevel, topEvent: DetectionEvent?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "TAILTRACE  •  ${tier.name}"
        val text = topEvent?.let { "${it.score}  •  ${it.label}" }
            ?: getString(R.string.notification_text)
        // Higher importance for ORANGE/RED so the system surfaces it more
        // aggressively (heads-up notification, etc.). The channel was created
        // with LOW; on supported versions this priority is best-effort.
        val priority = when (tier) {
            ThreatLevel.RED -> NotificationCompat.PRIORITY_HIGH
            ThreatLevel.ORANGE -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(priority)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Tracker alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "A tracker appears to be following you"
                    enableVibration(true)
                }
            )
        }
    }
}

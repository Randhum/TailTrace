package ch.swhizkid.tailtrace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import ch.swhizkid.tailtrace.data.settings.Settings
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.service.DetectionService
import ch.swhizkid.tailtrace.tracker.data.TrackerRepository
import ch.swhizkid.tailtrace.tracker.model.TrackerRow
import ch.swhizkid.tailtrace.tracker.ring.TrackerRinger
import ch.swhizkid.tailtrace.ui.FinderScreen
import ch.swhizkid.tailtrace.ui.LogScreen
import ch.swhizkid.tailtrace.ui.MainScreen
import ch.swhizkid.tailtrace.ui.SettingsScreen
import ch.swhizkid.tailtrace.ui.SignatureCatalogScreen
import ch.swhizkid.tailtrace.ui.TrackerHistoryScreen
import ch.swhizkid.tailtrace.ui.TrackerSafetyScreen
import ch.swhizkid.tailtrace.ui.TrackerWatchScreen
import ch.swhizkid.tailtrace.ui.theme.OverwatchTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            // Location is needed pre-S for BLE, pre-T for WiFi scan results,
            // and for Phase 3 DeFlock proximity.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.all { it.value }
        permissionsGranted.value = allGranted
        permanentlyDenied.value = !allGranted && !anyMissingCanStillAsk()
        if (allGranted) {
            // First-run path: user just granted everything, kick off scanning
            // immediately so they don't have to tap START a second time.
            DetectionService.start(this)
        }
    }

    private val permissionsGranted = mutableStateOf(false)
    /** True when at least one required permission is denied AND the system says
     *  we can no longer prompt for it (user picked "don't ask again"). The UI
     *  swaps the START button's call-to-action for an "Open app settings" link. */
    private val permanentlyDenied = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // osmdroid requires a User-Agent and a writable cache before any
        // MapView is constructed, otherwise OSM may rate-limit/IP-ban us.
        // Set it here once per process — Configuration is a singleton.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid-tiles").apply { mkdirs() }
        }
        permissionsGranted.value = checkAllPermissions()
        permanentlyDenied.value = false  // reset on activity create
        val settings = Settings.get(this)
        val trackerRepo = TrackerRepository.get(this)
        lifecycleScope.launch { trackerRepo.refresh() }

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            OverwatchTheme(mode = themeMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

                when (screen) {
                    Screen.MAIN -> {
                        val running by DetectionService.running.collectAsState()
                        val events by DetectionService.store.events.collectAsState()
                        val threat by DetectionService.store.threatLevel.collectAsState()
                        val maxScore by DetectionService.store.maxScore.collectAsState()
                        val mapPoints by DetectionService.mapPoints.collectAsState()
                        val userLocation by DetectionService.location.collectAsState()
                        // Visible map radius = max of the two proximity sliders
                        // so the user sees the full area where a detection
                        // can fire. Using the raw setting values regardless of
                        // enabled-state keeps the visualization stable when a
                        // source is briefly toggled.
                        val osmProx by settings.osmProximityM.collectAsState()
                        val wazeProx by settings.wazeProximityM.collectAsState()
                        val mapRadiusM = maxOf(osmProx, wazeProx).toFloat()
                        val granted by permissionsGranted
                        val denied by permanentlyDenied

                        val message = when {
                            granted -> null
                            denied -> "Permissions permanently denied — open app settings to grant"
                            else -> "Tap START to grant Bluetooth, WiFi, location + phone permissions"
                        }

                        MainScreen(
                            running = running,
                            threat = threat,
                            score = maxScore,
                            events = events,
                            mapPoints = mapPoints,
                            userLocation = userLocation,
                            mapRadiusMeters = mapRadiusM,
                            canStart = true,
                            permissionMessage = message,
                            showOpenAppSettings = denied && !granted,
                            onOpenAppSettings = { openAppSettings() },
                            onStartStop = {
                                if (running) {
                                    DetectionService.stop(this@MainActivity)
                                } else {
                                    if (granted) {
                                        DetectionService.start(this@MainActivity)
                                    } else if (denied) {
                                        openAppSettings()
                                    } else {
                                        permissionLauncher.launch(requiredPermissions)
                                    }
                                }
                            },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenCatalog = { screen = Screen.CATALOG },
                            onOpenWatch = { screen = Screen.WATCH },
                            onOpenFinder = { screen = Screen.FINDER },
                            onOpenLogs = { screen = Screen.LOGS }
                        )
                    }
                    Screen.CATALOG -> {
                        SignatureCatalogScreen(onBack = { screen = Screen.MAIN })
                    }
                    Screen.FINDER -> {
                        FinderScreen(onBack = { screen = Screen.MAIN })
                    }
                    Screen.WATCH -> {
                        val running by DetectionService.running.collectAsState()
                        val trackers by trackerRepo.trackers.collectAsState()
                        val sensitivity by settings.trackerSensitivity.collectAsState()
                        TrackerWatchScreen(
                            running = running,
                            trackers = trackers,
                            sensitivity = sensitivity,
                            onSetSensitivity = { settings.setTrackerSensitivity(it) },
                            onApprove = { id, approved ->
                                lifecycleScope.launch {
                                    trackerRepo.setApproved(id, approved)
                                    if (approved) DetectionService.store.remove(DetectionSource.TRACKER, id)
                                }
                            },
                            onDistrust = { id ->
                                lifecycleScope.launch { trackerRepo.clearBaseline(id) }
                            },
                            onRing = { row -> ringTracker(row) },
                            onClearAll = { lifecycleScope.launch { trackerRepo.clearAll() } },
                            onOpenSafety = { screen = Screen.WATCH_SAFETY },
                            onOpenHistory = { screen = Screen.WATCH_HISTORY },
                            loadTrail = { id -> trackerRepo.trailFor(id) },
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                    Screen.WATCH_HISTORY -> {
                        val alerts by trackerRepo.alerts.collectAsState()
                        TrackerHistoryScreen(
                            alerts = alerts,
                            onExport = { exportEvidence(trackerRepo) },
                            onClear = { lifecycleScope.launch { trackerRepo.clearAlerts() } },
                            onBack = { screen = Screen.WATCH }
                        )
                    }
                    Screen.WATCH_SAFETY -> {
                        TrackerSafetyScreen(onBack = { screen = Screen.WATCH })
                    }
                    Screen.SETTINGS -> {
                        // Route system back into MAIN instead of letting the
                        // activity finish — the screen enum is internal to
                        // Compose and the OS doesn't know about it.
                        BackHandler { screen = Screen.MAIN }
                        val running by DetectionService.running.collectAsState()
                        SettingsScreen(
                            settings = settings,
                            isRunning = running,
                            onRestart = {
                                DetectionService.stop(this@MainActivity)
                                DetectionService.start(this@MainActivity)
                            },
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                    Screen.LOGS -> {
                        LogScreen(onBack = { screen = Screen.MAIN })
                    }
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have granted permissions in app settings while we were paused.
        val nowGranted = checkAllPermissions()
        permissionsGranted.value = nowGranted
        if (nowGranted) permanentlyDenied.value = false
    }

    private fun checkAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** True if at least one missing permission is still askable via the system
     *  prompt. False means everything missing was denied with "don't ask again". */
    private fun anyMissingCanStillAsk(): Boolean {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        return missing.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
    }

    private fun openAppSettings() {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun ringTracker(tracker: TrackerRow) {
        Toast.makeText(this, "Trying to ring ${tracker.label}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = TrackerRinger.ring(this@MainActivity, tracker.lastMac, tracker.ecosystem)
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportEvidence(repo: TrackerRepository) {
        lifecycleScope.launch {
            val report = repo.buildTextReport()
            if (report == null) {
                Toast.makeText(this@MainActivity, "No alerts to export yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val txt = File(dir, "tailtrace-report.txt").apply { writeText(report) }
                val gpx = File(dir, "tailtrace-track.gpx").apply { writeText(repo.buildGpx()) }
                val auth = "$packageName.fileprovider"
                val uris = arrayListOf(
                    FileProvider.getUriForFile(this@MainActivity, auth, txt),
                    FileProvider.getUriForFile(this@MainActivity, auth, gpx)
                )
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Export tracker evidence"))
            }.onFailure {
                Toast.makeText(this@MainActivity, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private enum class Screen { MAIN, SETTINGS, CATALOG, FINDER, WATCH, WATCH_HISTORY, WATCH_SAFETY, LOGS }
}

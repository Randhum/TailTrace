package ch.swhizkid.tailtrace.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ch.swhizkid.tailtrace.tracker.model.Sensitivity

/**
 * App-wide user preferences. Backed by SharedPreferences (no DataStore dep).
 *
 * Each preference is exposed as a [StateFlow] for Compose to observe; mutators
 * write through to disk and update the flow synchronously.
 *
 * Per-source toggles only take effect at the next Start cycle — flipping a
 * source while scanning will NOT live-restart that scanner.
 */
class Settings private constructor(
    private val prefs: SharedPreferences,
    private val appContext: Context
) {

    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _wifiEnabled = MutableStateFlow(prefs.getBoolean(KEY_WIFI, true))
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _osmEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_OSM, prefs.getBoolean("src_deflock", true))
    )
    val osmEnabled: StateFlow<Boolean> = _osmEnabled.asStateFlow()

    private val _wazeEnabled = MutableStateFlow(prefs.getBoolean(KEY_WAZE, true))
    val wazeEnabled: StateFlow<Boolean> = _wazeEnabled.asStateFlow()

    private val _aircraftEnabled = MutableStateFlow(prefs.getBoolean(KEY_AIRCRAFT, true))
    val aircraftEnabled: StateFlow<Boolean> = _aircraftEnabled.asStateFlow()

    private val _micEnabled = MutableStateFlow(prefs.getBoolean(KEY_MIC, true))
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _cellEnabled = MutableStateFlow(prefs.getBoolean(KEY_CELL, true))
    val cellEnabled: StateFlow<Boolean> = _cellEnabled.asStateFlow()

    private val _trackerEnabled = MutableStateFlow(prefs.getBoolean(KEY_TRACKER, true))
    val trackerEnabled: StateFlow<Boolean> = _trackerEnabled.asStateFlow()

    private val _trackerSensitivity = MutableStateFlow(
        runCatching { Sensitivity.valueOf(prefs.getString(KEY_TRACKER_SENS, null) ?: "") }
            .getOrDefault(Sensitivity.MEDIUM)
    )
    val trackerSensitivity: StateFlow<Sensitivity> = _trackerSensitivity.asStateFlow()

    // Priv-app extras (no root): lift the WiFi scan throttle via
    // WRITE_SECURE_SETTINGS at scan start. No-op when the priv-app
    // allowlist hasn't granted the permission. (Key name is historic.)
    private val _rootEnabled = MutableStateFlow(prefs.getBoolean(KEY_ROOT, true))
    val rootEnabled: StateFlow<Boolean> = _rootEnabled.asStateFlow()

    // RF-silent: the app itself emits nothing. BLE drops to opportunistic
    // (piggybacks on system/other-app scans, no SCAN_REQ of our own), WiFi
    // stops calling startScan (consumes system-triggered results only), and
    // the BT Finder skips classic inquiry. Slower detection; zero app TX.
    private val _rfSilent = MutableStateFlow(prefs.getBoolean(KEY_RF_SILENT, false))
    val rfSilent: StateFlow<Boolean> = _rfSilent.asStateFlow()

    private val _osmProximityM = MutableStateFlow(
        prefs.getInt(KEY_OSM_PROX, prefs.getInt("deflock_proximity_m", DEFAULT_OSM_PROX))
    )
    val osmProximityM: StateFlow<Int> = _osmProximityM.asStateFlow()

    private val _wazeProximityM = MutableStateFlow(
        prefs.getInt(KEY_WAZE_PROX, DEFAULT_WAZE_PROX)
    )
    val wazeProximityM: StateFlow<Int> = _wazeProximityM.asStateFlow()

    // Shared secret the app presents to the api.blackflagintel.com proxy, which
    // holds the real OpenWeb Ninja key server-side. Stored encrypted (Keystore),
    // never baked into the APK, so a published build carries no usable credential.
    private val _wazeProxyToken = MutableStateFlow(SecureStore.get(appContext, KEY_WAZE_TOKEN) ?: "")
    val wazeProxyToken: StateFlow<String> = _wazeProxyToken.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _vibrateOnAlert = MutableStateFlow(prefs.getBoolean(KEY_VIBRATE, true))
    val vibrateOnAlert: StateFlow<Boolean> = _vibrateOnAlert.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY, false))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    fun setBleEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_BLE, v) }; _bleEnabled.value = v }
    fun setWifiEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WIFI, v) }; _wifiEnabled.value = v }
    fun setOsmEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_OSM, v) }; _osmEnabled.value = v }
    fun setWazeEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WAZE, v) }; _wazeEnabled.value = v }
    fun setAircraftEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_AIRCRAFT, v) }; _aircraftEnabled.value = v }
    fun setMicEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_MIC, v) }; _micEnabled.value = v }
    fun setCellEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_CELL, v) }; _cellEnabled.value = v }
    fun setTrackerEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_TRACKER, v) }; _trackerEnabled.value = v }
    fun setTrackerSensitivity(v: Sensitivity) {
        prefs.edit { putString(KEY_TRACKER_SENS, v.name) }
        _trackerSensitivity.value = v
    }
    fun setRootEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_ROOT, v) }; _rootEnabled.value = v }
    fun setRfSilent(v: Boolean) { prefs.edit { putBoolean(KEY_RF_SILENT, v) }; _rfSilent.value = v }

    fun setOsmProximityM(v: Int) {
        val clamped = v.coerceIn(50, 1600)
        prefs.edit { putInt(KEY_OSM_PROX, clamped) }
        _osmProximityM.value = clamped
    }

    fun setWazeProximityM(v: Int) {
        val clamped = v.coerceIn(100, 5000)
        prefs.edit { putInt(KEY_WAZE_PROX, clamped) }
        _wazeProximityM.value = clamped
    }

    fun setWazeProxyToken(v: String) {
        val t = v.trim()
        SecureStore.put(appContext, KEY_WAZE_TOKEN, t)
        _wazeProxyToken.value = t
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    fun setVibrateOnAlert(v: Boolean) {
        prefs.edit { putBoolean(KEY_VIBRATE, v) }
        _vibrateOnAlert.value = v
    }

    fun setOverlayEnabled(v: Boolean) {
        prefs.edit { putBoolean(KEY_OVERLAY, v) }
        _overlayEnabled.value = v
    }

    companion object {
        private const val PREFS = "overwatch_settings"
        private const val KEY_BLE = "src_ble"
        private const val KEY_WIFI = "src_wifi"
        private const val KEY_OSM = "src_osm"
        private const val KEY_WAZE = "src_waze"
        private const val KEY_AIRCRAFT = "src_aircraft"
        private const val KEY_MIC = "src_mic"
        private const val KEY_CELL = "src_cell"
        private const val KEY_TRACKER = "src_tracker"
        private const val KEY_TRACKER_SENS = "tracker_sensitivity"
        private const val KEY_ROOT = "root_enhancements"
        private const val KEY_RF_SILENT = "rf_silent"
        private const val KEY_OSM_PROX = "osm_proximity_m"
        private const val KEY_WAZE_PROX = "waze_proximity_m"
        private const val KEY_WAZE_TOKEN = "waze_proxy_token"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_VIBRATE = "vibrate_on_alert"
        private const val KEY_OVERLAY = "overlay_enabled"

        const val DEFAULT_OSM_PROX = 200
        const val DEFAULT_WAZE_PROX = 500

        @Volatile private var INSTANCE: Settings? = null

        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                context.applicationContext
            ).also { INSTANCE = it }
        }
    }
}

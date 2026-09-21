package ch.swhizkid.tailtrace.root

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ch.swhizkid.tailtrace.util.AppLog

/**
 * One-shot enhancements applied at scan start. **No root and no `su`** — everything here runs on the app's own uid with permissions the
 * priv-app allowlist grants (target: manual /system/priv-app install).
 *
 *  - **WiFi scan throttle off** — stock Android caps foreground apps at
 *    4 scans / 2 min. WRITE_SECURE_SETTINGS (priv-app allowlist) lets us
 *    clear the global toggle, the same switch as Developer options →
 *    "Wi-Fi scan throttling". Verified via [WifiManager.isScanThrottleEnabled].
 *  - **Doze whitelist** — checked, not forced: there is no silent API for
 *    it even from priv-app. If the app is not exempt we log the hint; long
 *    passive runs may be deferred in deep doze until the user whitelists
 *    TailTrace in battery settings.
 *  - **BLE location denylist** — needs WRITE_DEVICE_CONFIG (signature, not
 *    privileged), so we can't touch it. On degoogled LineageOS there is no
 *    GMS to push the list, so it is unset and nothing is filtered — see the
 *    AndroidManifest comment on BLUETOOTH_SCAN.
 */
object PrivEnhancer {

    private const val TAG = "PrivEnhancer"

    data class Status(
        val throttleDisabled: Boolean = false,
        val dozeWhitelisted: Boolean = false
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    suspend fun apply(context: Context) = withContext(Dispatchers.IO) {
        val throttleOff = disableThrottle(context)
        val dozeOk = isDozeWhitelisted(context)
        if (!dozeOk) {
            AppLog.i(
                TAG,
                "not on the Doze whitelist — deep doze may defer scans; " +
                    "exempt TailTrace under Settings → Battery if needed"
            )
        }
        _status.value = Status(throttleDisabled = throttleOff, dozeWhitelisted = dozeOk)
        AppLog.i(TAG, "applied: throttleDisabled=$throttleOff dozeWhitelisted=$dozeOk")
    }

    private fun disableThrottle(context: Context): Boolean {
        val written = try {
            AndroidSettings.Global.putInt(
                context.contentResolver,
                "wifi_scan_throttle_enabled",
                0
            )
        } catch (e: SecurityException) {
            AppLog.w(TAG, "WRITE_SECURE_SETTINGS missing: ${e.message}")
            false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mgr = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            mgr?.isScanThrottleEnabled == false
        } else {
            written
        }
    }

    private fun isDozeWhitelisted(context: Context): Boolean {
        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

package ch.swhizkid.tailtrace.scan

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ch.swhizkid.tailtrace.util.SystemState

/**
 * Who is allowed to see Wi-Fi scan results with Location services off.
 *
 * [WifiManager.getScanResults] goes through WifiPermissionsUtil.
 * Location mode is required unless the caller holds one of the bypasses
 * (NETWORK_SETTINGS, RADIO_SCAN_WITHOUT_LOCATION, …). neverForLocation on
 * NEARBY_WIFI_DEVICES does **not** skip that check — unlike BLE.
 *
 * **Every bypass is signature-level** (verified in AOSP core res manifest:
 * NETWORK_SETTINGS is protectionLevel="signature", not privileged), so the
 * priv-app allowlist cannot grant any of them — only a platform-signed APK
 * gets one. We still check at runtime so a platform-signed build works,
 * but on a normal priv-app install Wi-Fi scanning needs Location services
 * ON. There is no root/`su` fallback; the app runs standalone.
 */
internal object WifiScanAccess {

    fun hasPrivilegedBypass(context: Context): Boolean {
        if (granted(context, "android.permission.NETWORK_SETTINGS")) return true
        return granted(context, "android.permission.RADIO_SCAN_WITHOUT_LOCATION")
    }

    /** Framework getScanResults/startScan will actually return APs. */
    fun frameworkCanReturnResults(context: Context): Boolean =
        SystemState.isLocationEnabled(context) || hasPrivilegedBypass(context)

    /**
     * Why Location-off Wi-Fi is empty, or null when Location is on or a
     * signature bypass is (exceptionally) granted.
     */
    fun locationOffHint(context: Context): String? {
        if (SystemState.isLocationEnabled(context)) return null
        if (hasPrivilegedBypass(context)) return null
        return "Location off — Android withholds Wi-Fi scans. Turn Location " +
            "services on (GPS use is unchanged; TailTrace transmits nothing)."
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}

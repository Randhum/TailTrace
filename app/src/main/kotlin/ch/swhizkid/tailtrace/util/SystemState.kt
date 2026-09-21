package ch.swhizkid.tailtrace.util

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/** Shared reads of system toggles the scanners have to reason about. */
object SystemState {

    /**
     * Location services master switch. Wi-Fi scan results are withheld
     * unless Location is on or the app holds NETWORK_SETTINGS (priv-app
     * allowlist — see WifiScanAccess). BLE is unaffected: BLUETOOTH_SCAN
     * is declared neverForLocation.
     *
     * Returns true when LocationManager is unavailable — fail open rather
     * than block scanning on a read we couldn't make.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }
}

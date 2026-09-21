package ch.swhizkid.tailtrace.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import ch.swhizkid.tailtrace.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Framework-only location source ([LocationManager]) — deliberately NO Google
 * Play Services / FusedLocationProviderClient dependency. On degoogled
 * LineageOS there is no GMS to talk to anyway, and location fixes here come
 * exclusively from the device (GPS hardware, and the platform's own fused /
 * network providers where present); nothing leaves the device.
 *
 * Registers on every enabled provider plus PASSIVE_PROVIDER (free fixes that
 * piggyback other apps' requests — in keeping with the app's passive posture).
 * The latest fix from any provider wins.
 *
 * Requires Location services ON to get fixes. When the user runs with
 * Location off, this flow simply stays null — BLE (neverForLocation) and
 * WiFi (NETWORK_SETTINGS priv-app bypass, or the root shell) scanning keep
 * working; geotagging and the OSM/Waze proximity features degrade gracefully.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val INTERVAL_MS = 15_000L
    }

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    @Volatile private var running = false
    private var listener: LocationListener? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            AppLog.w(TAG, "ACCESS_FINE_LOCATION not granted")
            return false
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            AppLog.w(TAG, "LocationManager unavailable")
            return false
        }
        running = true
        val l = LocationListener { loc ->
            if (running) _location.value = loc
        }
        listener = l

        val candidates = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Platform fused provider (framework, not GMS) exists on 12+.
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.PASSIVE_PROVIDER)
        }
        var started = 0
        for (provider in candidates) {
            if (provider !in lm.allProviders) continue
            if (provider != LocationManager.PASSIVE_PROVIDER &&
                !lm.isProviderEnabled(provider)
            ) continue
            try {
                lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, l, Looper.getMainLooper())
                started++
                if (_location.value == null) {
                    lm.getLastKnownLocation(provider)?.let { _location.value = it }
                }
            } catch (e: SecurityException) {
                AppLog.e(TAG, "$provider denied", e)
            } catch (e: Exception) {
                AppLog.w(TAG, "$provider failed: ${e.message}")
            }
        }
        AppLog.i(
            TAG,
            "framework location: $started providers registered " +
                "(gps=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)})"
        )
        if (started == 0) {
            AppLog.w(TAG, "no location providers available — Location services off?")
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        listener?.let { l ->
            try {
                (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                    ?.removeUpdates(l)
            } catch (_: Exception) { }
        }
        listener = null
        _location.value = null
        AppLog.i(TAG, "Location updates stopped")
    }
}

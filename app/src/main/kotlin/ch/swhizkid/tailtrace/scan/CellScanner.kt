package ch.swhizkid.tailtrace.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentity
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ch.swhizkid.tailtrace.util.AppLog
import ch.swhizkid.tailtrace.data.location.LocationProvider
import ch.swhizkid.tailtrace.fusion.DetectionEvent
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.fusion.DetectionStore
import ch.swhizkid.tailtrace.fusion.ImsiCatcherEngine
import ch.swhizkid.tailtrace.fusion.SourceHealth

/**
 * Polls the serving cell and neighbours. Scores IMSI-catcher heuristics
 * via [ImsiCatcherEngine]. Does not transmit.
 *
 * When the app holds READ_PRECISE_PHONE_STATE (priv-app install via the
 * Magisk module — signature|privileged, never granted to a sideloaded APK)
 * it additionally listens for registration/TAU/LAU rejects. The 3GPP cause
 * code feeds [ImsiCatcherEngine.ServingCell.recentRejectCause] for
 * [REJECT_WINDOW_MS] after the event — identity rejects (IMSI unknown,
 * illegal MS/ME) are a classic catcher fingerprint.
 */
class CellScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val locationProvider: LocationProvider
) {

    companion object {
        private const val TAG = "CellScanner"
        private const val POLL_MS = 12_000L
        private const val ALARM_THRESHOLD = 40
        private const val REJECT_WINDOW_MS = 10 * 60_000L
    }

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var job: Job? = null
    private var sawLteOrNr = false

    /** (causeCode, wall-clock ms) of the last registration reject, if any. */
    @Volatile
    private var lastReject: Pair<Int, Long>? = null
    private var rejectCallback: TelephonyCallback? = null

    fun hasPermission(): Boolean {
        val loc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        return loc && phone
    }

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        if (!hasPermission()) {
            SourceHealth.record(
                DetectionSource.CELL, ok = false,
                message = "Needs location + phone permission"
            )
            return false
        }
        if (telephony == null) {
            SourceHealth.record(DetectionSource.CELL, ok = false, message = "No TelephonyManager")
            return false
        }
        job = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
        registerRejectListener()
        SourceHealth.record(DetectionSource.CELL, ok = true)
        AppLog.i(TAG, "CellScanner started (rejectListener=${rejectCallback != null})")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        unregisterRejectListener()
        AppLog.i(TAG, "CellScanner stopped")
    }

    /** Granted only when installed as priv-app with the allowlist XML. */
    private fun hasPreciseState(): Boolean = ContextCompat.checkSelfPermission(
        context, "android.permission.READ_PRECISE_PHONE_STATE"
    ) == PackageManager.PERMISSION_GRANTED

    private fun registerRejectListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!hasPreciseState()) {
            AppLog.i(TAG, "READ_PRECISE_PHONE_STATE not granted — reject listener off (sideload install?)")
            return
        }
        val tm = telephony ?: return
        val cb = object : TelephonyCallback(), TelephonyCallback.RegistrationFailedListener {
            override fun onRegistrationFailed(
                cellIdentity: CellIdentity,
                chosenPlmn: String,
                domain: Int,
                causeCode: Int,
                additionalCauseCode: Int
            ) {
                AppLog.w(TAG, "registration failed: plmn=$chosenPlmn cause=$causeCode add=$additionalCauseCode")
                lastReject = causeCode to System.currentTimeMillis()
                poll() // fold the reject into a fresh score immediately
            }
        }
        try {
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            rejectCallback = cb
        } catch (e: SecurityException) {
            AppLog.w(TAG, "reject listener rejected by framework: ${e.message}")
        }
    }

    private fun unregisterRejectListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val cb = rejectCallback ?: return
        rejectCallback = null
        try {
            telephony?.unregisterTelephonyCallback(cb)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun recentRejectCause(): Int? {
        val (cause, at) = lastReject ?: return null
        return if (System.currentTimeMillis() - at <= REJECT_WINDOW_MS) cause else null
    }

    @SuppressLint("MissingPermission")
    private fun poll() {
        val tm = telephony ?: return
        val cells: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            SourceHealth.record(DetectionSource.CELL, ok = false, message = "Permission revoked")
            return
        }
        val registered = cells.filter { it.isRegistered }
        if (registered.isEmpty()) {
            store.clearSource(DetectionSource.CELL)
            return
        }
        val serving = registered.first()
        val snapshot = toServing(serving, neighborCount = (cells.size - 1).coerceAtLeast(0))
        if (snapshot.rat == ImsiCatcherEngine.Rat.LTE || snapshot.rat == ImsiCatcherEngine.Rat.NR) {
            sawLteOrNr = true
        }
        val scored = ImsiCatcherEngine.score(
            snapshot.copy(
                previouslyLteOrNr = sawLteOrNr,
                recentRejectCause = recentRejectCause()
            )
        )
        if (scored.score >= ALARM_THRESHOLD) {
            store.submit(
                DetectionEvent(
                    source = DetectionSource.CELL,
                    key = cellKey(snapshot),
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = snapshot.dbm,
                    lat = snapshot.lat,
                    lon = snapshot.lon
                )
            )
        } else {
            store.clearSource(DetectionSource.CELL)
        }
    }

    private fun toServing(info: CellInfo, neighborCount: Int): ImsiCatcherEngine.ServingCell {
        val fix = locationProvider.location.value
        var mcc: Int? = null
        var mnc: Int? = null
        var tac: Long? = null
        var cid: Long? = null
        var pci: Int? = null
        var rat = ImsiCatcherEngine.Rat.UNKNOWN
        var dbm: Int? = null

        when (info) {
            is CellInfoGsm -> {
                rat = ImsiCatcherEngine.Rat.GSM
                val id = info.cellIdentity
                val mccStr = if (Build.VERSION.SDK_INT >= 28) id.mccString else null
                val mncStr = if (Build.VERSION.SDK_INT >= 28) id.mncString else null
                @Suppress("DEPRECATION")
                mcc = parseMcc(mccStr, id.mcc)
                @Suppress("DEPRECATION")
                mnc = parseMnc(mncStr, id.mnc)
                tac = id.lac.toLong()
                cid = id.cid.toLong()
                dbm = info.cellSignalStrength.dbm
            }
            is CellInfoWcdma -> {
                rat = ImsiCatcherEngine.Rat.UMTS
                val id = info.cellIdentity
                val mccStr = if (Build.VERSION.SDK_INT >= 28) id.mccString else null
                val mncStr = if (Build.VERSION.SDK_INT >= 28) id.mncString else null
                @Suppress("DEPRECATION")
                mcc = parseMcc(mccStr, id.mcc)
                @Suppress("DEPRECATION")
                mnc = parseMnc(mncStr, id.mnc)
                tac = id.lac.toLong()
                cid = id.cid.toLong()
                dbm = info.cellSignalStrength.dbm
            }
            is CellInfoLte -> {
                rat = ImsiCatcherEngine.Rat.LTE
                val id = info.cellIdentity
                val mccStr = if (Build.VERSION.SDK_INT >= 28) id.mccString else null
                val mncStr = if (Build.VERSION.SDK_INT >= 28) id.mncString else null
                @Suppress("DEPRECATION")
                mcc = parseMcc(mccStr, id.mcc)
                @Suppress("DEPRECATION")
                mnc = parseMnc(mncStr, id.mnc)
                tac = id.tac.toLong()
                cid = id.ci.toLong()
                pci = id.pci
                dbm = info.cellSignalStrength.dbm
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                    rat = ImsiCatcherEngine.Rat.NR
                    val id = info.cellIdentity
                    if (id is android.telephony.CellIdentityNr) {
                        mcc = id.mccString?.toIntOrNull()
                        mnc = id.mncString?.toIntOrNull()
                        tac = id.tac.toLong()
                        cid = id.nci
                        pci = id.pci
                    }
                    dbm = info.cellSignalStrength.dbm
                }
            }
        }
        return ImsiCatcherEngine.ServingCell(
            mcc = mcc,
            mnc = mnc,
            tacOrLac = tac,
            cellId = cid,
            pci = pci,
            rat = rat,
            dbm = dbm,
            neighborCount = neighborCount,
            lat = fix?.latitude,
            lon = fix?.longitude
        )
    }

    @Suppress("DEPRECATION")
    private fun parseMcc(str: String?, legacy: Int): Int? {
        str?.toIntOrNull()?.let { return it }
        return if (legacy in 200..999) legacy else null
    }

    @Suppress("DEPRECATION")
    private fun parseMnc(str: String?, legacy: Int): Int? {
        str?.toIntOrNull()?.let { return it }
        return if (legacy in 0..999) legacy else null
    }

    private fun cellKey(c: ImsiCatcherEngine.ServingCell): String =
        "cell:${c.mcc}-${c.mnc}-${c.tacOrLac}-${c.cellId}"
}

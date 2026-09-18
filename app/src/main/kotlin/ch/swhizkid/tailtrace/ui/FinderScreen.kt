package ch.swhizkid.tailtrace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.swhizkid.tailtrace.data.settings.Settings
import ch.swhizkid.tailtrace.scan.BtFinderScanner
import ch.swhizkid.tailtrace.scan.FinderBand
import ch.swhizkid.tailtrace.scan.FinderSignal
import ch.swhizkid.tailtrace.scan.WifiFinderScanner
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.delay

/**
 * Per-band UI timings, driven by how often each radio actually gives us a
 * sample: BLE adverts land several times a second, a Wi-Fi beacon RSSI only
 * once per scan cycle (~5 s rooted, up to 30 s throttled).
 */
private data class BandTiming(
    /** Seen within this window = "live"; older entries grey out and sink. */
    val liveWindowMs: Long,
    /** Silence beyond this = SIGNAL LOST in the meter. */
    val lostAfterMs: Long,
    /** How far back the closer/farther comparison reaches. */
    val trendWindowMs: Long
)

private fun timingFor(band: FinderBand): BandTiming = when (band) {
    // BLE/classic duty cycle is ~10 s listen + ~12 s inquiry. Keep rows live
    // across the opposite slot so they don't grey out mid-hunt.
    FinderBand.BLE -> BandTiming(liveWindowMs = 28_000, lostAfterMs = 22_000, trendWindowMs = 3_000)
    FinderBand.WIFI -> BandTiming(liveWindowMs = 90_000, lostAfterMs = 60_000, trendWindowMs = 15_000)
}

/**
 * RSSI mapped to a 0–100 % "warmth" scale. The top end differs per band: a
 * BLE tag at arm's length reads ≈−35 dBm, while an AP's beacon at the same
 * distance is far stronger because it transmits at orders more power.
 */
private fun proximityPercent(band: FinderBand, rssi: Double): Int {
    val floor = -95.0
    val ceiling = when (band) {
        FinderBand.BLE -> -35.0
        FinderBand.WIFI -> -25.0
    }
    return (((rssi - floor) / (ceiling - floor)) * 100.0).toInt().coerceIn(0, 100)
}

/**
 * Received power expected at 1 m — the anchor of the log-distance model.
 *
 * BLE: the advert's TX Power AD field is *radiated* power (dBm at 0 m), so
 * free-space loss at 1 m (≈41 dB at 2.4 GHz) has to come off it before it can
 * anchor a distance. Without that field we fall back to −59 dBm, the
 * calibrated iBeacon figure for a typical 0 dBm tag.
 *
 * Wi-Fi: a beacon carries no TX power we can trust, so we assume a consumer
 * AP at ≈−30 dBm at 1 m on 2.4 GHz and correct by 20·log10(f) for the higher
 * free-space loss on 5/6 GHz (≈7 dB at 5.5 GHz, ≈8.5 dB at 6.5 GHz).
 * A high-gain or PoE ceiling AP will read closer than it is.
 */
private fun referenceRssiAt1m(signal: FinderSignal): Double = when (signal.band) {
    FinderBand.BLE -> signal.txPower?.let { it - 41.0 } ?: -59.0
    FinderBand.WIFI -> -30.0 - 20.0 * log10((signal.frequencyMhz ?: 2437) / 2437.0)
}

/**
 * Log-distance path loss, n = 2.2 (indoor-ish). Rough by nature — body
 * shadowing alone swings 2.4 GHz by >10 dB — treat as an order of magnitude,
 * not a tape measure.
 */
private fun estimateMeters(signal: FinderSignal): Double =
    10.0.pow((referenceRssiAt1m(signal) - signal.smoothedRssi) / (10.0 * 2.2))

private fun signalColor(pct: Int): Color = when {
    pct >= 70 -> Color(0xFF4CAF50) // green — same palette family as ThreatColors
    pct >= 40 -> Color(0xFFFFC107) // amber
    else -> Color(0xFFF44336)      // red / weak
}

private fun placeholderName(band: FinderBand): String = when (band) {
    FinderBand.BLE -> "(no name)"
    FinderBand.WIFI -> "(hidden SSID)"
}

/**
 * Signal Finder: every emitter in the air on the selected band — Bluetooth
 * (BLE adverts + classic discovery) or Wi-Fi access points — with name,
 * vendor and live RSSI. Tapping a row opens a proximity meter to physically
 * walk the emitter down.
 */
@Composable
fun FinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val btScanner = remember { BtFinderScanner(context) }
    val wifiScanner = remember { WifiFinderScanner(context) }
    val settings = remember { Settings.get(context) }
    val rfSilent by settings.rfSilent.collectAsState()

    var band by remember { mutableStateOf(FinderBand.BLE) }
    var startFailed by remember { mutableStateOf(false) }

    // Foreground-only, one band at a time: the active scan (and especially
    // classic inquiry / Wi-Fi probe requests) stops the moment the app leaves
    // the foreground, and resumes on return. A backgrounded TailTrace must not
    // transmit — background listening is the DetectionService's job.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rfSilent, band) {
        val active = if (band == FinderBand.BLE) btScanner else wifiScanner
        fun startNow() {
            startFailed = !active.start(rfSilent = rfSilent)
        }
        // Keyed on band, so this effect also runs on a band switch — when the
        // lifecycle is already STARTED and no ON_START event is coming.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startNow()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startNow()
                Lifecycle.Event.ON_STOP -> active.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            active.stop()
        }
    }

    val btSignals by btScanner.signals.collectAsState()
    val wifiSignals by wifiScanner.signals.collectAsState()
    val btStatus by btScanner.status.collectAsState()
    val wifiStatus by wifiScanner.status.collectAsState()
    val signals = if (band == FinderBand.BLE) btSignals else wifiSignals
    val status = if (band == FinderBand.BLE) btStatus else wifiStatus

    var selectedKey by remember { mutableStateOf<String?>(null) }
    var selectedSnapshot by remember { mutableStateOf<FinderSignal?>(null) }
    LaunchedEffect(band) {
        selectedKey = null
        selectedSnapshot = null
    }

    // 1 s ticker so "Xs ago" ages and staleness re-render without new samples.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val live = selectedKey?.let { signals[it] }
    LaunchedEffect(live) {
        if (live != null) selectedSnapshot = live
    }
    val selectedSignal = live
        ?: selectedSnapshot?.takeIf { it.address == selectedKey && it.band == band }

    val btScanning by btScanner.scanning.collectAsState()
    val wifiScanning by wifiScanner.scanning.collectAsState()
    val activeScanning = if (band == FinderBand.BLE) btScanning else wifiScanning

    // List = survey (all radios this band can use). Meter = park every other
    // radio. Re-apply after start() because start resets hunt to survey.
    // Tags are a key: a seeded classic row that later hears a BLE advert
    // must switch from inquiry to an LE hunt.
    val huntTags = selectedSignal?.tags
    LaunchedEffect(selectedKey, band, activeScanning, huntTags) {
        if (!activeScanning) return@LaunchedEffect
        val signal = selectedKey?.let { key ->
            signals[key]
                ?: selectedSnapshot?.takeIf { it.address == key && it.band == band }
        }
        if (band == FinderBand.BLE) {
            btScanner.setHunt(signal)
            wifiScanner.setHunt(null)
        } else {
            wifiScanner.setHunt(signal)
            btScanner.setHunt(null)
        }
    }

    if (selectedSignal != null) {
        BackHandler {
            selectedKey = null
            selectedSnapshot = null
        }
        FinderPane(
            signal = selectedSignal,
            now = now,
            status = status,
            onBack = {
                selectedKey = null
                selectedSnapshot = null
            }
        )
    } else {
        BackHandler(onBack = onBack)
        SignalListPane(
            signals = signals.values.toList(),
            band = band,
            now = now,
            startFailed = startFailed,
            rfSilent = rfSilent,
            status = status,
            onBandChange = {
                selectedKey = null
                selectedSnapshot = null
                band = it
            },
            onBack = onBack,
            onOpen = { addr ->
                selectedKey = addr
                selectedSnapshot = signals[addr]
            }
        )
    }
}

@Composable
private fun BandToggle(
    band: FinderBand,
    onBandChange: (FinderBand) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (option in FinderBand.entries) {
            val active = option == band
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else Color.Transparent
                    )
                    .clickable(enabled = !active) { onBandChange(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option.label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SignalListPane(
    signals: List<FinderSignal>,
    band: FinderBand,
    now: Long,
    startFailed: Boolean,
    rfSilent: Boolean,
    status: String?,
    onBandChange: (FinderBand) -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit
) {
    val timing = timingFor(band)
    val sorted = remember(signals, now, band) {
        val (live, stale) = signals.partition { now - it.lastSeenMs <= timing.liveWindowMs }
        live.sortedByDescending { it.smoothedRssi } + stale.sortedByDescending { it.lastSeenMs }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "SIGNAL FINDER",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${sorted.count { now - it.lastSeenMs <= timing.liveWindowMs }} live / ${sorted.size}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        BandToggle(band = band, onBandChange = onBandChange)
        Spacer(Modifier.height(8.dp))

        if (startFailed) {
            Text(
                when (band) {
                    FinderBand.BLE ->
                        "Scan didn't start — check Bluetooth is on and scan permission is granted."
                    FinderBand.WIFI ->
                        "Scan didn't start — check Wi-Fi is on and scan permission is granted."
                },
                color = Color(0xFFF44336),
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        status?.let {
            Text(
                it,
                color = Color(0xFFFFC107),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Text(
            when {
                band == FinderBand.BLE && rfSilent ->
                    "RF-silent: passive listening only — no inquiry, no scan requests. " +
                        "Updates ride on system scans (sparse); non-advertising classic " +
                        "devices won't appear."
                band == FinderBand.BLE ->
                    "Classic inquiry runs first (~12 s), then BLE listen (~10 s), " +
                        "never overlapping. A classic-only module shows up in the " +
                        "inquiry half. Tap a row to hunt it — other radios pause " +
                        "until you come back to this list."
                rfSilent ->
                    "RF-silent: no probe requests — the list only refreshes when something " +
                        "else on the phone scans, so expect long gaps."
                else ->
                    "All Wi-Fi access points in range, strongest first. Tap one to hunt " +
                        "it — Bluetooth pauses until you come back to this list. Beacons " +
                        "update once per scan cycle, so the meter moves in steps. Android " +
                        "requires Location services ON for Wi-Fi scans."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sorted, key = { it.address }) { s ->
                SignalRow(s, live = now - s.lastSeenMs <= timing.liveWindowMs, now = now) {
                    onOpen(s.address)
                }
            }
        }
    }
}

@Composable
private fun SignalRow(
    s: FinderSignal,
    live: Boolean,
    now: Long,
    onClick: () -> Unit
) {
    val pct = proximityPercent(s.band, s.smoothedRssi)
    val dim = if (live) 1f else 0.45f
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = dim * 0.6f + 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.name ?: placeholderName(s.band),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                )
                Text(
                    s.address,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim)
                )
                val tags = buildList {
                    s.vendor?.let { add(it) }
                    addAll(s.tags)
                    if (s.randomized) add("random MAC")
                }
                Text(
                    tags.joinToString("  •  "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (live) "${s.rssi} dBm" else "${(now - s.lastSeenMs) / 1000}s ago",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (live) signalColor(pct) else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim)
                )
                Spacer(Modifier.height(4.dp))
                SignalBar(pct = if (live) pct else 0, width = 72.dp)
            }
        }
    }
}

@Composable
private fun SignalBar(pct: Int, width: Dp) {
    Box(
        Modifier
            .width(width)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(width * (pct / 100f))
                .clip(RoundedCornerShape(3.dp))
                .background(signalColor(pct))
        )
    }
}

/**
 * Hot/cold pane for one emitter. Big proximity percentage from the smoothed
 * RSSI, rough distance, and a closer/farther trend computed against the
 * smoothed value from one band-appropriate window ago.
 */
@Composable
private fun FinderPane(
    signal: FinderSignal,
    now: Long,
    status: String?,
    onBack: () -> Unit
) {
    val timing = timingFor(signal.band)

    // (timestamp, smoothedRssi) samples for the trend — updated as data lands.
    val history = remember(signal.address) { mutableListOf<Pair<Long, Double>>() }
    LaunchedEffect(signal.smoothedRssi, signal.lastSeenMs) {
        history.add(signal.lastSeenMs to signal.smoothedRssi)
        while (history.size > 60) history.removeAt(0)
    }

    val pct = proximityPercent(signal.band, signal.smoothedRssi)
    val meters = estimateMeters(signal)
    val ageS = (now - signal.lastSeenMs) / 1000
    val lost = now - signal.lastSeenMs > timing.lostAfterMs

    // Trend: newest smoothed value vs the sample nearest one window back.
    val reference = history
        .lastOrNull { it.first <= signal.lastSeenMs - timing.trendWindowMs }?.second
    val delta = reference?.let { signal.smoothedRssi - it }
    val (trendText, trendColor) = when {
        lost -> "SIGNAL LOST — retrace your steps" to Color(0xFFF44336)
        delta == null -> "sampling…" to MaterialTheme.colorScheme.onSurfaceVariant
        delta > 1.5 -> "GETTING CLOSER" to Color(0xFF4CAF50)
        delta < -1.5 -> "GETTING FARTHER" to Color(0xFFF44336)
        else -> "steady — keep moving" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    signal.name ?: placeholderName(signal.band),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    signal.address,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val meta = buildList {
            signal.vendor?.let { add(it) }
            addAll(signal.tags)
            if (signal.randomized) add("randomized MAC — may rotate away mid-search")
        }
        Text(
            meta.joinToString("  •  "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = if (status == null) 16.dp else 6.dp)
        )
        status?.let {
            Text(
                it,
                color = Color(0xFFFFC107),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
            )
        }

        Spacer(Modifier.weight(0.5f))

        // The meter.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(signalColor(if (lost) 0 else pct).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (lost) "—" else "$pct%",
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = signalColor(if (lost) 0 else pct)
                    )
                    Text(
                        if (lost) "last ${ageS}s ago"
                        else "%.0f dBm  •  ~%.1f m".format(signal.smoothedRssi, meters),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { if (lost) 0f else pct / 100f },
                color = signalColor(if (lost) 0 else pct),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text(
                trendText,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = trendColor,
                textAlign = TextAlign.Center
            )
            if (!lost && ageS >= 5) {
                Text(
                    "last sample ${ageS}s ago",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Distance is a rough estimate (path-loss model" +
                when (signal.band) {
                    FinderBand.BLE ->
                        if (signal.txPower == null) ", no TX power in advert" else ""
                    FinderBand.WIFI -> ", assumes a typical consumer AP's output"
                } +
                "). Your body blocks 2.4 GHz — hold the phone out and turn " +
                "slowly; the direction where % peaks is where it is. " +
                "Walls and metal reflect: expect the last few meters to need a sweep." +
                if (signal.band == FinderBand.WIFI) {
                    " A 5/6 GHz AP is directional and fades faster than 2.4 GHz — " +
                        "good for pinpointing, easy to lose behind a wall."
                } else {
                    ""
                },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

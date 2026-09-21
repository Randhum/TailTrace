@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.swhizkid.tailtrace.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.swhizkid.tailtrace.service.DetectionService
import ch.swhizkid.tailtrace.tracker.model.Sensitivity
import ch.swhizkid.tailtrace.tracker.model.TrackerEcosystem
import ch.swhizkid.tailtrace.tracker.model.TrackerRow
import ch.swhizkid.tailtrace.tracker.model.TrackerStatus
import ch.swhizkid.tailtrace.tracker.model.TrailPoint
import ch.swhizkid.tailtrace.ui.theme.ThreatColors

@Composable
fun TrackerWatchScreen(
    running: Boolean,
    trackers: List<TrackerRow>,
    sensitivity: Sensitivity,
    onSetSensitivity: (Sensitivity) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onRing: (TrackerRow) -> Unit,
    onClearAll: () -> Unit,
    onOpenSafety: () -> Unit,
    onOpenHistory: () -> Unit,
    loadTrail: suspend (String) -> List<TrailPoint>,
    onBack: () -> Unit
) {
    var detail by remember { mutableStateOf<TrackerRow?>(null) }
    var finding by remember { mutableStateOf<TrackerRow?>(null) }

    if (finding != null) {
        val dev = finding!!
        FinderPane(
            t = dev,
            onRing = onRing,
            onClose = { DetectionService.setFinderTarget(null); finding = null }
        )
        return
    }

    val now = System.currentTimeMillis()
    val active = trackers
        .filter { !isTrusted(it) && now - it.lastSeen < ACTIVE_WINDOW_MS }
        .sortedByDescending { riskRank(it) }
    val trusted = trackers.filter { isTrusted(it) }
    val alerting = active.count { statusOf(it) == TrackerStatus.ALERTING }
    val suspicious = active.count { statusOf(it) == TrackerStatus.SUSPICIOUS }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Watch list",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "Alert history")
            }
            IconButton(onClick = onOpenSafety) {
                Icon(Icons.Filled.HealthAndSafety, contentDescription = "Safety")
            }
            if (trackers.isNotEmpty()) {
                IconButton(onClick = onClearAll) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all trackers")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusHero(running, alerting, suspicious) }
            item { SensitivitySelector(sensitivity, onSetSensitivity) }
            item {
                SectionHeader(
                    if (active.isEmpty()) "Nothing following you" else "Active (${active.size})"
                )
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        if (running) "Listening… no trackers are moving with you."
                        else "Not scanning. Start from the main screen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(active, key = { it.stableId }) { t ->
                    TrackerCard(t, onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust)
                }
            }
            if (trusted.isNotEmpty()) {
                item { SectionHeader("Trusted (${trusted.size})") }
                items(trusted, key = { it.stableId }) { t ->
                    TrackerCard(t, onClick = { detail = t }, onApprove = onApprove, onDistrust = onDistrust)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    detail?.let { t ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            TrackerDetail(
                t,
                onApprove = { id, a -> onApprove(id, a); detail = null },
                onDistrust = { id -> onDistrust(id); detail = null },
                onFind = { dev -> DetectionService.setFinderTarget(dev.stableId); finding = dev; detail = null },
                onRing = onRing,
                onSafety = { onOpenSafety(); detail = null },
                loadTrail = loadTrail
            )
        }
    }
}

@Composable
private fun StatusHero(running: Boolean, alerting: Int, suspicious: Int) {
    data class Look(val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

    val look = when {
        !running -> Look("Idle", "Start scanning to watch for trackers.", Icons.Filled.Bluetooth, MaterialTheme.colorScheme.surfaceVariant)
        alerting > 0 -> Look(
            if (alerting == 1) "A tracker is following you" else "$alerting trackers following you",
            "Moving with you across places and time.", Icons.Filled.Warning, ThreatColors.Red
        )
        suspicious > 0 -> Look(
            "Keeping watch",
            "$suspicious possible follower(s) — not yet confirmed.", Icons.Filled.GppMaybe, ThreatColors.Orange
        )
        else -> Look("You're clear", "Nothing has been following you.", Icons.Filled.CheckCircle, ThreatColors.Green)
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = look.color)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(look.icon, null, tint = Color(0xFF111111), modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(16.dp))
            Column {
                Text(look.title, fontWeight = FontWeight.Bold, color = Color(0xFF111111), fontSize = 18.sp)
                Text(look.subtitle, color = Color(0xCC111111), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SensitivitySelector(current: Sensitivity, onSet: (Sensitivity) -> Unit) {
    Column {
        SectionHeader("Sensitivity")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s ->
                FilterChip(
                    selected = s == current,
                    onClick = { onSet(s) },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        Text(
            when (current) {
                Sensitivity.HIGH -> "Alerts fastest — more early warnings, more false alarms."
                Sensitivity.MEDIUM -> "Balanced — the recommended default."
                Sensitivity.LOW -> "Alerts only on strong, sustained evidence."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun TrackerCard(
    t: TrackerRow,
    onClick: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit
) {
    val status = statusOf(t)
    val accent = statusColor(status)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon(status), null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ecosystemDisplay(t.ecosystem), fontWeight = FontWeight.SemiBold)
                Text(
                    "${statusLabel(status)} · ${t.distinctPlaces} places · ${t.lastRssi} dBm · ${relative(t.lastSeen)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (status) {
                TrackerStatus.SAFE_APPROVED ->
                    TextButton(onClick = { onApprove(t.stableId, false) }) { Text("Undo") }
                TrackerStatus.SAFE_BASELINE ->
                    TextButton(onClick = { onDistrust(t.stableId) }) { Text("Not mine") }
                else -> TextButton(onClick = { onApprove(t.stableId, true) }) { Text("It's mine") }
            }
        }
    }
}

@Composable
private fun TrackerDetail(
    t: TrackerRow,
    onApprove: (String, Boolean) -> Unit,
    onDistrust: (String) -> Unit,
    onFind: (TrackerRow) -> Unit,
    onRing: (TrackerRow) -> Unit,
    onSafety: () -> Unit,
    loadTrail: suspend (String) -> List<TrailPoint>
) {
    val status = statusOf(t)
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(ecosystemDisplay(t.ecosystem), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(statusLabel(status), color = statusColor(status), fontWeight = FontWeight.SemiBold)
        if (status == TrackerStatus.ALERTING || status == TrackerStatus.SUSPICIOUS) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSafety,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ThreatColors.Red)
            ) {
                Icon(Icons.Filled.HealthAndSafety, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("What should I do?", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
        DetailRow("Signal (last / peak)", "${t.lastRssi} / ${t.peakRssi} dBm")
        DetailRow("Distinct places", t.distinctPlaces.toString())
        DetailRow("Co-movement sightings", t.effectiveSightings.toString())
        DetailRow("Adverts logged", t.sightingCount.toString())
        DetailRow("First seen", relative(t.firstSeen))
        DetailRow("Last seen", relative(t.lastSeen))
        if (t.anchorDayCount > 0) {
            DetailRow("Days at your places", "${t.anchorDayCount} / 3 to trust")
        }
        DetailRow("Identity", t.stableId.take(22) + "…")
        Spacer(Modifier.height(16.dp))
        Text(explanation(status), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val trail by produceState(initialValue = emptyList<TrailPoint>(), t.stableId) { value = loadTrail(t.stableId) }
        if (trail.size >= 2) {
            Spacer(Modifier.height(18.dp))
            Text("Where it's moved with you", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TrailView(
                trail,
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onFind(t) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Sensors, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Find it")
            }
            FilledTonalButton(onClick = { onRing(t) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Ring it")
            }
        }
        var showInfo by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showInfo = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Who owns it?")
        }
        if (showInfo) {
            AlertDialog(
                onDismissRequest = { showInfo = false },
                confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
                title = { Text("Who owns this tracker?") },
                text = {
                    Text(
                        "Tap the tracker against the top of your phone (NFC). If it's an AirTag or a DULT tag in lost mode, your phone opens a page with the owner's masked phone or email."
                    )
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        when (status) {
            TrackerStatus.SAFE_APPROVED ->
                Button(onClick = { onApprove(t.stableId, false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from approved")
                }
            TrackerStatus.SAFE_BASELINE ->
                Button(onClick = { onDistrust(t.stableId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Not mine — re-check this tracker")
                }
            else ->
                Button(onClick = { onApprove(t.stableId, true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("This is mine — stop alerting")
                }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
private fun FinderPane(t: TrackerRow, onRing: (TrackerRow) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val rssi by DetectionService.finderRssi.collectAsState()
    val smoothed = remember { mutableStateOf(-100f) }
    LaunchedEffect(rssi) { rssi?.let { smoothed.value = 0.35f * it + 0.65f * smoothed.value } }
    val hasSignal = rssi != null
    val p = ((smoothed.value + 100f) / 60f).coerceIn(0f, 1f)
    val label = when {
        !hasSignal -> "Searching… walk around"
        p > 0.8f -> "Right here"
        p > 0.6f -> "Very close"
        p > 0.4f -> "Close"
        p > 0.2f -> "Getting warmer"
        else -> "Far"
    }
    val color = lerp(ThreatColors.Red, ThreatColors.Green, p)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(ecosystemDisplay(t.ecosystem), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size((120 + p * 160).dp).clip(CircleShape).background(
                if (hasSignal) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Sensors, null,
                tint = if (hasSignal) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (hasSignal) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (hasSignal) "$rssi dBm" else "no signal yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        Button(onClick = { onRing(t) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.NotificationsActive, null)
            Spacer(Modifier.size(8.dp))
            Text("Make it ring")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Spacer(Modifier.height(12.dp))
        Text(
            "If it won't ring, it may be a silent or modified tracker — use the signal above to home in on it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrailView(points: List<TrailPoint>, modifier: Modifier = Modifier) {
    val line = Color(0xFF89B4FA)
    val startC = Color(0xFFA6E3A1)
    val endC = Color(0xFFFAB387)
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val pad = 18f
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val rLat = (maxLat - minLat).let { if (it > 1e-9) it else 1e-9 }
        val rLon = (maxLon - minLon).let { if (it > 1e-9) it else 1e-9 }
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val pts = points.map { p ->
            Offset(
                pad + ((p.lon - minLon) / rLon).toFloat() * w,
                pad + (1f - ((p.lat - minLat) / rLat).toFloat()) * h
            )
        }
        for (i in 0 until pts.size - 1) {
            drawLine(line.copy(alpha = 0.55f), pts[i], pts[i + 1], strokeWidth = 3f)
        }
        pts.forEachIndexed { i, o ->
            val c = if (i == 0) startC else if (i == pts.lastIndex) endC else line
            drawCircle(c, radius = if (i == 0 || i == pts.lastIndex) 6f else 4f, center = o)
        }
    }
}

private const val ACTIVE_WINDOW_MS = 10 * 60_000L

private fun isTrusted(t: TrackerRow) = t.approved || t.baselineSafe

private fun riskRank(t: TrackerRow): Int = when (statusOf(t)) {
    TrackerStatus.ALERTING -> 3
    TrackerStatus.SUSPICIOUS -> 2
    TrackerStatus.OBSERVED -> 1
    else -> 0
}

private fun statusOf(t: TrackerRow): TrackerStatus = when {
    t.approved -> TrackerStatus.SAFE_APPROVED
    t.baselineSafe -> TrackerStatus.SAFE_BASELINE
    else -> when (t.riskState) {
        "ALERTING" -> TrackerStatus.ALERTING
        "SUSPICIOUS" -> TrackerStatus.SUSPICIOUS
        else -> TrackerStatus.OBSERVED
    }
}

private fun statusColor(s: TrackerStatus): Color = when (s) {
    TrackerStatus.ALERTING -> ThreatColors.Red
    TrackerStatus.SUSPICIOUS -> ThreatColors.Orange
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> ThreatColors.Green
    TrackerStatus.OBSERVED -> Color(0xFF89B4FA)
}

private fun statusIcon(s: TrackerStatus): ImageVector = when (s) {
    TrackerStatus.ALERTING -> Icons.Filled.Warning
    TrackerStatus.SUSPICIOUS -> Icons.Filled.GppMaybe
    TrackerStatus.SAFE_APPROVED, TrackerStatus.SAFE_BASELINE -> Icons.Filled.Verified
    TrackerStatus.OBSERVED -> Icons.Filled.Bluetooth
}

private fun statusLabel(s: TrackerStatus): String = when (s) {
    TrackerStatus.SAFE_APPROVED -> "Approved (yours)"
    TrackerStatus.SAFE_BASELINE -> "Known (around home)"
    TrackerStatus.ALERTING -> "Following you"
    TrackerStatus.SUSPICIOUS -> "Watching"
    TrackerStatus.OBSERVED -> "Seen nearby"
}

private fun explanation(s: TrackerStatus): String = when (s) {
    TrackerStatus.ALERTING -> "This tracker has been close to you across several distinct places over a sustained window — the signature of something travelling with you. If it isn't yours, treat it seriously."
    TrackerStatus.SUSPICIOUS -> "Seen repeatedly and close, but not yet across enough places/time to confirm it's following you."
    TrackerStatus.OBSERVED -> "Seen nearby but with no co-movement pattern — most likely ambient."
    TrackerStatus.SAFE_APPROVED -> "You marked this as yours, so TailTrace won't alert on it."
    TrackerStatus.SAFE_BASELINE -> "Regularly around the places you live — learned as part of your normal environment, entirely on-device."
}

private fun ecosystemDisplay(name: String): String =
    runCatching { TrackerEcosystem.valueOf(name).display }.getOrDefault(name)

private fun relative(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

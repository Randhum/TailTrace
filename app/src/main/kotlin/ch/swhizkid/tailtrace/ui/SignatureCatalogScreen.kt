package ch.swhizkid.tailtrace.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import kotlinx.coroutines.launch
import ch.swhizkid.tailtrace.data.catalog.Entity
import ch.swhizkid.tailtrace.data.catalog.SignatureRepository
import ch.swhizkid.tailtrace.data.catalog.SignatureRow
import ch.swhizkid.tailtrace.data.catalog.TraitKind
import ch.swhizkid.tailtrace.data.catalog.TraitMatcher

private enum class CatalogFilter { ALL, UNASSIGNED, ASSIGNED, KNOWN }

@Composable
fun SignatureCatalogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SignatureRepository.get(context) }
    val scope = rememberCoroutineScope()
    val rows by repo.signatures.collectAsState()
    val entities by repo.entities.collectAsState()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = rows.firstOrNull { it.id == selectedId }

    LaunchedEffect(Unit) { repo.refresh() }

    if (selected != null) {
        BackHandler { selectedId = null }
        SignatureDetailPane(
            row = selected,
            entities = entities,
            repo = repo,
            onBack = { selectedId = null }
        )
    } else {
        BackHandler(onBack = onBack)
        SignatureListPane(
            rows = rows,
            onBack = onBack,
            onOpen = { selectedId = it.id },
            onRefresh = { scope.launch { repo.refresh() } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignatureListPane(
    rows: List<SignatureRow>,
    onBack: () -> Unit,
    onOpen: (SignatureRow) -> Unit,
    onRefresh: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CatalogFilter.ALL) }
    val visible = remember(rows, query, filter) {
        rows.filter { row ->
            val filterOk = when (filter) {
                CatalogFilter.ALL -> true
                CatalogFilter.UNASSIGNED -> row.entityId == null
                CatalogFilter.ASSIGNED -> row.entityId != null
                CatalogFilter.KNOWN -> !row.catalogHint.isNullOrBlank()
            }
            if (!filterOk) return@filter false
            if (query.isBlank()) return@filter true
            val q = query.trim()
            listOfNotNull(
                row.address, row.oui, row.advertisedName, row.ssid,
                row.companyIds, row.entityName, row.catalogHint
            ).any { it.contains(q, ignoreCase = true) }
        }
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
                text = "CATALOG",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Text(
            text = "${visible.size} of ${rows.size} signatures — assign an entity when you know the device",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search address, OUI, name, SSID, entity") }
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.name.lowercase()) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (visible.isEmpty()) {
            Text(
                text = if (rows.isEmpty()) {
                    "No signatures yet. Press START on the main screen — every BLE advert and WiFi AP is stored here."
                } else {
                    "Nothing matches this filter."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.id }) { row ->
                    SignatureCard(row = row, onClick = { onOpen(row) })
                }
            }
        }
    }
}

@Composable
private fun SignatureCard(row: SignatureRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.radio.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = row.entityName ?: "unassigned",
                    fontSize = 12.sp,
                    color = if (row.entityId == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            Text(
                text = row.address,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            val subtitle = listOfNotNull(
                row.advertisedName,
                row.ssid,
                row.catalogHint?.takeIf { row.entityName == null }
            ).distinct().joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "${row.hitCount} hits · last ${formatWhen(row.lastSeenMs)}" +
                    (row.lastRssi?.let { " · ${it} dBm" } ?: ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignatureDetailPane(
    row: SignatureRow,
    entities: List<Entity>,
    repo: SignatureRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var newName by rememberSaveable(row.id) { mutableStateOf("") }
    var newNotes by rememberSaveable(row.id) { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

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
                text = "SIGNATURE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 18.sp
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(row.address, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                DetailLine("Radio", row.radio.name)
                DetailLine("OUI", row.oui ?: "—")
                DetailLine("Name", row.advertisedName ?: "—")
                DetailLine("SSID", row.ssid ?: "—")
                DetailLine("Company IDs", row.companyIds ?: "—")
                DetailLine("Service UUIDs", row.serviceUuids ?: "—")
                DetailLine("Payload", row.payloadFingerprint ?: "—")
                DetailLine("Catalog hint", row.catalogHint ?: "none yet")
                DetailLine("Entity", row.entityName ?: "unassigned")
                DetailLine("Hits", row.hitCount.toString())
                DetailLine("First seen", formatWhen(row.firstSeenMs))
                DetailLine("Last seen", formatWhen(row.lastSeenMs))
                DetailLine("Last RSSI", row.lastRssi?.let { "$it dBm" } ?: "—")
                if (row.lastLat != null && row.lastLon != null) {
                    DetailLine("Last fix", "%.5f, %.5f".format(row.lastLat, row.lastLon))
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Assign this signature",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    "Pins this MAC/BSSID to an entity. Trait rules below apply the same label to every matching signature.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("New entity name") }
                )
                OutlinedTextField(
                    value = newNotes,
                    onValueChange = { newNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Notes (optional)") }
                )
                Button(
                    onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) {
                            status = "Name the entity first"
                            return@Button
                        }
                        scope.launch {
                            val id = repo.createEntity(name, notes = newNotes.trim().ifBlank { null })
                            repo.assignSignature(row.id, id)
                            status = "Assigned to $name"
                            newName = ""
                            newNotes = ""
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Create and assign") }
            }
            if (entities.isNotEmpty()) {
                item {
                    Text("Existing entities", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        entities.forEach { entity ->
                            FilterChip(
                                selected = row.entityId == entity.id,
                                onClick = {
                                    scope.launch {
                                        repo.assignSignature(row.id, entity.id)
                                        status = "Assigned to ${entity.name}"
                                    }
                                },
                                label = { Text(entity.name) }
                            )
                        }
                    }
                    if (row.entityId != null) {
                        TextButton(onClick = {
                            scope.launch {
                                repo.assignSignature(row.id, null)
                                status = "Unassigned"
                            }
                        }) { Text("Clear assignment") }
                    }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Remember this trait",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    "When you later learn what a company ID, OUI, UUID, or name means, bind it here. Unassigned signatures that carry the trait — including future ones — inherit the entity.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val traits = TraitMatcher.availableTraits(row.traits())
            if (traits.isEmpty()) {
                item {
                    Text(
                        "This packet has no reusable trait yet (no OUI / company ID / name / SSID).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(traits) { (kind, value) ->
                    TraitRuleRow(
                        kind = kind,
                        value = value,
                        enabled = row.entityId != null || newName.isNotBlank() || entities.isNotEmpty(),
                        onApply = {
                            scope.launch {
                                val id = row.entityId ?: run {
                                    val name = newName.trim()
                                    if (name.isEmpty()) {
                                        status = "Create or pick an entity first"
                                        return@launch
                                    }
                                    repo.createEntity(name, notes = newNotes.trim().ifBlank { null })
                                }
                                val n = repo.applyTraitRule(kind, value, id)
                                repo.assignSignature(row.id, id)
                                status = "Rule saved. $n existing signature(s) inherited it."
                            }
                        }
                    )
                }
            }
            if (status != null) {
                item {
                    Text(status!!, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TraitRuleRow(
    kind: TraitKind,
    value: String,
    enabled: Boolean,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                kind.name.lowercase().replace('_', ' '),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            TextButton(onClick = onApply, enabled = enabled) {
                Text("Apply trait to entity")
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(0.62f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun formatWhen(ms: Long): String {
    val ctx = LocalContext.current
    return DateFormat.getMediumDateFormat(ctx).format(Date(ms)) +
        " " + DateFormat.getTimeFormat(ctx).format(Date(ms))
}

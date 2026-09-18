package ch.swhizkid.tailtrace.data.catalog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persists every heard radio signature and lets the user attach an [Entity]
 * later — either to one row or to a trait that backfills matching rows.
 *
 * Instance assignment wins over trait rules. Rules only fill `entity_id IS NULL`.
 */
class SignatureRepository(
    private val dbHelper: CatalogDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val writeMutex = Mutex()
    private val lastIngestMs = HashMap<String, Long>()
    private val lastSightingMs = HashMap<Long, Long>()

    private val _signatures = MutableStateFlow<List<SignatureRow>>(emptyList())
    val signatures: StateFlow<List<SignatureRow>> = _signatures.asStateFlow()

    private val _entities = MutableStateFlow<List<Entity>>(emptyList())
    val entities: StateFlow<List<Entity>> = _entities.asStateFlow()

    private val _rules = MutableStateFlow<List<TraitRule>>(emptyList())
    val rules: StateFlow<List<TraitRule>> = _rules.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        writeMutex.withLock { reloadLocked() }
    }

    /**
     * Record one observation. Debounced per signature so a chatty advertiser
     * does not flood SQLite. Always bumps hit_count when the debounce accepts.
     */
    suspend fun ingest(obs: RadioObservation): Long? = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val traits = SignatureIdentity.traitsOf(obs)
            if (traits.address.isBlank()) return@withLock null
            val key = SignatureIdentity.key(traits.radio, traits.address)
            val now = obs.seenMs
            val last = lastIngestMs[key]
            if (last != null && now - last < INGEST_GAP_MS) return@withLock null
            lastIngestMs[key] = now

            val db = dbHelper.writableDatabase
            val existing = loadByKey(db, key)
            val rules = loadRules(db)
            val ruleEntity = TraitMatcher.firstMatch(rules, traits)?.entityId

            val id = if (existing == null) {
                insertSignature(db, key, traits, obs, ruleEntity)
            } else {
                updateSignature(db, existing, traits, obs, rules)
                existing.id
            }

            val sightingDue = lastSightingMs[id]?.let { now - it >= SIGHTING_GAP_MS } ?: true
            if (sightingDue) {
                insertSighting(db, id, obs)
                lastSightingMs[id] = now
            }
            reloadLocked()
            id
        }
    }

    suspend fun createEntity(name: String, kind: String = "custom", notes: String? = null): Long =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put("name", name.trim())
                    put("kind", kind.trim().ifBlank { "custom" })
                    put("notes", notes?.trim()?.ifBlank { null })
                    put("created_ms", nowMs())
                }
                val id = db.insert("entities", null, values)
                reloadLocked()
                id
            }
        }

    /** Pin this exact signature to an entity. Does not create a trait rule. */
    suspend fun assignSignature(signatureId: Long, entityId: Long?) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                if (entityId == null) putNull("entity_id") else put("entity_id", entityId)
            }
            db.update("signatures", values, "id = ?", arrayOf(signatureId.toString()))
            reloadLocked()
        }
    }

    /**
     * Bind a trait to an entity and backfill every unassigned signature that
     * carries it. Already-assigned rows keep their instance label.
     */
    suspend fun applyTraitRule(
        kind: TraitKind,
        value: String,
        entityId: Long
    ): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = dbHelper.writableDatabase
            val normalized = TraitMatcher.normalizeValue(kind, value)
            val values = ContentValues().apply {
                put("trait_kind", kind.name)
                put("trait_value", normalized)
                put("entity_id", entityId)
                put("created_ms", nowMs())
            }
            db.insertWithOnConflict("trait_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            val rule = TraitRule(
                id = 0,
                traitKind = kind,
                traitValue = normalized,
                entityId = entityId,
                createdMs = nowMs()
            )
            val unassigned = loadSignatures(db).filter { it.entityId == null }
            val ids = unassigned.filter { TraitMatcher.matches(rule, it.traits()) }.map { it.id }
            if (ids.isNotEmpty()) {
                val placeholders = ids.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE signatures SET entity_id = ? WHERE id IN ($placeholders)",
                    (listOf(entityId.toString()) + ids.map { it.toString() }).toTypedArray()
                )
            }
            reloadLocked()
            ids.size
        }
    }

    suspend fun deleteRule(ruleId: Long) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            dbHelper.writableDatabase.delete("trait_rules", "id = ?", arrayOf(ruleId.toString()))
            reloadLocked()
        }
    }

    suspend fun sightingsFor(signatureId: Long, limit: Int = 50): List<SightingRow> =
        withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery(
                """
                SELECT id, signature_id, seen_ms, rssi, lat, lon, score
                FROM sightings
                WHERE signature_id = ?
                ORDER BY seen_ms DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(signatureId.toString(), limit.toString())
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(readSighting(c))
                }
            }
        }

    private fun reloadLocked() {
        val db = dbHelper.readableDatabase
        _signatures.value = loadSignatures(db)
        _entities.value = loadEntities(db)
        _rules.value = loadRules(db)
    }

    private fun insertSignature(
        db: SQLiteDatabase,
        key: String,
        traits: SignatureTraits,
        obs: RadioObservation,
        ruleEntity: Long?
    ): Long {
        val values = ContentValues().apply {
            put("signature_key", key)
            put("radio", traits.radio.name)
            put("address", traits.address)
            put("oui", traits.oui)
            put("advertised_name", traits.advertisedName)
            put("company_ids", SignatureIdentity.companyIdsCsv(traits.companyIds))
            put("service_uuids", SignatureIdentity.uuidCsv(traits.serviceUuids))
            put("ssid", traits.ssid)
            put("payload_fingerprint", traits.payloadFingerprint)
            put("catalog_hint", obs.catalogHint)
            if (ruleEntity != null) put("entity_id", ruleEntity) else putNull("entity_id")
            put("first_seen_ms", obs.seenMs)
            put("last_seen_ms", obs.seenMs)
            put("hit_count", 1)
            if (obs.rssi != null) put("last_rssi", obs.rssi) else putNull("last_rssi")
            if (obs.lat != null) put("last_lat", obs.lat) else putNull("last_lat")
            if (obs.lon != null) put("last_lon", obs.lon) else putNull("last_lon")
            put("last_score", obs.score)
        }
        return db.insert("signatures", null, values)
    }

    private fun updateSignature(
        db: SQLiteDatabase,
        existing: SignatureRow,
        traits: SignatureTraits,
        obs: RadioObservation,
        rules: List<TraitRule>
    ) {
        val values = ContentValues().apply {
            put("last_seen_ms", obs.seenMs)
            put("hit_count", existing.hitCount + 1)
            if (obs.rssi != null) put("last_rssi", obs.rssi)
            if (obs.lat != null) put("last_lat", obs.lat)
            if (obs.lon != null) put("last_lon", obs.lon)
            put("last_score", obs.score)
            // Fill blanks as the radio reveals more; never wipe a known field.
            if (existing.advertisedName.isNullOrBlank() && traits.advertisedName != null) {
                put("advertised_name", traits.advertisedName)
            }
            if (existing.ssid.isNullOrBlank() && traits.ssid != null) {
                put("ssid", traits.ssid)
            }
            val mergedCompanies = (existing.traits().companyIds + traits.companyIds).distinct()
            put("company_ids", SignatureIdentity.companyIdsCsv(mergedCompanies))
            val mergedUuids = (existing.traits().serviceUuids + traits.serviceUuids).distinct()
            put("service_uuids", SignatureIdentity.uuidCsv(mergedUuids))
            if (existing.payloadFingerprint.isNullOrBlank() && traits.payloadFingerprint != null) {
                put("payload_fingerprint", traits.payloadFingerprint)
            }
            if (existing.catalogHint.isNullOrBlank() && !obs.catalogHint.isNullOrBlank()) {
                put("catalog_hint", obs.catalogHint)
            }
            if (existing.entityId == null) {
                val ruleEntity = TraitMatcher.firstMatch(rules, traits)?.entityId
                if (ruleEntity != null) put("entity_id", ruleEntity)
            }
        }
        db.update("signatures", values, "id = ?", arrayOf(existing.id.toString()))
    }

    private fun insertSighting(db: SQLiteDatabase, signatureId: Long, obs: RadioObservation) {
        val values = ContentValues().apply {
            put("signature_id", signatureId)
            put("seen_ms", obs.seenMs)
            if (obs.rssi != null) put("rssi", obs.rssi) else putNull("rssi")
            if (obs.lat != null) put("lat", obs.lat) else putNull("lat")
            if (obs.lon != null) put("lon", obs.lon) else putNull("lon")
            put("score", obs.score)
        }
        db.insert("sightings", null, values)
    }

    private fun loadByKey(db: SQLiteDatabase, key: String): SignatureRow? {
        db.rawQuery(SIGNATURE_SELECT + " WHERE s.signature_key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) readSignature(c) else null
        }
    }

    private fun loadSignatures(db: SQLiteDatabase): List<SignatureRow> {
        db.rawQuery(SIGNATURE_SELECT + " ORDER BY s.last_seen_ms DESC", null).use { c ->
            return buildList {
                while (c.moveToNext()) add(readSignature(c))
            }
        }
    }

    private fun loadEntities(db: SQLiteDatabase): List<Entity> {
        db.rawQuery(
            "SELECT id, name, kind, notes, created_ms FROM entities ORDER BY name COLLATE NOCASE",
            null
        ).use { c ->
            return buildList {
                while (c.moveToNext()) {
                    add(
                        Entity(
                            id = c.getLong(0),
                            name = c.getString(1),
                            kind = c.getString(2),
                            notes = c.getString(3),
                            createdMs = c.getLong(4)
                        )
                    )
                }
            }
        }
    }

    private fun loadRules(db: SQLiteDatabase): List<TraitRule> {
        db.rawQuery(
            "SELECT id, trait_kind, trait_value, entity_id, created_ms FROM trait_rules ORDER BY id",
            null
        ).use { c ->
            return buildList {
                while (c.moveToNext()) {
                    add(
                        TraitRule(
                            id = c.getLong(0),
                            traitKind = TraitKind.valueOf(c.getString(1)),
                            traitValue = c.getString(2),
                            entityId = c.getLong(3),
                            createdMs = c.getLong(4)
                        )
                    )
                }
            }
        }
    }

    private fun readSignature(c: Cursor): SignatureRow = SignatureRow(
        id = c.getLong(0),
        signatureKey = c.getString(1),
        radio = Radio.valueOf(c.getString(2)),
        address = c.getString(3),
        oui = c.getString(4),
        advertisedName = c.getString(5),
        companyIds = c.getString(6),
        serviceUuids = c.getString(7),
        ssid = c.getString(8),
        payloadFingerprint = c.getString(9),
        catalogHint = c.getString(10),
        entityId = if (c.isNull(11)) null else c.getLong(11),
        entityName = c.getString(12),
        firstSeenMs = c.getLong(13),
        lastSeenMs = c.getLong(14),
        hitCount = c.getLong(15),
        lastRssi = if (c.isNull(16)) null else c.getInt(16),
        lastLat = if (c.isNull(17)) null else c.getDouble(17),
        lastLon = if (c.isNull(18)) null else c.getDouble(18),
        lastScore = c.getInt(19)
    )

    private fun readSighting(c: Cursor): SightingRow = SightingRow(
        id = c.getLong(0),
        signatureId = c.getLong(1),
        seenMs = c.getLong(2),
        rssi = if (c.isNull(3)) null else c.getInt(3),
        lat = if (c.isNull(4)) null else c.getDouble(4),
        lon = if (c.isNull(5)) null else c.getDouble(5),
        score = c.getInt(6)
    )

    companion object {
        const val INGEST_GAP_MS = 2_000L
        const val SIGHTING_GAP_MS = 60_000L

        private const val SIGNATURE_SELECT = """
            SELECT s.id, s.signature_key, s.radio, s.address, s.oui, s.advertised_name,
                   s.company_ids, s.service_uuids, s.ssid, s.payload_fingerprint,
                   s.catalog_hint, s.entity_id, e.name,
                   s.first_seen_ms, s.last_seen_ms, s.hit_count,
                   s.last_rssi, s.last_lat, s.last_lon, s.last_score
            FROM signatures s
            LEFT JOIN entities e ON e.id = s.entity_id
        """

        @Volatile
        private var instance: SignatureRepository? = null

        fun get(context: Context): SignatureRepository {
            return instance ?: synchronized(this) {
                instance ?: SignatureRepository(CatalogDatabase(context.applicationContext)).also {
                    instance = it
                }
            }
        }
    }
}

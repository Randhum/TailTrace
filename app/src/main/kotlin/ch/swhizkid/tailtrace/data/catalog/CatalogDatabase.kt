package ch.swhizkid.tailtrace.data.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device catalog. Signatures and sightings never leave the phone.
 * Schema is additive — bump [VERSION] and add a migration when columns change.
 */
class CatalogDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    NAME,
    null,
    VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'custom',
                notes TEXT,
                created_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE signatures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                signature_key TEXT NOT NULL UNIQUE,
                radio TEXT NOT NULL,
                address TEXT NOT NULL,
                oui TEXT,
                advertised_name TEXT,
                company_ids TEXT,
                service_uuids TEXT,
                ssid TEXT,
                payload_fingerprint TEXT,
                catalog_hint TEXT,
                entity_id INTEGER,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                hit_count INTEGER NOT NULL DEFAULT 1,
                last_rssi INTEGER,
                last_lat REAL,
                last_lon REAL,
                last_score INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(entity_id) REFERENCES entities(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sig_entity ON signatures(entity_id)")
        db.execSQL("CREATE INDEX idx_sig_oui ON signatures(oui)")
        db.execSQL("CREATE INDEX idx_sig_last ON signatures(last_seen_ms)")
        db.execSQL(
            """
            CREATE TABLE sightings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                signature_id INTEGER NOT NULL,
                seen_ms INTEGER NOT NULL,
                rssi INTEGER,
                lat REAL,
                lon REAL,
                score INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(signature_id) REFERENCES signatures(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sight_sig ON sightings(signature_id, seen_ms)")
        db.execSQL(
            """
            CREATE TABLE trait_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trait_kind TEXT NOT NULL,
                trait_value TEXT NOT NULL,
                entity_id INTEGER NOT NULL,
                created_ms INTEGER NOT NULL,
                UNIQUE(trait_kind, trait_value),
                FOREIGN KEY(entity_id) REFERENCES entities(id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema. Future versions add columns here — never drop.
    }

    companion object {
        const val NAME = "tailtrace_catalog.db"
        const val VERSION = 1
    }
}

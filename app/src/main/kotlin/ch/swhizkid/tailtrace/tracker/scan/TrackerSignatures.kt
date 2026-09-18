package ch.swhizkid.tailtrace.tracker.scan

import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE wire-format constants for tracker ecosystems, per the VIGIL research brief.
 * Offsets marked in comments need empirical re-capture (SmartTag2, 2024+ Tile,
 * current AirTag firmware) before they should be trusted absolutely.
 */
object TrackerSignatures {

    const val APPLE_COMPANY_ID = 0x004C
    const val APPLE_TYPE_FINDMY = 0x12
    const val APPLE_STATUS_MAINTAINED_BIT = 0x04

    const val SAMSUNG_COMPANY_ID = 0x0075

    const val FMDN_FRAME_NORMAL = 0x40
    const val FMDN_FRAME_SEPARATED = 0x41

    val FMDN_UUID: ParcelUuid = uuid16(0xFEAA)
    val SAMSUNG_UUID: ParcelUuid = uuid16(0xFD5A)
    val TILE_ACTIVE_UUID: ParcelUuid = uuid16(0xFEED)
    val TILE_PREACT_UUID: ParcelUuid = uuid16(0xFEEC)
    val TILE_LEGACY_UUID: ParcelUuid = uuid16(0xFE84)
    val DULT_UUID: ParcelUuid = uuid16(0xFCB2)

    val trackerServiceUuids: List<ParcelUuid> = listOf(
        FMDN_UUID, SAMSUNG_UUID, TILE_ACTIVE_UUID, TILE_PREACT_UUID, TILE_LEGACY_UUID, DULT_UUID
    )

    fun uuid16(v: Int): ParcelUuid =
        ParcelUuid(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", 0x0000FFFF and v)))
}

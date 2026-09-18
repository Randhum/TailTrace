package ch.swhizkid.tailtrace.scan

/**
 * Which Bluetooth radio a proximity hunt should occupy.
 *
 * Tags mean *heard or typed*, not guessed:
 *  - `BLE` — an LE advert, or [android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE]
 *  - `Classic` — an inquiry hit, or DEVICE_TYPE_CLASSIC
 *  - `Dual` — DEVICE_TYPE_DUAL with no LE advert yet (do not treat as BLE)
 *
 * A GATT-connected tracker is TYPE_LE. Seeding it as Classic would send the
 * hunt into inquiry, which that radio can never answer.
 */
enum class BluetoothHuntMode { SURVEY, BLE, CLASSIC }

/** Android [android.bluetooth.BluetoothDevice] type constants (avoid importing the class in tests). */
internal const val BT_TYPE_UNKNOWN = 0
internal const val BT_TYPE_CLASSIC = 1
internal const val BT_TYPE_LE = 2
internal const val BT_TYPE_DUAL = 3

fun bluetoothHuntMode(signal: FinderSignal?): BluetoothHuntMode {
    if (signal == null) return BluetoothHuntMode.SURVEY
    return bluetoothHuntMode(signal.tags)
}

fun bluetoothHuntMode(tags: Collection<String>): BluetoothHuntMode {
    val heardBle = "BLE" in tags
    val heardClassic = "Classic" in tags
    return when {
        heardBle -> BluetoothHuntMode.BLE
        heardClassic -> BluetoothHuntMode.CLASSIC
        else -> BluetoothHuntMode.BLE
    }
}

fun bluetoothTypeTags(type: Int): List<String> = when (type) {
    BT_TYPE_LE -> listOf("BLE")
    BT_TYPE_CLASSIC -> listOf("Classic")
    BT_TYPE_DUAL -> listOf("Dual")
    else -> emptyList()
}

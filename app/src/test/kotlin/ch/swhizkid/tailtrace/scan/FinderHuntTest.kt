package ch.swhizkid.tailtrace.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class FinderHuntTest {

    @Test
    fun nullSignalIsSurvey() {
        assertEquals(BluetoothHuntMode.SURVEY, bluetoothHuntMode(null as FinderSignal?))
    }

    @Test
    fun leAdvertTakesBleEvenIfAlsoClassic() {
        assertEquals(BluetoothHuntMode.BLE, bluetoothHuntMode(listOf("BLE", "Classic", "Dual")))
    }

    @Test
    fun inquiryOnlyIsClassic() {
        assertEquals(BluetoothHuntMode.CLASSIC, bluetoothHuntMode(listOf("Classic", "Audio")))
    }

    @Test
    fun typeLeSeedIsBle() {
        assertEquals(
            BluetoothHuntMode.BLE,
            bluetoothHuntMode(listOf("Paired") + bluetoothTypeTags(BT_TYPE_LE))
        )
    }

    @Test
    fun typeClassicSeedIsClassic() {
        assertEquals(
            BluetoothHuntMode.CLASSIC,
            bluetoothHuntMode(listOf("Paired") + bluetoothTypeTags(BT_TYPE_CLASSIC))
        )
    }

    @Test
    fun typeDualWithoutAdvertPrefersBle() {
        // Dual + paired, no live radio yet — LE filter can still hear a tracker.
        assertEquals(
            BluetoothHuntMode.BLE,
            bluetoothHuntMode(listOf("Paired") + bluetoothTypeTags(BT_TYPE_DUAL))
        )
    }

    @Test
    fun dualHeardOnlyOnInquiryStaysClassicUntilAdvert() {
        assertEquals(
            BluetoothHuntMode.CLASSIC,
            bluetoothHuntMode(listOf("Classic") + bluetoothTypeTags(BT_TYPE_DUAL))
        )
    }

    @Test
    fun unknownTypeWithNoRadioTagPrefersBle() {
        assertEquals(
            BluetoothHuntMode.BLE,
            bluetoothHuntMode(listOf("Connected") + bluetoothTypeTags(BT_TYPE_UNKNOWN))
        )
    }

    @Test
    fun typeTagValues() {
        assertEquals(listOf("BLE"), bluetoothTypeTags(BT_TYPE_LE))
        assertEquals(listOf("Classic"), bluetoothTypeTags(BT_TYPE_CLASSIC))
        assertEquals(listOf("Dual"), bluetoothTypeTags(BT_TYPE_DUAL))
        assertEquals(emptyList<String>(), bluetoothTypeTags(BT_TYPE_UNKNOWN))
    }
}

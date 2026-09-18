package ch.swhizkid.tailtrace.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImsiCatcherEngineTest {

    private val zurichLat = 47.3769
    private val zurichLon = 8.5417

    private fun cell(
        rat: ImsiCatcherEngine.Rat,
        mcc: Int? = 228,
        mnc: Int? = 1,
        neighbors: Int = 3,
        cid: Long? = 12345,
        prevLte: Boolean = false,
        lat: Double? = zurichLat,
        lon: Double? = zurichLon,
        rejectCause: Int? = null
    ) = ImsiCatcherEngine.ServingCell(
        mcc = mcc,
        mnc = mnc,
        tacOrLac = 100,
        cellId = cid,
        pci = 12,
        rat = rat,
        dbm = -80,
        neighborCount = neighbors,
        lat = lat,
        lon = lon,
        previouslyLteOrNr = prevLte,
        recentRejectCause = rejectCause
    )

    @Test
    fun normalSwisscomLte_isClear() {
        val scored = ImsiCatcherEngine.score(cell(ImsiCatcherEngine.Rat.LTE))
        assertEquals(0, scored.score)
        assertTrue(scored.label.contains("no catcher"))
    }

    @Test
    fun gsmInland_isOrangeOrWorse() {
        val scored = ImsiCatcherEngine.score(cell(ImsiCatcherEngine.Rat.GSM))
        assertTrue(scored.score >= 70)
        assertTrue(scored.methods.contains("gsm_inland"))
    }

    @Test
    fun foreignMccInland_flags() {
        val scored = ImsiCatcherEngine.score(
            cell(ImsiCatcherEngine.Rat.LTE, mcc = 310, mnc = 260)
        )
        assertTrue(scored.score >= 55)
        assertTrue(scored.methods.contains("foreign_mcc_310"))
    }

    @Test
    fun germanMccNearBorder_doesNotUseInlandForeignWeight() {
        // Geneva-ish west edge — treated as near border
        val scored = ImsiCatcherEngine.score(
            cell(
                ImsiCatcherEngine.Rat.LTE,
                mcc = 208,
                mnc = 1,
                lat = 46.20,
                lon = 6.05
            )
        )
        assertTrue(scored.score < 40)
    }

    @Test
    fun lteToGsmDowngrade_addsTag() {
        val scored = ImsiCatcherEngine.score(
            cell(ImsiCatcherEngine.Rat.GSM, prevLte = true)
        )
        assertTrue(scored.methods.contains("lte_to_gsm"))
        assertTrue(scored.score >= ImsiCatcherEngine.W_GSM_INLAND)
    }

    @Test
    fun identityReject_flagsAloneOnCleanLte() {
        // IMSI unknown in HLR (cause 2) on an otherwise clean Swisscom LTE cell.
        val scored = ImsiCatcherEngine.score(
            cell(ImsiCatcherEngine.Rat.LTE, rejectCause = 2)
        )
        assertEquals(ImsiCatcherEngine.W_REJECT_IDENTITY, scored.score)
        assertTrue(scored.methods.contains("reg_reject_2"))
    }

    @Test
    fun congestionReject_staysBelowAlarm() {
        // Cause 22 (congestion) is routine — must not trip the 40-point alarm alone.
        val scored = ImsiCatcherEngine.score(
            cell(ImsiCatcherEngine.Rat.LTE, rejectCause = 22)
        )
        assertEquals(ImsiCatcherEngine.W_REJECT_OTHER, scored.score)
        assertTrue(scored.score < 40)
    }
}

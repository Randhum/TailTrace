package ch.swhizkid.tailtrace.fusion

import ch.swhizkid.tailtrace.data.targets.SwissPlmn

/**
 * Heuristic IMSI-catcher / fake-BTS scoring for a phone **already camped**
 * on a cell. Passive only — we read TelephonyManager; we do not probe,
 * attach, or send.
 *
 * This cannot prove a Stingray. Modern 4G/5G catchers can look like a real
 * operator. The strong CH-specific signal is **2G**: Swisscom shut GSM in
 * 2021 and the other MNOs followed. A registered GSM cell inland is the
 * main reason this source goes orange.
 */
object ImsiCatcherEngine {

    const val W_GSM_INLAND = 72
    const val W_UMTS_INLAND = 42
    const val W_FOREIGN_MCC_INLAND = 55
    const val W_BORDER_MCC_INLAND = 30
    const val W_UNKNOWN_CH_MNC = 25
    const val W_NO_NEIGHBORS = 18
    const val W_IMPLAUSIBLE_CID = 15
    const val W_DOWNGRADE = 12

    /**
     * Registration/TAU/LAU reject seen via TelephonyCallback (needs
     * READ_PRECISE_PHONE_STATE — priv-app install). Identity-related causes
     * (TS 24.008 §10.5.3.6 / TS 24.301 §9.9.3.9: 2 = IMSI unknown in HLR,
     * 3 = illegal MS, 6 = illegal ME) are classic catcher behaviour used to
     * force a victim off its home network; anything else (congestion, roaming
     * restriction) is common in normal operation and weighted low.
     */
    const val W_REJECT_IDENTITY = 55
    const val W_REJECT_OTHER = 15
    val IDENTITY_REJECT_CAUSES = setOf(2, 3, 6)

    const val SCORE_CAP = 92

    enum class Rat { GSM, UMTS, LTE, NR, UNKNOWN }

    data class ServingCell(
        val mcc: Int?,
        val mnc: Int?,
        val tacOrLac: Long?,
        val cellId: Long?,
        val pci: Int?,
        val rat: Rat,
        val dbm: Int?,
        val neighborCount: Int,
        val lat: Double?,
        val lon: Double?,
        val previouslyLteOrNr: Boolean = false,
        /** Recent registration-reject cause code (null when none in window). */
        val recentRejectCause: Int? = null
    )

    fun score(cell: ServingCell): ConfidenceEngine.Scored {
        var score = 0
        val methods = StringBuilder()
        val inland = SwissPlmn.inSwitzerland(cell.lat, cell.lon) &&
            !SwissPlmn.nearBorder(cell.lat, cell.lon)
        val inChBox = SwissPlmn.inSwitzerland(cell.lat, cell.lon)

        if (inland && cell.rat == Rat.GSM) {
            score += W_GSM_INLAND
            methods.append("gsm_inland ")
        } else if (inland && cell.rat == Rat.UMTS) {
            score += W_UMTS_INLAND
            methods.append("umts_inland ")
        }

        val mcc = cell.mcc
        if (mcc != null && inChBox && !SwissPlmn.nearBorder(cell.lat, cell.lon)) {
            when {
                mcc == SwissPlmn.MCC_CH -> { /* expected */ }
                mcc in SwissPlmn.BORDER_MCCS -> {
                    score += W_BORDER_MCC_INLAND
                    methods.append("border_mcc_$mcc ")
                }
                else -> {
                    score += W_FOREIGN_MCC_INLAND
                    methods.append("foreign_mcc_$mcc ")
                }
            }
        }

        if (mcc == SwissPlmn.MCC_CH && cell.mnc != null && cell.mnc !in SwissPlmn.CH_MNCS) {
            score += W_UNKNOWN_CH_MNC
            methods.append("unknown_mnc_${cell.mnc} ")
        }

        if (cell.neighborCount <= 0 && cell.rat != Rat.UNKNOWN) {
            score += W_NO_NEIGHBORS
            methods.append("no_neighbors ")
        }

        if (cell.rat == Rat.LTE && (cell.cellId == 0L || cell.cellId == 1L)) {
            score += W_IMPLAUSIBLE_CID
            methods.append("cid_${cell.cellId} ")
        }

        if (cell.previouslyLteOrNr && cell.rat == Rat.GSM) {
            score += W_DOWNGRADE
            methods.append("lte_to_gsm ")
        }

        val reject = cell.recentRejectCause
        if (reject != null) {
            score += if (reject in IDENTITY_REJECT_CAUSES) W_REJECT_IDENTITY else W_REJECT_OTHER
            methods.append("reg_reject_$reject ")
        }

        score = score.coerceAtMost(SCORE_CAP)
        val op = SwissPlmn.operatorName(cell.mcc, cell.mnc)
            ?: listOfNotNull(cell.mcc, cell.mnc).joinToString("-").ifBlank { "?" }
        val rat = cell.rat.name
        val label = if (score == 0) {
            "Serving $op $rat — no catcher indicators"
        } else {
            "Possible IMSI catcher — $op $rat"
        }
        return ConfidenceEngine.Scored(score, methods.toString().trim(), label, isAxon = false)
    }
}

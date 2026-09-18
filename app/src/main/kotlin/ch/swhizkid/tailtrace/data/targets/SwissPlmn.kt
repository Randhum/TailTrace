package ch.swhizkid.tailtrace.data.targets

/**
 * Swiss public land mobile networks (MCC 228) and neighbours that are
 * legitimate at the border.
 *
 * MNCs from the OFCOM / ITU 228 allocation as commonly published:
 * 01 Swisscom, 02 Sunrise, 03 Salt, plus known MVNOs and brands.
 * A 228 code that is not in [CH_MNCS] is unusual, not proof of a catcher.
 */
object SwissPlmn {

    const val MCC_CH = 228

    /** Austria, Germany, France, Italy, Liechtenstein. */
    val BORDER_MCCS: Set<Int> = setOf(232, 262, 208, 222, 295)

    val CH_MNCS: Set<Int> = setOf(
        1, 2, 3,
        5, 6, 7, 8, 9, 12,
        50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60
    )

    /** Rough CH bounding box (WGS84). */
    const val LAT_MIN = 45.818
    const val LAT_MAX = 47.808
    const val LON_MIN = 5.956
    const val LON_MAX = 10.492

    /** ~20 km in degrees — don't treat DE/FR/AT/IT/LI MCC as hostile here. */
    const val BORDER_BAND_DEG = 0.18

    fun inSwitzerland(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        return lat in LAT_MIN..LAT_MAX && lon in LON_MIN..LON_MAX
    }

    fun nearBorder(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return true
        if (!inSwitzerland(lat, lon)) return true
        return lat < LAT_MIN + BORDER_BAND_DEG ||
            lat > LAT_MAX - BORDER_BAND_DEG ||
            lon < LON_MIN + BORDER_BAND_DEG ||
            lon > LON_MAX - BORDER_BAND_DEG
    }

    fun operatorName(mcc: Int?, mnc: Int?): String? {
        if (mcc != MCC_CH || mnc == null) return null
        return when (mnc) {
            1 -> "Swisscom"
            2 -> "Sunrise"
            3 -> "Salt"
            12 -> "Lycamobile"
            else -> "CH MVNO $mnc"
        }
    }
}

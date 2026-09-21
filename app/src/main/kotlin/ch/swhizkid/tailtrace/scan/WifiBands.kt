package ch.swhizkid.tailtrace.scan

/** Frequency → channel/band labelling shared by the Wi-Fi scanners. */
internal object WifiBands {

    /** 802.11 channel number for a centre frequency, null if out of plan. */
    fun channelOf(freqMhz: Int): Int? = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> null
    }

    fun bandLabel(freqMhz: Int): String = when {
        freqMhz < 2500 -> "2.4 GHz"
        freqMhz < 5925 -> "5 GHz"
        else -> "6 GHz"
    }
}

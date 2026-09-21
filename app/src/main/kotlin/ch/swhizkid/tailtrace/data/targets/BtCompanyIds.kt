package ch.swhizkid.tailtrace.data.targets

/**
 * Bluetooth SIG company identifiers → short display names.
 *
 * Curated subset of the official assigned-numbers list
 * (bitbucket.org/bluetooth-SIG/public, company_identifiers.yaml,
 * fetched 2026-09-10) — every ID below was verified against that file.
 * Unknown IDs render as raw hex; this is a label map, not a detector.
 *
 * Note: the company ID names the maker of the BLE stack/advert, which for
 * white-label gear may differ from the brand on the shell.
 */
object BtCompanyIds {

    private val NAMES: Map<Int, String> = mapOf(
        0x0002 to "Intel",
        0x0006 to "Microsoft",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x001D to "Qualcomm",
        0x0046 to "MediaTek",
        0x004C to "Apple",
        0x0057 to "Harman",
        0x0059 to "Nordic Semiconductor",
        0x005D to "Realtek",
        0x0067 to "GN Hearing",
        0x006B to "Polar",
        0x0075 to "Samsung",
        0x0087 to "Garmin",
        0x0089 to "GN Hearing",
        0x009E to "Bose",
        0x009F to "Suunto",
        0x00C4 to "LG Electronics",
        0x00CC to "Beats",
        0x00E0 to "Google",
        0x0103 to "Bang & Olufsen",
        0x010E to "Audi",
        0x011F to "Volkswagen",
        0x012D to "Sony",
        0x0171 to "Amazon",
        0x0178 to "Casio",
        0x017C to "Mercedes-Benz",
        0x018E to "Google",
        0x01AB to "Meta",
        0x01DA to "Logitech",
        0x01DD to "Philips",
        0x01FC to "Wahoo Fitness",
        0x022B to "Tesla",
        0x027D to "Huawei",
        0x02B2 to "Oura",
        0x02C5 to "Lenovo",
        0x02E5 to "Espressif",
        0x0304 to "Oura",
        0x038F to "Xiaomi",
        0x03FF to "Withings",
        0x044A to "Shimano",
        0x0494 to "Sennheiser",
        0x0499 to "Ruuvi",
        0x058E to "Meta",
        0x05A7 to "Sonos",
        0x060F to "Signify (Hue)",
        0x0618 to "Audio-Technica",
        0x0639 to "Minew",
        0x067C to "Tile",
        0x0723 to "Ford",
        0x072F to "OnePlus",
        0x07C9 to "Skullcandy",
        0x089A to "Teltonika",
        0x08C3 to "Chipolo",
        0x0915 to "Honda",
        0x0933 to "SRAM",
        0x0969 to "SwitchBot",
        0x0977 to "Toyota",
        0x09C8 to "Xuntong (Raven)",
        0x0A12 to "Dyson",
        0x0BA9 to "Allterco (Shelly)",
        0x0CAC to "Shokz",
        0x0CC2 to "Anker",
        0x0CC4 to "ABUS",
        0x0CCB to "Nothing",
        0x0CE9 to "JLab Audio"
    )

    fun name(companyId: Int): String? = NAMES[companyId and 0xFFFF]

    /** First known vendor among [ids], else null. */
    fun bestName(ids: Collection<Int>): String? =
        ids.firstNotNullOfOrNull { name(it) }
}

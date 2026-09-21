package ch.swhizkid.tailtrace.data.targets

/**
 * OpenStreetMap tags that matter in Switzerland.
 *
 * US DeFlock only queried `surveillance:type=ALPR` (Flock cameras). That tag
 * is almost unused here. CH mapping instead uses speed cameras, section
 * control (Abschnittskontrolle), and public/outdoor CCTV.
 *
 * Indoor shop cameras (`surveillance=indoor`) are excluded — they drown the
 * map in cities and are not the public-space signal.
 */
object OsmSurveillance {

    enum class Kind {
        SPEED_CAMERA,
        SECTION_CONTROL,
        RED_LIGHT,
        ALPR,
        PUBLIC_CCTV
    }

    fun classify(tags: Map<String, String>): Kind {
        val highway = tags["highway"].orEmpty()
        val enforcement = tags["enforcement"].orEmpty()
        val survType = tags["surveillance:type"].orEmpty()
        return when {
            highway == "speed_camera" || enforcement == "maxspeed" -> Kind.SPEED_CAMERA
            enforcement == "average_speed" -> Kind.SECTION_CONTROL
            enforcement == "traffic_signals" -> Kind.RED_LIGHT
            survType.equals("ALPR", ignoreCase = true) ||
                survType.equals("ANPR", ignoreCase = true) -> Kind.ALPR
            else -> Kind.PUBLIC_CCTV
        }
    }

    fun label(kind: Kind): String = when (kind) {
        Kind.SPEED_CAMERA -> "Radar / speed camera"
        Kind.SECTION_CONTROL -> "Section control"
        Kind.RED_LIGHT -> "Red-light camera"
        Kind.ALPR -> "ANPR"
        Kind.PUBLIC_CCTV -> "Public CCTV"
    }

    /**
     * Overpass QL for a bbox. Nodes only — enforcement ways are usually
     * paired with a camera node we already catch.
     */
    fun overpassQuery(south: Double, west: Double, north: Double, east: Double, timeoutS: Int): String {
        val bbox = "($south,$west,$north,$east)"
        return """
            [out:json][timeout:$timeoutS];
            (
              node["highway"="speed_camera"]$bbox;
              node["enforcement"="maxspeed"]$bbox;
              node["enforcement"="average_speed"]$bbox;
              node["enforcement"="traffic_signals"]$bbox;
              node["man_made"="surveillance"]["surveillance"="public"]$bbox;
              node["man_made"="surveillance"]["surveillance"="outdoor"]$bbox;
              node["man_made"="surveillance"]["surveillance:type"="ALPR"]$bbox;
              node["man_made"="surveillance"]["surveillance:type"="ANPR"]$bbox;
            );
            out body;
        """.trimIndent().replace("\n", "")
    }
}

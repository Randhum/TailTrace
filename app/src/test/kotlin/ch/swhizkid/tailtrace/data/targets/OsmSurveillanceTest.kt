package ch.swhizkid.tailtrace.data.targets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmSurveillanceTest {

    @Test
    fun classify_swissEnforcementTags() {
        assertEquals(
            OsmSurveillance.Kind.SPEED_CAMERA,
            OsmSurveillance.classify(mapOf("highway" to "speed_camera"))
        )
        assertEquals(
            OsmSurveillance.Kind.SPEED_CAMERA,
            OsmSurveillance.classify(mapOf("enforcement" to "maxspeed"))
        )
        assertEquals(
            OsmSurveillance.Kind.SECTION_CONTROL,
            OsmSurveillance.classify(mapOf("enforcement" to "average_speed"))
        )
        assertEquals(
            OsmSurveillance.Kind.RED_LIGHT,
            OsmSurveillance.classify(mapOf("enforcement" to "traffic_signals"))
        )
        assertEquals(
            OsmSurveillance.Kind.ALPR,
            OsmSurveillance.classify(mapOf("surveillance:type" to "ANPR"))
        )
        assertEquals(
            OsmSurveillance.Kind.PUBLIC_CCTV,
            OsmSurveillance.classify(mapOf("man_made" to "surveillance", "surveillance" to "public"))
        )
    }

    @Test
    fun overpassQuery_asksSwissTagsNotUsAlprOnly() {
        val q = OsmSurveillance.overpassQuery(47.3, 8.5, 47.4, 8.6, 25)
        assertTrue(q.contains("highway"))
        assertTrue(q.contains("speed_camera"))
        assertTrue(q.contains("average_speed"))
        assertTrue(q.contains("surveillance\":\"public") || q.contains("surveillance\"=\"public\""))
        assertFalse(q.contains("deflock"))
    }
}

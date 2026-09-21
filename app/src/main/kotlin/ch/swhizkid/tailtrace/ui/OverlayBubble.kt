package ch.swhizkid.tailtrace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.cos
import kotlin.math.max
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import ch.swhizkid.tailtrace.data.settings.Settings
import ch.swhizkid.tailtrace.fusion.DetectionSource
import ch.swhizkid.tailtrace.fusion.ThreatLevel
import ch.swhizkid.tailtrace.service.DetectionService
import ch.swhizkid.tailtrace.ui.theme.ThreatColors

/**
 * Smaller "chat-bubble" version of the threat-map circle, hosted in a
 * WindowManager overlay by [ch.swhizkid.tailtrace.service.OverlayManager].
 *
 * Self-contained: pulls all of its data from the same companion-level
 * StateFlows the in-app [MainScreen] uses (DetectionService.running / store /
 * mapPoints / location) plus the proximity sliders from [Settings]. The
 * caller doesn't pass any state — keeps the OverlayManager dumb.
 *
 * Tap and drag are handled at the View layer (OverlayManager's OnTouchListener);
 * this composable is render-only.
 */
@Composable
fun OverlayBubble() {
    val ctx = LocalContext.current
    val settings = remember(ctx) { Settings.get(ctx) }

    val running by DetectionService.running.collectAsState()
    val threat by DetectionService.store.threatLevel.collectAsState()
    val userLocation by DetectionService.location.collectAsState()
    val mapPoints by DetectionService.mapPoints.collectAsState()
    val events by DetectionService.store.events.collectAsState()
    val osmProx by settings.osmProximityM.collectAsState()
    val wazeProx by settings.wazeProximityM.collectAsState()
    val radius = max(osmProx, wazeProx).toFloat()

    val activeColor = when (threat) {
        ThreatLevel.GREEN -> ThreatColors.Green
        ThreatLevel.YELLOW -> ThreatColors.Yellow
        ThreatLevel.ORANGE -> ThreatColors.Orange
        ThreatLevel.RED -> ThreatColors.Red
    }

    // The pulse lives in PulseVisuals' leaves; reading it here would
    // recompose the map host every frame.

    val userMark = remember(ctx) { crosshairDrawable(ctx.resources, 34, MARK_USER_WHITE) }
    val flockDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_FLOCK_RED) }
    val wazeDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_WAZE_BLUE) }
    val aircraftDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_AIRCRAFT_VIOLET) }
    val cellDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_CITIZEN_PURPLE) }
    val trackerDot = remember(ctx) { dotDrawable(ctx.resources, 22, DOT_TRACKER_PEACH) }

    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // The OverlayManager only attaches the bubble while running == true,
        // but check anyway — paranoia keeps the bubble from rendering a stale
        // map if a future code path lets the composition outlive the service.
        val fix = userLocation
        if (!running || fix == null) {
            PulsingDisc(
                color = activeColor,
                animating = running,
                label = null,
                labelColor = activeColor
            )
        } else {
            val camera = remember { MapCamera() }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    MapView(c).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(false)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        isClickable = false
                        isFocusable = false
                    }
                },
                update = { map ->
                    map.controller.setCenter(GeoPoint(fix.latitude, fix.longitude))
                    map.overlays.clear()
                    for (p in mapPoints) {
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(p.lat, p.lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = flockDot
                                title = p.operator ?: p.manufacturer ?: "ALPR"
                                setInfoWindow(null)
                            }
                        )
                    }
                    for (e in events) {
                        val lat = e.lat ?: continue
                        val lon = e.lon ?: continue
                        val dot = when (e.source) {
                            DetectionSource.WAZE -> wazeDot
                            DetectionSource.AIRCRAFT -> aircraftDot
                            DetectionSource.CELL -> cellDot
                            DetectionSource.TRACKER -> trackerDot
                            else -> null
                        } ?: continue
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(lat, lon)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = dot
                                setInfoWindow(null)
                            }
                        )
                    }
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(fix.latitude, fix.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = userMark
                            setInfoWindow(null)
                        }
                    )
                    val r = radius.toDouble().coerceAtLeast(50.0)
                    val latDegPerMeter = 1.0 / 111_000.0
                    val lonDegPerMeter = 1.0 /
                        (111_000.0 * cos(Math.toRadians(fix.latitude)).coerceAtLeast(0.01))
                    val bbox = BoundingBox(
                        fix.latitude + r * latDegPerMeter,
                        fix.longitude + r * lonDegPerMeter,
                        fix.latitude - r * latDegPerMeter,
                        fix.longitude - r * lonDegPerMeter
                    )
                    if (camera.needsMove(fix.latitude, fix.longitude, radius)) {
                        map.post { map.zoomToBoundingBox(bbox, false, 0) }
                    }
                    map.invalidate()
                },
                onRelease = { map -> map.onDetach() }
            )
            TierScrim(color = activeColor, animating = running)
        }
    }
}

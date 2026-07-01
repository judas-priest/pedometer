package com.pedometer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pedometer.data.GpsPointRecord
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun WorkoutMap(
    gpsPoints: List<GpsPointRecord>,
    modifier: Modifier = Modifier,
) {
    if (gpsPoints.size < 2) return

    val context = LocalContext.current
    var showFullscreen by remember { mutableStateOf(false) }

    remember {
        Configuration.getInstance().userAgentValue = context.packageName
        true
    }

    val geoPoints = remember(gpsPoints) {
        gpsPoints.map { GeoPoint(it.lat, it.lon) }
    }

    // Miniature map — tap to expand
    ElevatedCard(
        modifier = modifier.fillMaxWidth().clickable { showFullscreen = true },
    ) {
        Box {
            OsmMapView(
                geoPoints = geoPoints,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                interactive = false,
            )
            // Tap hint
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Text(
                    "Развернуть",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    // Fullscreen dialog
    if (showFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    OsmMapView(
                        geoPoints = geoPoints,
                        modifier = Modifier.fillMaxSize(),
                        interactive = true,
                    )
                    // Close button
                    IconButton(
                        onClick = { showFullscreen = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        ),
                    ) {
                        Icon(Icons.Default.Close, "Закрыть")
                    }
                }
            }
        }
    }
}

@Composable
private fun OsmMapView(
    geoPoints: List<GeoPoint>,
    modifier: Modifier,
    interactive: Boolean,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                if (!interactive) {
                    // Disable all gestures for miniature
                    setOnTouchListener { _, _ -> true }
                }

                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = android.graphics.Color.parseColor("#E53935")
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.isAntiAlias = true
                }
                overlays.add(polyline)

                if (geoPoints.size >= 2) {
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    post { zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                }
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            val polyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = android.graphics.Color.parseColor("#E53935")
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(polyline)
            if (geoPoints.size >= 2) {
                val box = BoundingBox.fromGeoPoints(geoPoints)
                mapView.zoomToBoundingBox(box.increaseByScale(1.3f), false)
            }
            mapView.invalidate()
        },
    )
}

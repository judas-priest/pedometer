package com.pedometer.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    // Init OSMDroid config
    remember {
        Configuration.getInstance().userAgentValue = context.packageName
        true
    }

    val geoPoints = remember(gpsPoints) {
        gpsPoints.map { GeoPoint(it.lat, it.lon) }
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

                    // Add route polyline
                    val polyline = Polyline().apply {
                        setPoints(geoPoints)
                        outlinePaint.color = android.graphics.Color.parseColor("#E53935")
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.isAntiAlias = true
                    }
                    overlays.add(polyline)

                    // Zoom to fit route
                    if (geoPoints.size >= 2) {
                        val box = BoundingBox.fromGeoPoints(geoPoints)
                        post {
                            zoomToBoundingBox(box.increaseByScale(1.3f), false)
                        }
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
}

package com.emir.citymapapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.emir.citymapapp.data.AppDatabase
import com.emir.citymapapp.data.LandmarkEntity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private const val ZOOM_MARKERS_MIN = 13.5
private const val ZOOM_LABELS_MIN = 15.5

data class Landmark(
    val id: Int,
    val title: String,
    val details: String,
    val point: Point,
    val category: String,
    val popupImageName: String? = null,
    val detailLine2: String? = null
)

private data class LandmarkCluster(
    val id: String,
    val point: Point,
    val count: Int,
    val landmarks: List<Landmark>
)

fun LandmarkEntity.toLandmark(): Landmark =
    Landmark(
        id = id,
        title = name,
        details = detailLine1,
        point = Point.fromLngLat(longitude, latitude),
        category = category,
        popupImageName = imageName,
        detailLine2 = detailLine2
    )

@Composable
fun getDrawableResourceIdByName(imageName: String?): Int? {
    val context = LocalContext.current
    return imageName?.let {
        context.resources.getIdentifier(it.lowercase(), "drawable", context.packageName)
            .takeIf { id -> id != 0 }
    }
}

fun loadScaledBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap? =
    try {
        val bitmap = BitmapFactory.decodeResource(context.resources, resId)
        Bitmap.createScaledBitmap(bitmap, width, height, false)
    } catch (e: Exception) {
        println("Error loading bitmap for resource ID $resId: ${e.message}")
        null
    }

@Composable
fun MapScreen(
    settingsPreferences: SettingsPreferences,
    onLandmarkClick: (Landmark) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).landmarkDao() }

    val landmarks by dao.getAllLandmarks()
        .map { it.map(LandmarkEntity::toLandmark) }
        .collectAsState(initial = emptyList())

    val pinSize by settingsPreferences.pinSizeSetting
        .collectAsState(initial = SettingsPreferences.DEFAULT_PIN_SIZE)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MapboxComposeView(
            landmarks = landmarks,
            pinSize = pinSize,
            onLandmarkClick = onLandmarkClick
        )

        if (landmarks.isEmpty()) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun MapboxComposeView(
    landmarks: List<Landmark>,
    pinSize: Float,
    onLandmarkClick: (Landmark) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val latestOnLandmarkClick by rememberUpdatedState(onLandmarkClick)

    DisposableEffect(Unit) {
        mapView.onStart()
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val annotationApi = mapView.annotations
    val userAnnotationManager =
        remember { annotationApi.createPointAnnotationManager(AnnotationConfig("user_layer")) }
    val landmarkAnnotationManager =
        remember { annotationApi.createPointAnnotationManager(AnnotationConfig("landmark_layer")) }
    val clusterAnnotationManager =
        remember { annotationApi.createPointAnnotationManager(AnnotationConfig("cluster_layer")) }

    val landmarkByAnnotationId = remember { mutableMapOf<String, Landmark>() }
    val clusterByAnnotationId = remember { mutableMapOf<String, LandmarkCluster>() }

    var hasZoomedToUser by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<PointAnnotation?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var renderedMode by remember { mutableStateOf<MapRenderMode?>(null) }

    fun renderForZoom(zoom: Double) {
        val newMode = when {
            zoom < ZOOM_MARKERS_MIN -> MapRenderMode.CLUSTERS
            zoom < ZOOM_LABELS_MIN -> MapRenderMode.MARKERS_ONLY
            else -> MapRenderMode.MARKERS_WITH_LABELS
        }

        if (newMode == renderedMode && landmarkByAnnotationId.size == landmarks.size) return
        renderedMode = newMode

        landmarkAnnotationManager.deleteAll()
        clusterAnnotationManager.deleteAll()
        landmarkByAnnotationId.clear()
        clusterByAnnotationId.clear()

        val grouped = if (newMode == MapRenderMode.CLUSTERS) clusterLandmarks(landmarks, zoom) else emptyList()

        grouped.forEach { cluster ->
            val opts = PointAnnotationOptions()
                .withPoint(cluster.point)
                .withIconImage("ic_landmark_blue")
                .withIconSize((pinSize * 1.15f).toDouble())
                .withTextField(cluster.count.toString())
                .withTextColor(AndroidColor.WHITE)
                .withTextHaloColor(AndroidColor.BLACK)
                .withTextHaloWidth(1.2)
                .withTextSize(12.0)
                .withTextAnchor(TextAnchor.CENTER)
            val annotation = clusterAnnotationManager.create(opts)
            clusterByAnnotationId[annotation.id] = cluster
        }

        val clusteredIds = grouped.flatMap { it.landmarks }.map { it.id }.toSet()
        val unclusteredLandmarks = if (newMode == MapRenderMode.CLUSTERS) {
            landmarks.filter { it.id !in clusteredIds }
        } else {
            landmarks
        }

        val showLabels = newMode == MapRenderMode.MARKERS_WITH_LABELS
        unclusteredLandmarks.forEach { lm ->
            val icon = when (lm.category.lowercase()) {
                "red" -> "ic_landmark_red"
                "yellow" -> "ic_landmark_yellow"
                else -> "ic_landmark_blue"
            }
            val opts = PointAnnotationOptions()
                .withPoint(lm.point)
                .withIconImage(icon)
                .withIconSize(pinSize.toDouble())
                .withTextField(if (showLabels) lm.title else "")
                .withTextSize(12.0)
                .withTextAnchor(TextAnchor.BOTTOM)
                .withTextOffset(listOf(0.0, -1.3))
                .withTextColor(AndroidColor.BLACK)
                .withTextHaloColor(AndroidColor.WHITE)
                .withTextHaloWidth(1.2)
            val annotation = landmarkAnnotationManager.create(opts)
            landmarkByAnnotationId[annotation.id] = lm
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
            addLandmarkImages(style, context)
            renderForZoom(mapView.getMapboxMap().cameraState.zoom)
        }
    }

    LaunchedEffect(landmarks, pinSize) {
        mapView.getMapboxMap().getStyle { style ->
            addLandmarkImages(style, context)
            renderedMode = null
            renderForZoom(mapView.getMapboxMap().cameraState.zoom)
        }
    }

    DisposableEffect(mapView, landmarks) {
        val cameraListener = com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener {
            renderForZoom(mapView.getMapboxMap().cameraState.zoom)
        }
        mapView.getMapboxMap().addOnCameraChangeListener(cameraListener)
        onDispose {
            mapView.getMapboxMap().removeOnCameraChangeListener(cameraListener)
        }
    }

    DisposableEffect(mapView) {
        val landmarkClickListener = com.mapbox.maps.plugin.annotation.generated.OnPointAnnotationClickListener { pa ->
            landmarkByAnnotationId[pa.id]?.let {
                latestOnLandmarkClick(it)
                true
            } ?: false
        }
        val clusterClickListener = com.mapbox.maps.plugin.annotation.generated.OnPointAnnotationClickListener { pa ->
            val cluster = clusterByAnnotationId[pa.id]
            if (cluster != null) {
                mapView.getMapboxMap().setCamera(
                    CameraOptions.Builder()
                        .center(cluster.point)
                        .zoom((mapView.getMapboxMap().cameraState.zoom + 1.5).coerceAtMost(18.0))
                        .build()
                )
                true
            } else {
                false
            }
        }

        landmarkAnnotationManager.addClickListener(landmarkClickListener)
        clusterAnnotationManager.addClickListener(clusterClickListener)

        onDispose {
            landmarkAnnotationManager.removeClickListener(landmarkClickListener)
            clusterAnnotationManager.removeClickListener(clusterClickListener)
        }
    }

    LaunchedEffect(mapView) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.create().apply {
            interval = 5_000L
            fastestInterval = 2_000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation ?: return
                val point = Point.fromLngLat(result.lastLocation!!.longitude, result.lastLocation!!.latitude)
                lastUserPoint = point

                mapView.getMapboxMap().getStyle { style ->
                    if (style.getStyleImage("ic_user_pin_scaled") == null) {
                        loadScaledBitmap(context, R.drawable.ic_user_pin, 100, 100)
                            ?.let { style.addImage("ic_user_pin_scaled", it) }
                    }
                    val opts = PointAnnotationOptions()
                        .withPoint(point)
                        .withIconImage("ic_user_pin_scaled")
                        .withIconSize(pinSize.toDouble())
                    userMarker?.let(userAnnotationManager::delete)
                    userMarker = userAnnotationManager.create(opts)
                }

                if (!hasZoomedToUser) {
                    mapView.getMapboxMap().setCamera(
                        CameraOptions.Builder().center(point).zoom(14.0).build()
                    )
                    hasZoomedToUser = true
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@LaunchedEffect
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitCancellation()
        fusedClient.removeLocationUpdates(callback)
    }

    LaunchedEffect(pinSize, userMarker) {
        userMarker?.let { marker ->
            if (marker.iconSize != pinSize.toDouble()) {
                marker.iconSize = pinSize.toDouble()
                userAnnotationManager.update(marker)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.gestures.updateSettings {
                    scrollEnabled = true
                    pinchToZoomEnabled = true
                    pitchEnabled = true
                    rotateEnabled = true
                }
                mv.setOnTouchListener { _, _ ->
                    mv.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        )

        FloatingActionButton(
            onClick = {
                lastUserPoint?.let {
                    mapView.getMapboxMap().setCamera(
                        CameraOptions.Builder().center(it).zoom(14.0).build()
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Icon(
                imageVector = Icons.Filled.GpsFixed,
                contentDescription = stringResource(R.string.center_map_button_description)
            )
        }

        MapLegend(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        )
    }
}

private enum class MapRenderMode {
    CLUSTERS,
    MARKERS_ONLY,
    MARKERS_WITH_LABELS
}

private fun clusterLandmarks(landmarks: List<Landmark>, zoom: Double): List<LandmarkCluster> {
    val cellSize = when {
        zoom < 11.0 -> 0.02
        zoom < 12.5 -> 0.013
        else -> 0.009
    }

    return landmarks
        .groupBy {
            val lat = (it.point.latitude() / cellSize).roundToInt()
            val lng = (it.point.longitude() / cellSize).roundToInt()
            "$lat-$lng"
        }
        .filter { it.value.size > 1 }
        .map { (key, group) ->
            val centerLat = group.map { it.point.latitude() }.average()
            val centerLng = group.map { it.point.longitude() }.average()
            LandmarkCluster(
                id = key,
                point = Point.fromLngLat(centerLng, centerLat),
                count = group.size,
                landmarks = group
            )
        }
}

private fun addLandmarkImages(style: Style, context: Context) {
    listOf(
        "ic_landmark_blue" to R.drawable.ic_landmark_blue,
        "ic_landmark_yellow" to R.drawable.ic_landmark_yellow,
        "ic_landmark_red" to R.drawable.ic_landmark_red
    ).forEach { (name, res) ->
        if (style.getStyleImage(name) == null) {
            loadScaledBitmap(context, res, 80, 80)?.let { style.addImage(name, it) }
        }
    }
}

@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LegendItem(
            pinResId = R.drawable.ic_landmark_blue,
            label = stringResource(id = R.string.legend_fully_accessible)
        )
        LegendItem(
            pinResId = R.drawable.ic_landmark_yellow,
            label = stringResource(id = R.string.legend_partially_accessible)
        )
        LegendItem(
            pinResId = R.drawable.ic_landmark_red,
            label = stringResource(id = R.string.legend_inaccessible)
        )
    }
}

@Composable
fun LegendItem(pinResId: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = pinResId),
            contentDescription = label,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

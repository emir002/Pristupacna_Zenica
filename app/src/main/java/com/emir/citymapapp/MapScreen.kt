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
import androidx.compose.foundation.layout.height
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
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.coroutine.queryRenderedFeatures
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.rememberCoroutineScope

private const val LANDMARKS_SOURCE_ID = "landmarks-source"
private const val CLUSTER_LAYER_ID = "landmarks-clusters"
private const val CLUSTER_COUNT_LAYER_ID = "landmarks-cluster-count"
private const val UNCLUSTERED_MARKERS_LAYER_ID = "landmarks-markers"
private const val UNCLUSTERED_LABELS_LAYER_ID = "landmarks-labels"
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
    val landmarksById = remember(landmarks) { landmarks.associateBy { it.id } }
    val coroutineScope = rememberCoroutineScope()

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

    var hasZoomedToUser by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<PointAnnotation?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
            ensureLandmarkStyle(style, context, pinSize)
            style.getSourceAs<GeoJsonSource>(LANDMARKS_SOURCE_ID)
                ?.featureCollection(buildLandmarksFeatureCollection(landmarks))
        }
    }

    LaunchedEffect(landmarks) {
        mapView.getMapboxMap().getStyle { style ->
            style.getSourceAs<GeoJsonSource>(LANDMARKS_SOURCE_ID)
                ?.featureCollection(buildLandmarksFeatureCollection(landmarks))
        }
    }

    LaunchedEffect(pinSize) {
        mapView.getMapboxMap().getStyle { style ->
            val markerLayer = style.getLayerAs<SymbolLayer>(UNCLUSTERED_MARKERS_LAYER_ID)
            markerLayer?.iconSize(pinSize.toDouble())
        }
    }

    DisposableEffect(mapView, landmarksById) {
        val clickListener = OnMapClickListener { clickedPoint ->
            coroutineScope.launch {
                val screenPoint = mapView.getMapboxMap().pixelForCoordinate(clickedPoint)
                val queryResult = mapView.getMapboxMap().queryRenderedFeatures(
                    RenderedQueryGeometry(ScreenCoordinate(screenPoint.x, screenPoint.y)),
                    com.mapbox.maps.RenderedQueryOptions(
                        listOf(CLUSTER_LAYER_ID, UNCLUSTERED_MARKERS_LAYER_ID),
                        null
                    )
                )
                val clickedFeature = queryResult.value?.firstOrNull()?.queriedFeature?.feature

                if (clickedFeature?.hasProperty("point_count") == true) {
                    mapView.getMapboxMap().setCamera(
                        CameraOptions.Builder()
                            .center((clickedFeature.geometry() as? Point) ?: clickedPoint)
                            .zoom(mapView.getMapboxMap().cameraState.zoom + 1.5)
                            .build()
                    )
                } else {
                    val landmarkId = clickedFeature
                        ?.getNumberProperty("landmark_id")
                        ?.toInt()
                    val landmark = landmarkId?.let(landmarksById::get)
                    if (landmark != null) {
                        latestOnLandmarkClick(landmark)
                    }
                }
            }
            true
        }
        mapView.gestures.addOnMapClickListener(clickListener)
        onDispose {
            mapView.gestures.removeOnMapClickListener(clickListener)
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
                val point = Point.fromLngLat(
                    result.lastLocation!!.longitude,
                    result.lastLocation!!.latitude
                )
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
            println("Location permission not granted.")
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

private fun ensureLandmarkStyle(style: Style, context: Context, pinSize: Float) {
    addLandmarkImages(style, context)

    if (style.getSource(LANDMARKS_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource.Builder(LANDMARKS_SOURCE_ID)
                .cluster(true)
                .clusterRadius(50)
                .clusterMaxZoom(13)
                .featureCollection(FeatureCollection.fromFeatures(emptyArray()))
                .build()
        )
    }

    if (style.getLayer(CLUSTER_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(CLUSTER_LAYER_ID, LANDMARKS_SOURCE_ID)
                .circleColor(Expression.step(
                    Expression.get("point_count"),
                    Expression.color("#8AB4F8"),
                    Expression.literal(20), Expression.color("#5E97F6"),
                    Expression.literal(50), Expression.color("#3F7DE0")
                ))
                .circleRadius(Expression.step(
                    Expression.get("point_count"),
                    Expression.literal(16),
                    Expression.literal(20), Expression.literal(20),
                    Expression.literal(50), Expression.literal(26)
                ))
                .maxZoom(ZOOM_MARKERS_MIN)
        )
    }

    if (style.getLayer(CLUSTER_COUNT_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(CLUSTER_COUNT_LAYER_ID, LANDMARKS_SOURCE_ID)
                .textField(Expression.get("point_count_abbreviated"))
                .textSize(12.0)
                .textColor(AndroidColor.WHITE)
                .maxZoom(ZOOM_MARKERS_MIN)
        )
    }

    if (style.getLayer(UNCLUSTERED_MARKERS_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(UNCLUSTERED_MARKERS_LAYER_ID, LANDMARKS_SOURCE_ID)
                .filter(Expression.not(Expression.has("point_count")))
                .iconImage(Expression.get("icon"))
                .iconSize(pinSize.toDouble())
                .iconAllowOverlap(false)
                .iconIgnorePlacement(false)
                .minZoom(ZOOM_MARKERS_MIN)
        )
    }

    if (style.getLayer(UNCLUSTERED_LABELS_LAYER_ID) == null) {
        style.addLayerAbove(
            SymbolLayer(UNCLUSTERED_LABELS_LAYER_ID, LANDMARKS_SOURCE_ID)
                .filter(Expression.not(Expression.has("point_count")))
                .textField(Expression.get("title"))
                .textSize(12.0)
                .textColor(AndroidColor.BLACK)
                .textHaloColor(AndroidColor.WHITE)
                .textHaloWidth(1.0)
                .textAnchor(TextAnchor.TOP)
                .textOffset(listOf(0.0, 1.1))
                .textAllowOverlap(false)
                .textIgnorePlacement(false)
                .textOptional(true)
                .minZoom(ZOOM_LABELS_MIN),
            UNCLUSTERED_MARKERS_LAYER_ID
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

private fun buildLandmarksFeatureCollection(landmarks: List<Landmark>): FeatureCollection {
    val features = landmarks.map { landmark ->
        Feature.fromGeometry(landmark.point).apply {
            addStringProperty("title", landmark.title)
            addStringProperty("category", landmark.category.lowercase())
            addStringProperty(
                "icon",
                when (landmark.category.lowercase()) {
                    "red" -> "ic_landmark_red"
                    "yellow" -> "ic_landmark_yellow"
                    else -> "ic_landmark_blue"
                }
            )
            addNumberProperty("landmark_id", landmark.id)
        }
    }
    return FeatureCollection.fromFeatures(features)
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

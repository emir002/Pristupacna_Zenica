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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.expressions.dsl.generated.has
import com.mapbox.maps.extension.style.expressions.dsl.generated.literal
import com.mapbox.maps.extension.style.expressions.dsl.generated.not
import com.mapbox.maps.extension.style.expressions.dsl.generated.step
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.TextJustify
import com.mapbox.maps.extension.style.layers.properties.generated.TextPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.TextRotationAlignment
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map

private const val LANDMARK_SOURCE_ID = "landmark-source"
private const val LANDMARK_CLUSTER_CIRCLE_LAYER_ID = "landmark-cluster-circle-layer"
private const val LANDMARK_CLUSTER_COUNT_LAYER_ID = "landmark-cluster-count-layer"
private const val LANDMARK_MARKER_LAYER_ID = "landmark-marker-layer"
private const val LANDMARK_LABEL_LAYER_ID = "landmark-label-layer"

private const val CLUSTER_MAX_ZOOM = 13.5
private const val LABEL_MIN_ZOOM = 15.5

private const val CLUSTER_RADIUS = 50L
private const val CLUSTER_MAX_ZOOM_FOR_SOURCE = 14L

private const val CLUSTER_TAP_ZOOM_DELTA = 1.4
private const val USER_CAMERA_ZOOM = 14.0

private const val STATUS_BLUE = "blue"
private const val STATUS_YELLOW = "yellow"
private const val STATUS_RED = "red"

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

    val landmarkById = remember { mutableStateMapOf<Int, Landmark>() }

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
                        CameraOptions.Builder().center(point).zoom(USER_CAMERA_ZOOM).build()
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

    LaunchedEffect(mapView, onLandmarkClick) {
        val clickListener = com.mapbox.maps.plugin.gestures.OnMapClickListener { point ->
            val mapboxMap = mapView.getMapboxMap()
            val screenCoordinate: ScreenCoordinate = mapboxMap.pixelForCoordinate(point)

            mapboxMap.queryRenderedFeatures(
                RenderedQueryGeometry(screenCoordinate),
                com.mapbox.maps.RenderedQueryOptions(listOf(LANDMARK_CLUSTER_CIRCLE_LAYER_ID), null)
            ) { clusterResult ->
                val clusterFeature = clusterResult.value?.firstOrNull()?.queriedFeature?.feature
                if (clusterFeature != null) {
                    val centerPoint = clusterFeature.geometry() as? Point
                    if (centerPoint != null) {
                        val nextZoom = (mapboxMap.cameraState.zoom + CLUSTER_TAP_ZOOM_DELTA).coerceAtMost(19.0)
                        mapboxMap.setCamera(
                            CameraOptions.Builder()
                                .center(centerPoint)
                                .zoom(nextZoom)
                                .build()
                        )
                    }
                    return@queryRenderedFeatures
                }

                mapboxMap.queryRenderedFeatures(
                    RenderedQueryGeometry(screenCoordinate),
                    com.mapbox.maps.RenderedQueryOptions(listOf(LANDMARK_MARKER_LAYER_ID), null)
                ) { markerResult ->
                    val markerFeature = markerResult.value?.firstOrNull()?.queriedFeature?.feature
                    val landmarkId = markerFeature?.getNumberProperty("landmarkId")?.toInt()
                    val tappedLandmark = landmarkId?.let { landmarkById[it] }
                    if (tappedLandmark != null) {
                        onLandmarkClick(tappedLandmark)
                    }
                }
            }
            true
        }

        mapView.gestures.addOnMapClickListener(clickListener)
        awaitCancellation()
        mapView.gestures.removeOnMapClickListener(clickListener)
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
                    addLandmarkImages(style, context)
                    setupLandmarkLayers(style)
                    updateLandmarkSource(style, landmarks, landmarkById)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { mv ->
            mv.gestures.updateSettings {
                scrollEnabled = true
                pinchToZoomEnabled = true
                pitchEnabled = true
                rotateEnabled = true
            }

            mv.getMapboxMap().getStyle { style ->
                updateLandmarkSource(style, landmarks, landmarkById)
                updateMarkerSizing(style, pinSize)
            }

            mv.setOnTouchListener { _, _ ->
                mv.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = {
                lastUserPoint?.let {
                    mapView.getMapboxMap().setCamera(
                        CameraOptions.Builder().center(it).zoom(USER_CAMERA_ZOOM).build()
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

private fun setupLandmarkLayers(style: Style) {
    if (style.getSource(LANDMARK_SOURCE_ID) == null) {
        style.addSource(
            geoJsonSource(LANDMARK_SOURCE_ID) {
                cluster(true)
                clusterRadius(CLUSTER_RADIUS)
                clusterMaxZoom(CLUSTER_MAX_ZOOM_FOR_SOURCE)
                featureCollection(FeatureCollection.fromFeatures(emptyArray()))
            }
        )
    }

    if (style.getLayer(LANDMARK_CLUSTER_CIRCLE_LAYER_ID) == null) {
        style.addLayer(
            circleLayer(LANDMARK_CLUSTER_CIRCLE_LAYER_ID, LANDMARK_SOURCE_ID) {
                filter(has("point_count"))
                minZoom(0.0)
                maxZoom(CLUSTER_MAX_ZOOM)
                circleColor("#4F46E5")
                circleStrokeWidth(2.0)
                circleStrokeColor("#FFFFFF")
                circleRadius(
                    step {
                        get("point_count")
                        literal(18)
                        stop {
                            literal(15)
                            literal(22)
                        }
                        stop {
                            literal(30)
                            literal(28)
                        }
                    }
                )
            }
        )
    }

    if (style.getLayer(LANDMARK_CLUSTER_COUNT_LAYER_ID) == null) {
        style.addLayer(
            symbolLayer(LANDMARK_CLUSTER_COUNT_LAYER_ID, LANDMARK_SOURCE_ID) {
                filter(has("point_count"))
                minZoom(0.0)
                maxZoom(CLUSTER_MAX_ZOOM)
                textField(get("point_count_abbreviated"))
                textSize(12.0)
                textColor("#FFFFFF")
                textIgnorePlacement(true)
                textAllowOverlap(true)
            }
        )
    }

    if (style.getLayer(LANDMARK_MARKER_LAYER_ID) == null) {
        style.addLayer(
            symbolLayer(LANDMARK_MARKER_LAYER_ID, LANDMARK_SOURCE_ID) {
                filter(not { has("point_count") })
                minZoom(CLUSTER_MAX_ZOOM)
                iconImage(get("iconImage"))
                iconSize(1.0)
                iconAllowOverlap(false)
                iconIgnorePlacement(false)
                iconAnchor(IconAnchor.BOTTOM)
                iconPitchAlignment(IconPitchAlignment.MAP)
                iconRotationAlignment(IconRotationAlignment.MAP)
            }
        )
    }

    if (style.getLayer(LANDMARK_LABEL_LAYER_ID) == null) {
        style.addLayer(
            symbolLayer(LANDMARK_LABEL_LAYER_ID, LANDMARK_SOURCE_ID) {
                filter(not { has("point_count") })
                minZoom(LABEL_MIN_ZOOM)
                textField(get("title"))
                textSize(12.0)
                textColor("#1E293B")
                textHaloColor("#FFFFFF")
                textHaloWidth(1.3)
                textAnchor(TextAnchor.TOP)
                textOffset(listOf(0.0, 1.2))
                textMaxWidth(10.0)
                textVariableAnchor(listOf("top", "left", "right"))
                textJustify(TextJustify.AUTO)
                textOptional(true)
                textAllowOverlap(false)
                textIgnorePlacement(false)
                textPitchAlignment(TextPitchAlignment.MAP)
                textRotationAlignment(TextRotationAlignment.MAP)
            }
        )
    }
}

private fun updateLandmarkSource(
    style: Style,
    landmarks: List<Landmark>,
    landmarkById: MutableMap<Int, Landmark>
) {
    landmarkById.clear()
    val features = landmarks.map { landmark ->
        landmarkById[landmark.id] = landmark
        Feature.fromGeometry(landmark.point).apply {
            addNumberProperty("landmarkId", landmark.id)
            addStringProperty("title", landmark.title)
            addStringProperty("category", landmark.category.lowercase())
            addStringProperty("iconImage", iconNameForCategory(landmark.category))
        }
    }

    (style.getSource(LANDMARK_SOURCE_ID) as? GeoJsonSource)
        ?.featureCollection(FeatureCollection.fromFeatures(features))
}

private fun updateMarkerSizing(style: Style, pinSize: Float) {
    (style.getLayer(LANDMARK_MARKER_LAYER_ID) as? SymbolLayer)?.iconSize(pinSize.toDouble())
}

private fun iconNameForCategory(category: String): String = when (category.lowercase()) {
    STATUS_RED -> "ic_landmark_red"
    STATUS_YELLOW -> "ic_landmark_yellow"
    else -> "ic_landmark_blue"
}

@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

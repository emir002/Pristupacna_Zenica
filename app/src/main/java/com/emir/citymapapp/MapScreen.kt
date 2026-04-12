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
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.expressions.dsl.generated.has
import com.mapbox.maps.extension.style.expressions.dsl.generated.literal
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.clusterMaxZoom
import com.mapbox.maps.extension.style.sources.generated.clusterRadius
import com.mapbox.maps.extension.style.sources.generated.cluster
import com.mapbox.maps.extension.style.sources.generated.data
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map

private const val LANDMARK_SOURCE_ID = "landmarks-source"
private const val CLUSTER_CIRCLE_LAYER_ID = "landmarks-cluster-circle-layer"
private const val CLUSTER_COUNT_LAYER_ID = "landmarks-cluster-count-layer"
private const val LANDMARK_ICON_LAYER_BLUE_ID = "landmarks-icon-blue-layer"
private const val LANDMARK_ICON_LAYER_YELLOW_ID = "landmarks-icon-yellow-layer"
private const val LANDMARK_ICON_LAYER_RED_ID = "landmarks-icon-red-layer"
private const val LANDMARK_LABEL_LAYER_ID = "landmarks-label-layer"

private const val MEDIUM_ZOOM_MIN = 13.5
private const val LABEL_ZOOM_MIN = 15.5
private const val CLUSTER_ZOOM_MAX = 13.5

private const val CLUSTER_TAP_ZOOM_STEP = 2.0

data class Landmark(
    val id: Int,
    val title: String,
    val details: String,
    val point: Point,
    val category: String,
    val popupImageName: String? = null,
    val detailLine2: String? = null
)

fun LandmarkEntity.toLandmark(): Landmark = Landmark(
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
    val textSize by settingsPreferences.textSizeSetting
        .collectAsState(initial = SettingsPreferences.DEFAULT_TEXT_SIZE)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MapboxComposeView(
            landmarks = landmarks,
            pinSize = pinSize,
            textSize = textSize,
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
    textSize: Float,
    onLandmarkClick: (Landmark) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        mapView.onStart()
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val annotationApi = mapView.annotations
    val userAnnotationManager = remember {
        annotationApi.createPointAnnotationManager(AnnotationConfig("user_layer"))
    }

    var hasZoomedToUser by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<PointAnnotation?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var mapClickListener by remember { mutableStateOf<OnMapClickListener?>(null) }

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
                    mapView.getMapboxMap()
                        .setCamera(CameraOptions.Builder().center(point).zoom(14.0).build())
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

    LaunchedEffect(mapView) {
        mapView.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
            loadLandmarkIcons(style, context)
            addLandmarkSourceAndLayers(style, pinSize, textSize)

            mapClickListener?.let { mapView.gestures.removeOnMapClickListener(it) }
            val listener = OnMapClickListener { clickedPoint ->
                handleLandmarkTap(
                    mapView = mapView,
                    clickedPoint = clickedPoint,
                    onLandmarkClick = onLandmarkClick
                )
                true
            }
            mapView.gestures.addOnMapClickListener(listener)
            mapClickListener = listener
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapClickListener?.let { mapView.gestures.removeOnMapClickListener(it) }
        }
    }

    LaunchedEffect(landmarks) {
        mapView.getMapboxMap().getStyle { style ->
            updateLandmarkSource(style, landmarks)
        }
    }

    LaunchedEffect(pinSize, textSize) {
        mapView.getMapboxMap().getStyle { style ->
            updateLandmarkLayerSizes(style, pinSize, textSize)
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

private fun loadLandmarkIcons(style: Style, context: Context) {
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

private fun addLandmarkSourceAndLayers(style: Style, pinSize: Float, textSize: Float) {
    if (style.styleSourceExists(LANDMARK_SOURCE_ID)) return

    style.addSource(
        GeoJsonSource(LANDMARK_SOURCE_ID) {
            data(FeatureCollection.fromFeatures(emptyList()))
            cluster(true)
            clusterRadius(50L)
            clusterMaxZoom(13L)
        }
    )

    style.addLayer(
        CircleLayer(CLUSTER_CIRCLE_LAYER_ID, LANDMARK_SOURCE_ID).apply {
            filter(has("point_count"))
            minZoom(0.0)
            maxZoom(CLUSTER_ZOOM_MAX)
            circleColor("#3B82F6")
            circleRadius(
                Expression.step(
                    get("point_count"),
                    literal(18.0),
                    literal(15.0), literal(24.0),
                    literal(40.0), literal(30.0)
                )
            )
            circleOpacity(0.85)
            circleStrokeColor("#FFFFFF")
            circleStrokeWidth(2.0)
        }
    )

    style.addLayer(
        SymbolLayer(CLUSTER_COUNT_LAYER_ID, LANDMARK_SOURCE_ID).apply {
            filter(has("point_count"))
            minZoom(0.0)
            maxZoom(CLUSTER_ZOOM_MAX)
            textField(Expression.toString(get("point_count")))
            textSize(12.0)
            textColor("#FFFFFF")
            textIgnorePlacement(true)
            textAllowOverlap(true)
        }
    )

    addMarkerLayer(style, LANDMARK_ICON_LAYER_BLUE_ID, "blue", "ic_landmark_blue", pinSize)
    addMarkerLayer(style, LANDMARK_ICON_LAYER_YELLOW_ID, "yellow", "ic_landmark_yellow", pinSize)
    addMarkerLayer(style, LANDMARK_ICON_LAYER_RED_ID, "red", "ic_landmark_red", pinSize)

    style.addLayer(
        SymbolLayer(LANDMARK_LABEL_LAYER_ID, LANDMARK_SOURCE_ID).apply {
            filter(Expression.not(has("point_count")))
            minZoom(LABEL_ZOOM_MIN)
            textField(get("title"))
            textSize(textSize.toDouble())
            textAnchor(TextAnchor.TOP)
            textOffset(listOf(0.0, 1.1))
            textColor("#111111")
            textHaloColor("#FFFFFF")
            textHaloWidth(1.0)
            textOptional(true)
            textAllowOverlap(false)
            textIgnorePlacement(false)
        }
    )
}


private fun addMarkerLayer(
    style: Style,
    layerId: String,
    category: String,
    iconImage: String,
    pinSize: Float
) {
    style.addLayer(
        SymbolLayer(layerId, LANDMARK_SOURCE_ID).apply {
            filter(
                Expression.all(
                    Expression.not(has("point_count")),
Expression.eq(get("category"), literal(category))
                )
            )
            minZoom(MEDIUM_ZOOM_MIN)
            iconImage(iconImage)
            iconSize(pinSize.toDouble())
            iconAllowOverlap(true)
            iconIgnorePlacement(true)
        }
    )
}
private fun updateLandmarkSource(style: Style, landmarks: List<Landmark>) {
    val source = style.getSourceAs<GeoJsonSource>(LANDMARK_SOURCE_ID) ?: return
    val features = landmarks.map { landmark ->
        Feature.fromGeometry(landmark.point).apply {
            addNumberProperty("id", landmark.id)
            addStringProperty("title", landmark.title)
            addStringProperty("details", landmark.details)
            addStringProperty("category", landmark.category.lowercase())
            addStringProperty("detailLine2", landmark.detailLine2 ?: "")
            addStringProperty("popupImageName", landmark.popupImageName ?: "")
        }
    }
    source.featureCollection(FeatureCollection.fromFeatures(features))
}

private fun updateLandmarkLayerSizes(style: Style, pinSize: Float, textSize: Float) {
    style.getLayerAs<SymbolLayer>(LANDMARK_ICON_LAYER_BLUE_ID)?.iconSize(pinSize.toDouble())
    style.getLayerAs<SymbolLayer>(LANDMARK_ICON_LAYER_YELLOW_ID)?.iconSize(pinSize.toDouble())
    style.getLayerAs<SymbolLayer>(LANDMARK_ICON_LAYER_RED_ID)?.iconSize(pinSize.toDouble())
    style.getLayerAs<SymbolLayer>(LANDMARK_LABEL_LAYER_ID)?.textSize(textSize.toDouble())
}

private fun handleLandmarkTap(
    mapView: MapView,
    clickedPoint: Point,
    onLandmarkClick: (Landmark) -> Unit
) {
    val mapboxMap = mapView.getMapboxMap()
    val clickedPixel = mapboxMap.pixelForCoordinate(clickedPoint)

    mapboxMap.queryRenderedFeatures(
        RenderedQueryGeometry(clickedPixel),
        RenderedQueryOptions(listOf(CLUSTER_CIRCLE_LAYER_ID), null)
    ) { clusterResult ->
        val clusterFeature = clusterResult.value?.firstOrNull()?.queriedFeature?.feature
        if (clusterFeature != null) {
            val clusterCenter = clusterFeature.geometry() as? Point
            if (clusterCenter != null) {
                val currentZoom = mapboxMap.cameraState.zoom
                mapboxMap.easeTo(
                    CameraOptions.Builder()
                        .center(clusterCenter)
                        .zoom(currentZoom + CLUSTER_TAP_ZOOM_STEP)
                        .build()
                )
            }
            return@queryRenderedFeatures
        }

        mapboxMap.queryRenderedFeatures(
            RenderedQueryGeometry(clickedPixel),
            RenderedQueryOptions(
                listOf(
                    LANDMARK_ICON_LAYER_BLUE_ID,
                    LANDMARK_ICON_LAYER_YELLOW_ID,
                    LANDMARK_ICON_LAYER_RED_ID,
                    LANDMARK_LABEL_LAYER_ID
                ),
                null
            )
        ) { result ->
            val feature = result.value?.firstOrNull()?.queriedFeature?.feature ?: return@queryRenderedFeatures
            onLandmarkClick(feature.toLandmarkOrNull() ?: return@queryRenderedFeatures)
        }
    }
}

private fun Feature.toLandmarkOrNull(): Landmark? {
    val point = geometry() as? Point ?: return null
    val id = getNumberProperty("id")?.toInt() ?: return null

    return Landmark(
        id = id,
        title = getStringProperty("title") ?: return null,
        details = getStringProperty("details") ?: "",
        point = point,
        category = getStringProperty("category") ?: "blue",
        popupImageName = getStringProperty("popupImageName").takeUnless { it.isNullOrBlank() },
        detailLine2 = getStringProperty("detailLine2").takeUnless { it.isNullOrBlank() }
    )
}

@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

package com.emir.citymapapp
// MapScreen.kt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed // Using GpsFixed for location
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Color as AndroidColor

/* -------------------------------------------------------------------- */
/* ------------------  Data class & small utilities  ------------------ */
/* -------------------------------------------------------------------- */

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

/* -------------------------------------------------------------------- */
/* ---------------------------  Map Screen  --------------------------- */
/* -------------------------------------------------------------------- */

@Composable
fun MapScreen(
    settingsPreferences: SettingsPreferences,
    onLandmarkClick: (Landmark) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).landmarkDao() }

    /* --- Landmarks from DB ------------------------------------------ */
    val landmarks by dao.getAllLandmarks()
        .map { it.map(LandmarkEntity::toLandmark) }
        .collectAsState(initial = emptyList())

    /* --- User-tunable map settings ---------------------------------- */
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

/* -------------------------------------------------------------------- */
/* -----------------------  Mapbox Composable  ------------------------ */
/* -------------------------------------------------------------------- */

@Composable
fun MapboxComposeView(
    landmarks: List<Landmark>,
    pinSize: Float,
    textSize: Float,
    onLandmarkClick: (Landmark) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    /* Mapbox view lifecycle */
    DisposableEffect(Unit) {
        mapView.onStart()
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    /* Annotation managers */
    val annotationApi = mapView.annotations
    val userAnnotationManager =
        remember { annotationApi.createPointAnnotationManager(AnnotationConfig("user_layer")) }
    val landmarkAnnotationManager =
        remember { annotationApi.createPointAnnotationManager(AnnotationConfig("landmark_layer")) }

    /* Track user location and zoom once */
    var hasZoomedToUser by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<PointAnnotation?>(null) }
    var lastUserPoint by remember { mutableStateOf<Point?>(null) } // Store last user point

    /* ---------- Location updates ---------- */
    LaunchedEffect(mapView) { // Removed pinSize as key, will use current value
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.create().apply {
            interval = 5_000L
            fastestInterval = 2_000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation ?: return
                val point = Point.fromLngLat(result.lastLocation!!.longitude,
                    result.lastLocation!!.latitude)
                lastUserPoint = point // Update last user point

                mapView.getMapboxMap().getStyle { style ->
                    if (style.getStyleImage("ic_user_pin_scaled") == null) {
                        loadScaledBitmap(context, R.drawable.ic_user_pin, 100, 100)
                            ?.let { style.addImage("ic_user_pin_scaled", it) }
                    }
                    val opts = PointAnnotationOptions()
                        .withPoint(point)
                        .withIconImage("ic_user_pin_scaled")
                        .withIconSize(pinSize.toDouble()) // Use pinSize
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
            println("Location permission not granted.")
            return@LaunchedEffect
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitCancellation()
        fusedClient.removeLocationUpdates(callback)
    }

    /* --- NEW: Effect to update user marker size when pinSize changes --- */
    LaunchedEffect(pinSize, userMarker) {
        userMarker?.let { marker ->
            // Check if size actually needs updating to avoid unnecessary redraws
            if (marker.iconSize != pinSize.toDouble()) {
                marker.iconSize = pinSize.toDouble()
                userAnnotationManager.update(marker)
            }
        }
    }

    /* ---------- AndroidView wrapper with Overlays ---------- */
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) { style ->
                    /* preload landmark icons */
                    listOf(
                        "ic_landmark_blue" to R.drawable.ic_landmark_blue,
                        "ic_landmark_yellow" to R.drawable.ic_landmark_yellow,
                        "ic_landmark_red" to R.drawable.ic_landmark_red
                    ).forEach { (name, res) ->
                        if (style.getStyleImage(name) == null) {
                            loadScaledBitmap(context, res, 80, 80)?.let { style.addImage(name, it) }
                        }
                    }

                    mv.gestures.updateSettings {
                        scrollEnabled = true
                        pinchToZoomEnabled = true
                        pitchEnabled = true
                        rotateEnabled = true
                    }

                    landmarkAnnotationManager.deleteAll()

                    val annotationsById = mutableMapOf<String, Landmark>()
                    landmarkAnnotationManager.addClickListener { pa ->
                        annotationsById[pa.id]?.let(onLandmarkClick)
                        true
                    }

                    val groupedByCoordinate = landmarks.groupBy { it.point.toJson() }
                    val currentIndexByCoordinate = mutableMapOf<String, Int>()

                    landmarks.forEach { lm ->
                        val icon = when (lm.category.lowercase()) {
                            "red" -> "ic_landmark_red"
                            "yellow" -> "ic_landmark_yellow"
                            else -> "ic_landmark_blue"
                        }

                        val coordinateKey = lm.point.toJson()
                        val currentIndex = currentIndexByCoordinate.getOrDefault(coordinateKey, 0)
                        currentIndexByCoordinate[coordinateKey] = currentIndex + 1
                        val duplicatesAtPoint = groupedByCoordinate[coordinateKey]?.size ?: 1

                        val renderedPoint = if (duplicatesAtPoint > 1) {
                            val angle = (2 * PI * currentIndex) / duplicatesAtPoint
                            val offset = 0.00018 // ~20 m to separate overlapping pins
                            Point.fromLngLat(
                                lm.point.longitude() + (offset * cos(angle)),
                                lm.point.latitude() + (offset * sin(angle))
                            )
                        } else {
                            lm.point
                        }

                        val opts = PointAnnotationOptions()
                            .withPoint(renderedPoint)
                            .withTextField(lm.title)
                            .withIconImage(icon)
                            .withIconSize(pinSize.toDouble()) // Use pinSize
                            .withTextSize(textSize.toDouble())
                            .withTextAnchor(TextAnchor.BOTTOM)
                            .withTextOffset(listOf(0.0, -1.5))
                            .withTextColor(AndroidColor.BLACK)
                            .withTextHaloColor(AndroidColor.WHITE)
                            .withTextHaloWidth(1.5)

                        val annotation = landmarkAnnotationManager.create(opts)
                        annotationsById[annotation.id] = lm
                    }
                }

                /* allow map to intercept gestures */
                mv.setOnTouchListener { _, _ ->
                    mv.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        )

        /* --- NEW: Current Location Button --- */
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

        /* --- NEW: Map Legend --- */
        MapLegend(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        )
    }
}

/* --- NEW: Map Legend Composable --- */
@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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

/* --- NEW: Legend Item Composable --- */
@Composable
fun LegendItem(pinResId: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = pinResId),
            contentDescription = label,
            modifier = Modifier.size(24.dp) // Adjust size as needed
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

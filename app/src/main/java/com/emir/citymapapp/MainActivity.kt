package com.emir.citymapapp
// MainActivity.kt (Using AppCompatDelegate for Locale)

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
// import android.content.res.Configuration // No longer needed for updateLocaleIfNeeded
import android.net.Uri
import android.os.Bundle
import android.util.Log
// import androidx.activity.ComponentActivity // CHANGED
import androidx.appcompat.app.AppCompatActivity // CHANGED: Import AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate // NEW: Import AppCompatDelegate
import androidx.core.os.LocaleListCompat // NEW: Import LocaleListCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope // Import lifecycleScope
import com.emir.citymapapp.ui.theme.CityMapAppTheme
import com.emir.citymapapp.util.OfflineMapManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first // Import first
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() { // CHANGED: Inherit from AppCompatActivity

    private lateinit var settingsPreferences: SettingsPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPreferences = SettingsPreferences(applicationContext)

        // This lifecycleScope launch attempts to sync the AppCompatDelegate's locale
        // with the DataStore preference when the app starts.
        lifecycleScope.launch {
            val desiredLocaleFromDataStore = settingsPreferences.localeSetting.first()
            val currentAppCompatLocales = AppCompatDelegate.getApplicationLocales()

            if (currentAppCompatLocales.isEmpty || currentAppCompatLocales.get(0)?.language != desiredLocaleFromDataStore) {
                Log.d("LocaleSwitch", "onCreate: Syncing AppCompatDelegate locale to DataStore value: $desiredLocaleFromDataStore")
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(desiredLocaleFromDataStore))
                // Important: setApplicationLocales might trigger a configuration change.
                // If called after UI is partially up, it could cause a recreate.
                // Here, it's early in onCreate, so it should apply to this Activity instance.
            }
        }

        setContent {
            val currentThemeSetting by settingsPreferences.themeSetting
                .collectAsState(initial = ThemeSetting.SYSTEM)
            val startupWarningAccepted by settingsPreferences.startupWarningAccepted
                .collectAsState(initial = false)

            CityMapAppTheme(themeSetting = currentThemeSetting) {
                when (startupWarningAccepted) {
                    false -> StartupWarningDialogWrapper(settingsPreferences) { finish() }
                    true -> {
                        LaunchedEffect(Unit) {
                            OfflineMapManager.downloadZenicaRegionIfNeeded(applicationContext)
                        }
                        AppContent(settingsPreferences)
                    }
                }
            }
        }
    }
}

/* ====================================================================== */
/* ========================   COMPOSABLES   ============================= */
/* ====================================================================== */

@Composable
fun StartupWarningDialogWrapper(
    settingsPreferences: SettingsPreferences,
    onCancelApp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    StartupWarningDialog(
        onConfirm = {
            scope.launch {
                settingsPreferences.saveStartupWarningAccepted(true)
            }
        },
        onCancel = onCancelApp
    )
}

@Composable
fun StartupWarningDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* Prevent dismissing */ },
        title = { Text(stringResource(R.string.startup_warning_title)) },
        text = { Text(stringResource(R.string.startup_warning_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.startup_warning_ok))
            }
        },
        dismissButton = {
            Button(onClick = onCancel) {
                Text(stringResource(R.string.startup_warning_cancel))
            }
        }
    )
}


@Composable
fun AppContent(settingsPreferences: SettingsPreferences) {
    val context = LocalContext.current
    var locationPermissionGranted by remember(context) {
        mutableStateOf(isLocationPermissionGranted(context))
    }
    var isLoading by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationPermissionGranted = permissions.values.any { it }
    }

    LaunchedEffect(Unit) {
        delay(2000L)
        isLoading = false
    }

    when {
        isLoading -> LoadingScreen()
        !locationPermissionGranted -> PermissionRequestScreen {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        else -> MainScaffold(settingsPreferences = settingsPreferences)
    }
}

fun isLocationPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.permission_request_text),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.permission_grant_button))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(settingsPreferences: SettingsPreferences) {
    var currentScreen by remember { mutableStateOf("Home") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedLandmark by remember { mutableStateOf<Landmark?>(null) }
    val context = LocalContext.current // This will be an AppCompatActivity context

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(
                            text = stringResource(R.string.menu_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                        Divider()
                        Text(
                            text = stringResource(R.string.settings_language),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Text(
                                "🇬🇧",
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        Log.d("LocaleSwitch", "Saving 'en' and setting AppCompat locale via click.")
                                        settingsPreferences.saveLocaleSetting("en")
                                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                                        // Recreate to apply immediately. setApplicationLocales alone might not be enough
                                        // for an immediate visual update of the current Activity.
                                        (context as? Activity)?.recreate()
                                    }
                                },
                                style = MaterialTheme.typography.headlineLarge
                            )
                            Text(
                                "🇧🇦",
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        Log.d("LocaleSwitch", "Saving 'bs' and setting AppCompat locale via click.")
                                        settingsPreferences.saveLocaleSetting("bs")
                                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("bs"))
                                        (context as? Activity)?.recreate()
                                    }
                                },
                                style = MaterialTheme.typography.headlineLarge
                            )
                        }
                        Divider()
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.menu_home)) },
                            selected = currentScreen == "Home",
                            onClick = {
                                currentScreen = "Home"
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.menu_settings)) },
                            selected = currentScreen == "Settings",
                            onClick = {
                                currentScreen = "Settings"
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.menu_about)) },
                            selected = currentScreen == "About",
                            onClick = {
                                currentScreen = "About"
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        },
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        "Home" -> MapScreen(
                            settingsPreferences = settingsPreferences,
                            onLandmarkClick = { landmark -> selectedLandmark = landmark }
                        )
                        "Settings" -> SettingsScreen(settingsPreferences = settingsPreferences)
                        "About" -> AboutScreen()
                    }
                }
                selectedLandmark?.let { landmark ->
                    LandmarkBottomSheet(
                        landmark = landmark,
                        onDismiss = { selectedLandmark = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkBottomSheet(landmark: Landmark, onDismiss: () -> Unit) {
    val imageResId = getDrawableResourceIdByName(landmark.popupImageName)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = landmark.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))

            val accessibilityStatus = when (landmark.category.lowercase()) {
                "blue" -> stringResource(R.string.legend_fully_accessible)
                "yellow" -> stringResource(R.string.legend_partially_accessible)
                "red" -> stringResource(R.string.legend_inaccessible)
                else -> landmark.category
            }
            Text(
                text = "${stringResource(R.string.landmark_accessibility_status)} $accessibilityStatus",
                style = MaterialTheme.typography.titleSmall
            )

            imageResId?.let { resId ->
                Spacer(modifier = Modifier.height(12.dp))
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = stringResource(
                        R.string.landmark_image_description,
                        landmark.title
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "${stringResource(R.string.landmark_popup_info_prefix)} ${landmark.details}",
                style = MaterialTheme.typography.bodyLarge
            )

            landmark.detailLine2?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${stringResource(R.string.landmark_popup_details_prefix)} $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SettingsScreen(settingsPreferences: SettingsPreferences) {
    val scope = rememberCoroutineScope()
    val currentThemeSetting by settingsPreferences.themeSetting
        .collectAsState(initial = ThemeSetting.SYSTEM)
    val currentPinSize by settingsPreferences.pinSizeSetting
        .collectAsState(initial = SettingsPreferences.DEFAULT_PIN_SIZE)
    val currentTextSize by settingsPreferences.textSizeSetting
        .collectAsState(initial = SettingsPreferences.DEFAULT_TEXT_SIZE)
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        /* ---- Theme picker ---- */
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup()) {
            ThemeSetting.values().forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .selectable(
                            selected = option == currentThemeSetting,
                            onClick = { scope.launch { settingsPreferences.saveThemeSetting(option) } },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option == currentThemeSetting, onClick = null)
                    val name = when (option) {
                        ThemeSetting.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeSetting.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeSetting.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                    Text(name, Modifier.padding(start = 16.dp))
                }
            }
        }
        Divider(Modifier.padding(vertical = 16.dp))

        /* ---- Map display ---- */
        Text(stringResource(R.string.settings_map_display), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        SettingSlider(
            label = stringResource(R.string.settings_pin_size),
            value = currentPinSize,
            onValueChange = { scope.launch { settingsPreferences.savePinSizeSetting(it) } },
            valueRange = 0.5f..2.0f,
            steps = 14,
            defaultValue = SettingsPreferences.DEFAULT_PIN_SIZE,
            onRestoreDefault = { scope.launch { settingsPreferences.savePinSizeSetting(SettingsPreferences.DEFAULT_PIN_SIZE) } }
        )
        Spacer(Modifier.height(16.dp))

        SettingSlider(
            label = stringResource(R.string.settings_text_size),
            value = currentTextSize,
            onValueChange = { scope.launch { settingsPreferences.saveTextSizeSetting(it) } },
            valueRange = 8f..20f,
            steps = 11,
            defaultValue = SettingsPreferences.DEFAULT_TEXT_SIZE,
            onRestoreDefault = { scope.launch { settingsPreferences.saveTextSizeSetting(SettingsPreferences.DEFAULT_TEXT_SIZE) } }
        )
        Divider(Modifier.padding(vertical = 16.dp))

        /* ---- Permission info ---- */
        Text(stringResource(R.string.settings_permissions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val status = if (isLocationPermissionGranted(context))
            stringResource(R.string.settings_permissions_granted)
        else
            stringResource(R.string.settings_permissions_not_granted)
        Text(stringResource(R.string.settings_permissions_status, status))
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValue: Float,
    onRestoreDefault: () -> Unit
) {
    Column {
        Text(text = "$label: ${String.format("%.1f", value)}", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRestoreDefault, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.settings_restore_default))
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.about_developed_by))
        Text(
            text = stringResource(R.string.about_contact),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${context.getString(R.string.about_contact)}")
                }
                context.startActivity(intent)
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.about_mapbox_info), style = MaterialTheme.typography.bodySmall)
    }
}

private const val FRAME_DURATION_MS = 200L
private val loadingAnimationFrames = listOf(
    R.drawable.frame_0_loading, R.drawable.frame_1_loading, R.drawable.frame_2_loading,
    R.drawable.frame_3_loading, R.drawable.frame_4_loading, R.drawable.frame_5_loading
)

@Composable
fun LoadingScreen() {
    var currentFrameIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (loadingAnimationFrames.isNotEmpty()) {
            while (true) {
                currentFrameIndex = (currentFrameIndex + 1) % loadingAnimationFrames.size
                delay(FRAME_DURATION_MS)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loadingAnimationFrames.isNotEmpty()) {
                Image(
                    painter = painterResource(id = loadingAnimationFrames[currentFrameIndex]),
                    contentDescription = stringResource(R.string.loading_text),
                    modifier = Modifier.size(120.dp)
                )
            } else {
                CircularProgressIndicator()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.loading_text))
        }
    }
}

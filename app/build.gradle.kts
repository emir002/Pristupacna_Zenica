// build.gradle.kts (Module :app)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // id("org.jetbrains.kotlin.plugin.compose") // This plugin is often not needed directly with modern setups.
    // `buildFeatures { compose = true }` and compose dependencies handle it.
    // Keep it if your project requires it, but consider if it's redundant.
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.emir.citymapapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emir.citymapapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true }
    composeOptions {
        // Ensure this version is compatible with your Kotlin version.
        // For Kotlin 1.9.x, 1.5.x is common. For Kotlin 2.0.0, it might be different.
        // Let's assume 1.5.10 as a common stable version compatible with recent setups.
        // If you are using Kotlin 2.0.0, you might need a newer extension version if available.
        // The original script had 2.0.0, which is likely incorrect or bleeding-edge.
        // Let's use a version known to work well with recent Compose BOMs, like 1.5.10 or check official compatibility.
        // For Compose BOM 2024.05.00, 1.5.10 is generally a safe bet with Kotlin ~1.9.23.
        // If you are indeed on Kotlin 2.0.0, ensure your Compose BOM and compiler extension support it.
        // We will keep it as 1.5.10 for now as 2.0.0 is very new/potentially wrong.
        kotlinCompilerExtensionVersion = "1.5.10" // Check for compatibility with your Kotlin version
    }
}

kotlin { jvmToolchain(17) }

/* -------------------------------------------------------------------------
   Dependencies
   --------------------------------------------------------------------- */
dependencies {
    /* ---------- Jetpack Compose (BOM keeps artifacts in sync) ---------- */
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    /* ---------- Activity / Lifecycle ---------------------------------- */
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0") // Updated to a more recent version
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0") // Updated to a more recent version

    /* ---------- Theme related ------------------------------------------ */
    // Add this for XML Material 3 themes like Theme.Material3.DayNight.NoActionBar
    implementation("com.google.android.material:material:1.12.0") // Or a newer stable version

    // You should also still have the appcompat dependency from previous steps
    implementation("androidx.appcompat:appcompat:1.6.1") // Or a newer stable version
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")


    /* ---------- Google-Play location ----------------------------------- */
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    /* ---------- Room (with KSP) --------------------------------------- */
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0") // Updated to a more recent version

    /* ---------- DataStore --------------------------------------------- */
    implementation("androidx.datastore:datastore-preferences:1.1.1") // Updated to a more recent version

    /* ---------- Mapbox – Using latest recommended (11.12.2) ------------- */
    val mapboxVersion = "11.12.2" // Updated to the latest recommended stable version
    implementation("com.mapbox.maps:android:$mapboxVersion")          // Core SDK
    implementation("com.mapbox.extension:maps-compose:$mapboxVersion") // CORRECTED Group ID and Artifact ID

    /* ---------- Kotlin Coroutines ------------------------------------- */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0") // Updated to a more recent version
}

/* ---------- KSP options --------------------------------------------- */
ksp {
    arg("room.generateKotlin", "true")
    // arg("room.schemaLocation", "$projectDir/schemas")
}

/* ---------------------------------------------------------------------
   Enforce *one* Mapbox version in case another library sneaks in a
   different one later.
   ------------------------------------------------------------------ */
configurations.all {
    exclude(group = "com.android.support.constraint", module = "constraint-layout")
    exclude(group = "com.android.support.constraint", module = "constraint-layout-solver")

    resolutionStrategy {
        eachDependency {
            // Check only for the correct Mapbox group ID.
            if (requested.group == "com.mapbox.maps" ||
                requested.group == "com.mapbox.extension") { // Add this check
                useVersion("11.12.2")
            }
        }
    }
}

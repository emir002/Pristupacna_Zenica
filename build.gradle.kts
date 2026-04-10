// build.gradle.kts (Project: CityMapApp)

plugins {
    // Android & Kotlin
    id("com.android.application")             version "8.9.0" apply false
    id("org.jetbrains.kotlin.android")        version "1.9.22"        apply false
    //id("org.jetbrains.kotlin.plugin.compose") version "1.9.22"        apply false

    // KSP (Room, etc.)
    id("com.google.devtools.ksp")             version "1.9.22-1.0.18" apply false
}

/*  Nothing else is required here – all repositories live in
    settings.gradle.kts                                                      */

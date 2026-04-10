// ---------------------------------------------------------------------
// settings.gradle.kts  –  CityMapApp
// ---------------------------------------------------------------------
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.authentication.http.BasicAuthentication
import org.gradle.api.artifacts.repositories.PasswordCredentials

rootProject.name = "Pristupacna_Zenica"
include(":app")

// ──────────────────────────────────────────────────────────────────────
// 1.  Plugins (Kotlin, AGP, …)
// ──────────────────────────────────────────────────────────────────────
pluginManagement {

    // ⬇︎  visible *inside this lambda* only
    val mapboxToken: String =
        providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
            .orElse(System.getenv("MAPBOX_DOWNLOADS_TOKEN"))
            .forUseAtConfigurationTime()
            .get()

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()

        maven("https://api.mapbox.com/downloads/v2/releases/maven") {
            name = "MapboxSDK"
            authentication { create<BasicAuthentication>("basic") }
            credentials(PasswordCredentials::class) {
                username = "mapbox"
                password = mapboxToken         // ✅ works – in scope!
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// 2.  Project-level dependencies
// ──────────────────────────────────────────────────────────────────────
dependencyResolutionManagement {

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // ⬇︎  *another* local val – each lambda has its own scope
    val mapboxToken: String =
        providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
            .orElse(System.getenv("MAPBOX_DOWNLOADS_TOKEN"))
            .forUseAtConfigurationTime()
            .get()

    repositories {
        google()
        mavenCentral()

        maven("https://api.mapbox.com/downloads/v2/releases/maven") {
            name = "MapboxSDK"
            authentication { create<BasicAuthentication>("basic") }
            credentials(PasswordCredentials::class) {
                username = "mapbox"
                password = mapboxToken
            }
        }
    }
}

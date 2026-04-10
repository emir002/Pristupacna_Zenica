# Pristupačna Zenica (Android)

## 1) What this app does (Function & Purpose)

**Pristupačna Zenica** is an Android map application focused on **accessibility of city locations (landmarks)** in Zenica.

Core user value:
- Shows points of interest on a map.
- Classifies each location by accessibility status using color-coded pins:
  - **Blue** = fully accessible
  - **Yellow** = partially accessible
  - **Red** = inaccessible
- Lets users open details (including optional image + text lines) for each location.
- Supports **Bosnian and English** UI switching.
- Supports **dark/light/system theme**.
- Supports offline map preloading for Zenica region (with first-launch warning).

In short: this app is a **city accessibility guide** with offline-capable mapping and user personalization.

---

## 2) High-level architecture

The project follows a practical single-module Android architecture with clear separation of concerns:

### UI Layer (Jetpack Compose)
- `MainActivity.kt` hosts app state flow, startup flow, permission flow, navigation drawer, settings/about/popups, and top-level composition.
- `MapScreen.kt` hosts map rendering and map-specific UI/logic (Mapbox `MapView`, location marker, legend, map click handling).
- Theme files under `ui/theme/` provide Material3 color/type/theme setup.

### Data & Persistence Layer
- **Room database** for landmarks:
  - `LandmarkEntity.kt`
  - `LandmarkDao.kt`
  - `AppDatabase.kt`
- Prepopulates landmarks from `assets/landmarks.sql` on first DB creation.

### Settings / Preferences Layer
- `SettingsPreferences.kt` uses **DataStore Preferences** to persist:
  - Theme
  - Locale
  - Pin size
  - Landmark text size
  - Startup warning acceptance

### Infrastructure / Services
- `OfflineMapManager.kt` handles offline style pack + tile-region download for Zenica using Mapbox offline APIs.
- `YourApplication.kt` configures global Mapbox token + shared `TileStore`.

This architecture is a **Compose-first app with local persistence and service helpers**, centered around a single activity.

---

## 3) Runtime behavior walkthrough

### App launch flow
1. App starts in `MainActivity`.
2. Loads settings (theme, locale, startup-warning flag).
3. If startup warning not accepted, shows warning dialog.
4. If accepted, triggers offline map download check.
5. Shows loading animation briefly, then checks location permission.
6. If permission granted -> opens main scaffold with map.

### Main navigation
Drawer sections:
- **Home**: interactive map + landmarks + legend + “center on my location” button.
- **Settings**: theme, map pin size, text size, permission status.
- **About**: app metadata and contact.

### Map interaction
- Landmark pins are rendered from Room data.
- Tapping a pin opens a popup with details and optional image.
- User location is updated via fused location provider and shown with dedicated marker.

---

## 4) Tech stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Maps**: Mapbox Maps SDK (+ compose extension dependency present)
- **Database**: Room
- **Settings**: DataStore Preferences
- **Location**: Google Play Services Location
- **Build system**: Gradle (Kotlin DSL), KSP for Room

---

## 5) Strengths in current implementation

- Clear user-facing purpose (accessibility mapping).
- Good practical feature set: localization, theme, map tuning controls.
- Offline-region strategy for better reliability.
- Simple Room + SQL asset prepopulation approach.
- Compose UI is modularized into reusable composables.

---

## 6) Issues identified during analysis

1. **Startup warning state initialization bug risk**
   - UI collected a non-null `Flow<Boolean>` with `initial = null` and had a `when` branch for `null`.
   - This is type-inconsistent and can break build or behavior depending on compiler/type inference.

2. **Deprecated Gradle API usage**
   - `forUseAtConfigurationTime()` in `settings.gradle.kts` is deprecated and produces warnings.

3. **Minor code hygiene / maintainability issues**
   - Unused variable in `MainActivity` (`currentLocaleCode` was collected but not used).
   - Unused coroutine imports in `AppDatabase.kt`.

---

## 7) Improvements & optimizations applied (without changing app function)

I applied safe changes that preserve the app’s functional behavior while improving reliability/maintainability:

1. **Fixed startup-warning state handling in `MainActivity`**
   - Changed `collectAsState(initial = null)` to `collectAsState(initial = false)` for `startupWarningAccepted`.
   - Removed impossible `null` branch in the `when` block.
   - Effect: startup flow remains the same from user perspective (warning shown until accepted), while state handling is now type-safe and stable.

2. **Removed unused state collection in `MainActivity`**
   - Removed unused `currentLocaleCode` collector.
   - Effect: small recomposition/state overhead reduction and cleaner code.

3. **Removed deprecated Gradle usage in `settings.gradle.kts`**
   - Removed `forUseAtConfigurationTime()` in two places.
   - Effect: cleaner Gradle output and forward compatibility with newer Gradle versions.

4. **Removed unused imports in `AppDatabase.kt`**
   - Removed `Dispatchers`, `GlobalScope`, and `launch` imports that were unused.
   - Effect: cleaner code and less confusion for future maintainers.

---

## 8) Current limitations / follow-up recommendations

These are **recommendations only** (not applied to avoid behavior change):

- Consider moving Mapbox access token out of committed `strings.xml` into safer secret management for production.
- Consider adding automated unit/instrumentation coverage for startup and map flows.
- Consider extracting navigation state to a sealed class/router abstraction for long-term scalability.
- Consider handling missing `MAPBOX_DOWNLOADS_TOKEN` with a clearer Gradle error message.

---

## 9) Repository map (important files)

- Entry points:
  - `app/src/main/java/com/emir/citymapapp/MainActivity.kt`
  - `app/src/main/java/com/emir/citymapapp/YourApplication.kt`
- Map:
  - `app/src/main/java/com/emir/citymapapp/MapScreen.kt`
  - `app/src/main/java/com/emir/citymapapp/util/OfflineMapManager.kt`
- Data:
  - `app/src/main/java/com/emir/citymapapp/AppDatabase.kt`
  - `app/src/main/java/com/emir/citymapapp/LandmarkDao.kt`
  - `app/src/main/java/com/emir/citymapapp/LandmarkEntity.kt`
  - `app/src/main/assets/landmarks.sql`
- Settings/theme/resources:
  - `app/src/main/java/com/emir/citymapapp/SettingsPreferences.kt`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-bs/strings.xml`
  - `app/src/main/java/com/emir/citymapapp/ui/theme/*`

---

## 10) Build/check note

A full Android build could not be completed in this environment due to missing local **Java 17 toolchain**.

Gradle error summary:
- `Cannot find a Java installation ... languageVersion=17`.

Once JDK 17 is available, run:

```bash
./gradlew :app:assembleDebug
```


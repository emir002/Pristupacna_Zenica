package com.emir.citymapapp
// YourApplication.kt

import android.app.Application
import com.mapbox.common.MapboxOptions          // access token holder
import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMapsOptions       // <-- correct global options
import com.mapbox.maps.TileStoreUsageMode
import com.emir.citymapapp.R

class YourApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1️⃣  Access-token from res/values/strings.xml
        MapboxOptions.accessToken = getString(R.string.mapbox_access_token)

        // 2️⃣  One shared TileStore
        val tileStore = TileStore.create()

        MapboxMapsOptions.tileStore = tileStore
        MapboxMapsOptions.tileStoreUsageMode = TileStoreUsageMode.READ_AND_UPDATE
    }
}

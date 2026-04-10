package com.emir.citymapapp.util
// OfflineMapManager.kt

import android.content.Context
import android.util.Log
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.Value
import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.common.TileStore
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackError
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.StylePackLoadProgress
import com.mapbox.maps.TilesetDescriptorOptions

/**
 * Downloads a style-pack **and** a tile region covering Zenica (z10 – z16).
 */
object OfflineMapManager {

    private const val TAG = "OfflineMapManager"

    private const val STYLE_PACK_METADATA = "ZenicaStylePack"
    private const val TILE_REGION_METADATA = "ZenicaTileRegion"
    private const val TILE_REGION_ID = "ZenicaTileRegionID"

    private val ZENICA_BOUNDS: Polygon = Polygon.fromLngLats(
        listOf(
            listOf(
                Point.fromLngLat(17.86, 44.16),
                Point.fromLngLat(17.96, 44.16),
                Point.fromLngLat(17.96, 44.25),
                Point.fromLngLat(17.86, 44.25),
                Point.fromLngLat(17.86, 44.16)
            )
        )
    )

    private const val MIN_ZOOM = 10
    private const val MAX_ZOOM = 16

    private lateinit var tileStore: TileStore
    private lateinit var offlineMgr: OfflineManager
    private var ready = false

    @Synchronized
    private fun init(context: Context) {
        if (ready) return
        tileStore = MapboxMapsOptions.tileStore
            ?: throw IllegalStateException(
                "Global TileStore must be set in YourApplication.onCreate()"
            )
        offlineMgr = OfflineManager()
        ready = true
    }

    fun downloadZenicaRegionIfNeeded(context: Context) {
        init(context.applicationContext)

        offlineMgr.getAllStylePacks { expected ->
            expected.fold(
                { err: StylePackError ->
                    Log.e(TAG, "Query style-packs failed: ${err.message}. Downloading anew.")
                    downloadStylePack(context)
                },
                { packs: MutableList<StylePack> ->
                    val already = packs.any { it.styleURI == Style.MAPBOX_STREETS }
                    if (already) {
                        Log.i(TAG, "Style-pack cached → downloading tiles only.")
                        downloadTileRegion(context)
                    } else {
                        Log.i(TAG, "Style-pack missing → downloading.")
                        downloadStylePack(context)
                    }
                }
            )
        }
    }

    // ─────────────── STYLE-PACK ───────────────
    private fun downloadStylePack(context: Context) {
        val opts = StylePackLoadOptions.Builder()
            .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
            .metadata(Value(STYLE_PACK_METADATA))
            .build()

        offlineMgr.loadStylePack(
            Style.MAPBOX_STREETS,
            opts,
            { p: StylePackLoadProgress ->
                val pct = if (p.requiredResourceCount > 0)
                    p.completedResourceCount * 100 / p.requiredResourceCount else 0
                Log.d(TAG, "Style-pack download: $pct %")
            },
            { res: Expected<StylePackError, StylePack> ->
                res.fold(
                    { err: StylePackError ->
                        Log.e(TAG, "Style-pack download failed: ${err.message}")
                    },
                    { pack: StylePack ->
                        Log.i(
                            TAG,
                            "Style-pack downloaded ✓ (uri=${pack.styleURI}, expires=${pack.expires})"
                        )
                        downloadTileRegion(context)
                    }
                )
            }
        )
    }

    // ─────────────── TILE-REGION ───────────────
    private fun downloadTileRegion(context: Context) {
        val density = context.resources.displayMetrics.density

        val descriptor: TilesetDescriptor = offlineMgr.createTilesetDescriptor(
            TilesetDescriptorOptions.Builder()
                .styleURI(Style.MAPBOX_STREETS)
                .minZoom(MIN_ZOOM.toByte())
                .maxZoom(MAX_ZOOM.toByte())
                .pixelRatio(density)
                .build()
        )
        Log.i(TAG, "TilesetDescriptor created.")

        val opts = TileRegionLoadOptions.Builder()
            .geometry(ZENICA_BOUNDS)
            .descriptors(listOf(descriptor))
            .metadata(Value(TILE_REGION_METADATA))
            .acceptExpired(true)
            .networkRestriction(NetworkRestriction.NONE)
            .build()

        tileStore.loadTileRegion(
            TILE_REGION_ID,
            opts,
            { p: TileRegionLoadProgress ->
                val pct = if (p.requiredResourceCount > 0)
                    p.completedResourceCount * 100 / p.requiredResourceCount else 0
                Log.d(TAG, "Tile-region download: $pct %")
            },
            { res: Expected<TileRegionError, TileRegion> ->
                res.fold(
                    { err: TileRegionError ->
                        Log.e(TAG, "Tile-region download failed: ${err.message}")
                    },
                    { region: TileRegion ->
                        Log.i(TAG, "Tile-region downloaded ✓ (id=${region.id})")
                    }
                )
            }
        )
    }
}

package com.example.mymap.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mymap.domain.model.UserLocation
import com.example.mymap.util.Constants
import com.example.mymap.util.Logger
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val TAG = "MapLibreView"

/**
 * MapLibre 지도 + 내 위치 마커 표시
 */
@Composable
fun MapLibreView(
    mapFilePath: String,
    myLocation: UserLocation?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    remember {
        MapLibre.getInstance(context)
        true
    }

    // MapView와 MapLibreMap 참조 저장
    val mapViewState = remember { MapViewState() }

    val mapView = remember {
        createMapView(context, mapFilePath) { map ->
            mapViewState.map = map
        }
    }

    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()

        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            Logger.d(TAG, "MapView destroyed")
        }
    }

    // 내 위치가 업데이트되면 마커 갱신 + 카메라 이동
    LaunchedEffect(myLocation) {
        val location = myLocation ?: return@LaunchedEffect
        val map = mapViewState.map ?: return@LaunchedEffect

        updateMyLocationMarker(map, location, mapViewState)

        // 내 위치로 카메라 이동
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                Constants.MapDefaults.MY_LOCATION_ZOOM
            )
        )
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

/**
 * MapView 상태 보관
 */
private class MapViewState {
    var map: MapLibreMap? = null
    var myLocationMarker: Marker? = null
}

/**
 * 내 위치 마커 업데이트
 */
private fun updateMyLocationMarker(
    map: MapLibreMap,
    location: UserLocation,
    state: MapViewState
) {
    // 기존 마커 제거
    state.myLocationMarker?.let { map.removeMarker(it) }

    // 새 마커 추가
    val marker = map.addMarker(
        MarkerOptions()
            .position(LatLng(location.latitude, location.longitude))
            .title("내 위치")
    )
    state.myLocationMarker = marker

    Logger.d(TAG, "Location marker updated: $location")
}

/**
 * MapView 인스턴스 생성
 */
private fun createMapView(
    context: Context,
    mapFilePath: String,
    onMapReady: (MapLibreMap) -> Unit
): MapView {
    return MapView(context).apply {
        onCreate(null)

        getMapAsync { map ->
            Logger.d(TAG, "Map ready, loading style from: $mapFilePath")

            val styleJson = buildMapStyle(mapFilePath)
            map.setStyle(Style.Builder().fromJson(styleJson)) {
                Logger.d(TAG, "Style loaded successfully")
                onMapReady(map)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(
                    LatLng(
                        Constants.MapDefaults.INITIAL_LATITUDE,
                        Constants.MapDefaults.INITIAL_LONGITUDE
                    )
                )
                .zoom(Constants.MapDefaults.INITIAL_ZOOM)
                .build()

            map.uiSettings.apply {
                isCompassEnabled = true
                isLogoEnabled = false
                isAttributionEnabled = true
                isRotateGesturesEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
            }
        }
    }
}

/**
 * 지도 스타일 JSON (기존 코드 그대로)
 */
private fun buildMapStyle(mapFilePath: String): String {
    return """
    {
      "version": 8,
      "name": "MyMap",
      "sources": {
        "openmaptiles": {
          "type": "vector",
          "url": "mbtiles:///$mapFilePath"
        }
      },
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": { "background-color": "#f8f4f0" }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "water",
          "paint": { "fill-color": "#a0c8f0" }
        },
        {
          "id": "landuse",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "landuse",
          "paint": { "fill-color": "#e8e0d0", "fill-opacity": 0.5 }
        },
        {
          "id": "park",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "park",
          "paint": { "fill-color": "#c8e6c0" }
        },
        {
          "id": "buildings",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "building",
          "minzoom": 13,
          "paint": { "fill-color": "#d8d0c0", "fill-outline-color": "#b8b0a0" }
        },
        {
          "id": "roads-minor",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "minor", "service", "track"],
          "minzoom": 13,
          "paint": { "line-color": "#ffffff", "line-width": 1 }
        },
        {
          "id": "roads-major",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "primary", "secondary", "tertiary"],
          "paint": {
            "line-color": "#ffffff",
            "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 16, 4]
          }
        },
        {
          "id": "highways",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "motorway", "trunk"],
          "paint": {
            "line-color": "#fcd16e",
            "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1, 16, 6]
          }
        }
      ]
    }
    """.trimIndent()
}
package com.example.mymap.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mymap.util.Constants
import com.example.mymap.util.Logger
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val TAG = "MapLibreView"

/**
 * MapLibre 지도를 Compose에서 사용할 수 있게 감싼 컴포넌트
 *
 * @param mapFilePath 로컬 mbtiles 파일 경로
 * @param modifier Compose Modifier
 */
@Composable
fun MapLibreView(
    mapFilePath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // MapLibre 초기화 (Application에서도 가능하지만 여기서 해도 OK)
    remember {
        MapLibre.getInstance(context)
        true
    }

    // 화면이 사라질 때 정리할 MapView 참조
    val mapView = remember { mutableMapViewOf(context, mapFilePath) }

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

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

/**
 * 지도 스타일을 인라인 JSON으로 정의
 * - 로컬 mbtiles 파일을 vector source로 사용
 * - 기본적인 도로/건물/배경 레이어 포함
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
          "paint": {
            "background-color": "#f8f4f0"
          }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "water",
          "paint": {
            "fill-color": "#a0c8f0"
          }
        },
        {
          "id": "landuse",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "landuse",
          "paint": {
            "fill-color": "#e8e0d0",
            "fill-opacity": 0.5
          }
        },
        {
          "id": "park",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "park",
          "paint": {
            "fill-color": "#c8e6c0"
          }
        },
        {
          "id": "buildings",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "building",
          "minzoom": 13,
          "paint": {
            "fill-color": "#d8d0c0",
            "fill-outline-color": "#b8b0a0"
          }
        },
        {
          "id": "roads-minor",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "minor", "service", "track"],
          "minzoom": 13,
          "paint": {
            "line-color": "#ffffff",
            "line-width": 1
          }
        },
        {
          "id": "roads-major",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "primary", "secondary", "tertiary"],
          "paint": {
            "line-color": "#ffffff",
            "line-width": [
              "interpolate", ["linear"], ["zoom"],
              8, 1,
              16, 4
            ]
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
            "line-width": [
              "interpolate", ["linear"], ["zoom"],
              5, 1,
              16, 6
            ]
          }
        }
      ]
    }
    """.trimIndent()
}

/**
 * MapView 인스턴스 생성 + 초기 설정
 */
private fun mutableMapViewOf(context: Context, mapFilePath: String): MapView {
    return MapView(context).apply {
        // onCreate은 AndroidView 내부에서 자동 호출되지 않으므로 수동 호출
        onCreate(null)

        getMapAsync { map ->
            Logger.d(TAG, "Map ready, loading style from: $mapFilePath")

            // 스타일 로드
            val styleJson = buildMapStyle(mapFilePath)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                Logger.d(TAG, "Style loaded: ${style.uri}")
            }

            // 초기 카메라 위치 (서울 시청)
            map.cameraPosition = CameraPosition.Builder()
                .target(
                    LatLng(
                        Constants.MapDefaults.INITIAL_LATITUDE,
                        Constants.MapDefaults.INITIAL_LONGITUDE
                    )
                )
                .zoom(Constants.MapDefaults.INITIAL_ZOOM)
                .build()

            // UI 설정
            map.uiSettings.apply {
                isCompassEnabled = true       // 나침반 표시
                isLogoEnabled = false         // MapLibre 로고 숨김 (선택)
                isAttributionEnabled = true   // 저작권 표시 (필수)
                isRotateGesturesEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
            }
        }
    }
}
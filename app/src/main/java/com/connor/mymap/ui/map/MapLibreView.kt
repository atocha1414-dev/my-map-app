package com.connor.mymap.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.UserLocation
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

private const val TAG = "MapLibreView"
private const val TRACK_SOURCE_ID = "my-track-source"
private const val TRACK_LAYER_ID = "my-track-layer"
private const val MY_LOCATION_SOURCE_ID = "my-location-source"
private const val MY_LOCATION_ACCURACY_LAYER_ID = "my-location-accuracy-layer"
private const val MY_LOCATION_DOT_LAYER_ID = "my-location-dot-layer"

/**
 * MapLibre 지도 + 내 위치 포인터(GeoJSON 기반) + 이동 경로 라인
 */
@Composable
fun MapLibreView(
    mapFilePath: String,
    myLocation: UserLocation?,
    trackPoints: List<TrackingPoint>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember {
        MapLibre.getInstance(context)
        true
    }

    // MapView와 MapLibreMap 참조 저장
    val mapViewState = remember { MapViewState() }

    val mapView = remember {
        createMapView(context, mapFilePath) { map, style ->
            mapViewState.map = map
            mapViewState.style = style
        }
    }

    // 변경 이유: DisposableEffect(Unit)로 onStart/onResume를 한 번만 부르면
    // Compose가 살아있는 동안 Activity가 onStop 되어도 MapView lifecycle이 따라가지 않아
    // 배터리/리소스가 새고 SDK 내부 상태가 어그러진다. Activity Lifecycle을 직접 구독한다.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            Logger.d(TAG, "MapView disposed")
        }
    }

    // 내 위치가 업데이트되면 마커 갱신 + 카메라 이동
    LaunchedEffect(myLocation) {
        val location = myLocation ?: return@LaunchedEffect
        val map = mapViewState.map ?: return@LaunchedEffect
        val style = mapViewState.style ?: return@LaunchedEffect

        updateMyLocationPointer(style, location)

        // 내 위치로 카메라 이동
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                Constants.MapDefaults.MY_LOCATION_ZOOM
            )
        )
    }

    LaunchedEffect(trackPoints) {
        val style = mapViewState.style ?: return@LaunchedEffect
        updateTrackLine(style, trackPoints)
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
    var style: Style? = null
}

/**
 * 내 위치 포인터 업데이트
 *
 * 변경 이유: MapLibre LocationComponent와 GeoJSON 레이어를 같이 쓰면 일부 기기에서
 * 점이 두 개 겹쳐 보였다. 호환성이 더 안정적인 GeoJSON 기반 단일 표시로 통일한다.
 */
private fun updateMyLocationPointer(
    style: Style,
    location: UserLocation
) {
    val source = style.getSourceAs<GeoJsonSource>(MY_LOCATION_SOURCE_ID)
        ?: GeoJsonSource(MY_LOCATION_SOURCE_ID, location.toMyLocationFeature()).also { newSource ->
            style.addSource(newSource)
            addMyLocationLayers(style)
        }

    source.setGeoJson(location.toMyLocationFeature())
    Logger.d(TAG, "Location pointer updated: $location")
}

private fun addMyLocationLayers(style: Style) {
    if (style.getLayer(MY_LOCATION_ACCURACY_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(MY_LOCATION_ACCURACY_LAYER_ID, MY_LOCATION_SOURCE_ID).withProperties(
                circleColor("#1A73E8"),
                circleOpacity(0.18f),
                circleRadius(
                    interpolate(
                        linear(),
                        zoom(),
                        stop(10, 18f),
                        stop(14, 40f),
                        stop(18, 90f)
                    )
                )
            )
        )
    }

    if (style.getLayer(MY_LOCATION_DOT_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(MY_LOCATION_DOT_LAYER_ID, MY_LOCATION_SOURCE_ID).withProperties(
                circleColor("#1A73E8"),
                circleRadius(8f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
                circleStrokeOpacity(1f)
            )
        )
    }
}

private fun UserLocation.toMyLocationFeature(): Feature {
    return Feature.fromGeometry(
        Point.fromLngLat(longitude, latitude)
    ).also { feature ->
        feature.addNumberProperty("accuracy", accuracy)
    }
}

private fun updateTrackLine(
    style: Style,
    trackPoints: List<TrackingPoint>
) {
    val source = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)
        ?: GeoJsonSource(TRACK_SOURCE_ID, emptyTrackFeatureCollection()).also { newSource ->
            style.addSource(newSource)
            style.addLayer(
                LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                    lineColor("#1976D2"),
                    lineWidth(5f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

    source.setGeoJson(trackPoints.toTrackFeatureCollection())
}

private fun List<TrackingPoint>.toTrackFeatureCollection(): FeatureCollection {
    if (size < 2) return emptyTrackFeatureCollection()

    val points = map { point ->
        Point.fromLngLat(point.longitude, point.latitude)
    }

    return FeatureCollection.fromFeature(
        Feature.fromGeometry(LineString.fromLngLats(points))
    )
}

private fun emptyTrackFeatureCollection(): FeatureCollection {
    return FeatureCollection.fromFeatures(emptyList())
}

/**
 * MapView 인스턴스 생성
 */
private fun createMapView(
    context: android.content.Context,
    mapFilePath: String,
    onMapReady: (MapLibreMap, Style) -> Unit
): MapView {
    return MapView(context).apply {
        onCreate(null)

        getMapAsync { map ->
            Logger.d(TAG, "Map ready, loading style from: $mapFilePath")

            val styleJson = buildMapStyle(mapFilePath)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                Logger.d(TAG, "Style loaded successfully")
                onMapReady(map, style)
                map.cameraPosition = CameraPosition.Builder()
                    .target(
                        LatLng(
                            Constants.MapDefaults.INITIAL_LATITUDE,
                            Constants.MapDefaults.INITIAL_LONGITUDE
                        )
                    )
                    .zoom(Constants.MapDefaults.INITIAL_ZOOM)
                    .build()
            }

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
 * 지도 스타일 JSON
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

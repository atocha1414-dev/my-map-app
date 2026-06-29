package com.connor.mymap.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.connor.mymap.util.buildOfflineMapStyle
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
    fitBounds: LatLngBounds? = null,
    onMapClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember {
        MapLibre.getInstance(context)
        true
    }

    // 변경 이유: MapLibre 스타일 로딩은 비동기다.
    // 일반 var 필드에 담으면 값이 채워져도 Compose가 모르므로 LaunchedEffect가 재실행되지 않아
    // "처음 1회 실행 → 그때 style==null → return → 영영 라인 안 그림" 버그가 난다.
    // mutableStateOf로 두고 LaunchedEffect의 key로 함께 사용한다.
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyle by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        createMapView(context, mapFilePath) { map, style ->
            mapLibreMap = map
            mapStyle = style
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
    // mapStyle을 키로 함께 묶어, 스타일이 늦게 로드되어도 한 번 더 실행되게 한다.
    LaunchedEffect(myLocation, mapStyle) {
        val location = myLocation ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = mapStyle ?: return@LaunchedEffect

        updateMyLocationPointer(style, location)

        // 내 위치로 카메라 이동
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                Constants.MapDefaults.MY_LOCATION_ZOOM
            )
        )
    }

    // 상세/재생 화면 진입 시 전체 경로가 한눈에 들어오도록 경계에 맞춰 카메라를 이동한다.
    // fitBounds가 null이면 즉시 반환하므로 홈 화면의 카메라 동작과 충돌하지 않는다.
    LaunchedEffect(fitBounds, mapStyle) {
        val bounds = fitBounds ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        mapStyle ?: return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 128))
    }

    // 단일 탭 토글: 드래그/핀치/줌은 OnMapClickListener로 잡히지 않으므로 안전하게 분리됨
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap
        val listener = MapLibreMap.OnMapClickListener {
            currentOnMapClick?.invoke()
            true
        }
        map?.addOnMapClickListener(listener)
        onDispose {
            map?.removeOnMapClickListener(listener)
        }
    }

    LaunchedEffect(trackPoints, mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        updateTrackLine(style, trackPoints)
        // 최신 GPS 포인트로 파란 위치 점을 카메라 이동 없이 업데이트한다.
        // myLocation(버튼 탭)과 달리 카메라는 그대로 두고 점만 움직인다.
        if (trackPoints.isNotEmpty()) {
            val latest = trackPoints.last()
            updateMyLocationPointer(
                style,
                UserLocation(latest.latitude, latest.longitude, latest.accuracy)
            )
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
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
    Logger.d(TAG, "Location pointer updated")
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
    val features = groupBy { it.segmentIndex }
        .values
        .filter { segment -> segment.size >= 2 }
        .map { segment ->
            val points = segment.map { point ->
                Point.fromLngLat(point.longitude, point.latitude)
            }
            Feature.fromGeometry(LineString.fromLngLats(points))
        }

    return FeatureCollection.fromFeatures(features)
}

private fun emptyTrackFeatureCollection(): FeatureCollection {
    return FeatureCollection.fromFeatures(emptyList())
}

/**
 * mbtiles의 center 메타데이터("lng,lat,zoom")로 초기 카메라를 만든다.
 * 다운로드한 지역(한국·미국 주 등)마다 다른 위치를 비추도록 한다. 실패 시 null → 기본값 폴백.
 */
private fun mbtilesInitialCamera(mapFilePath: String): CameraPosition? = runCatching {
    val center = android.database.sqlite.SQLiteDatabase.openDatabase(
        mapFilePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
    ).use { db ->
        db.rawQuery("SELECT value FROM metadata WHERE name='center'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } ?: return@runCatching null
    val parts = center.split(",")
    val lng = parts[0].trim().toDouble()
    val lat = parts[1].trim().toDouble()
    val zoom = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: Constants.MapDefaults.INITIAL_ZOOM
    CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build()
}.getOrNull()

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

            val styleJson = buildOfflineMapStyle(mapFilePath)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                Logger.d(TAG, "Style loaded successfully")
                onMapReady(map, style)
                // 초기 카메라: 다운로드된 mbtiles의 center 메타데이터(지역별로 다름)로 이동.
                // 지역 지도(예: 미국 주)를 받아도 엉뚱한 위치(서울)를 비춰 빈 화면이 되는 문제 방지.
                map.cameraPosition = mbtilesInitialCamera(mapFilePath)
                    ?: CameraPosition.Builder()
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


package com.connor.mymap.ui.footprints

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
import com.connor.mymap.util.buildOfflineMapStyle
import org.maplibre.android.MapLibre
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TAG = "FootprintsMapView"
private const val SOURCE_ID = "footprints-source"
private const val HALO_LAYER_ID = "footprints-halo"
private const val CORE_LAYER_ID = "footprints-core"

/**
 * 모든 세션의 경로를 "빛나는 라인"으로 한 지도에 겹쳐 그린다.
 * (HeatmapLayer는 일부 소프트웨어 GPU에서 네이티브 렌더 크래시가 있어,
 *  더 호환성이 높은 블러 LineLayer 2겹으로 글로우 효과를 낸다.)
 */
@Composable
fun FootprintsMapView(
    mapFilePath: String,
    routes: List<List<TrackingPoint>>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember {
        MapLibre.getInstance(context)
        true
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyle by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                val styleJson = buildOfflineMapStyle(mapFilePath)
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    Logger.d(TAG, "Footprints style loaded")
                    mapLibreMap = map
                    mapStyle = style
                }
                map.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = true
                    isCompassEnabled = false
                }
            }
        }
    }

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
            Logger.d(TAG, "Footprints MapView disposed")
        }
    }

    LaunchedEffect(routes, mapStyle) {
        val style = mapStyle ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        updateFootprints(style, routes)

        val all = routes.flatten()
        when {
            all.size >= 2 -> {
                val builder = LatLngBounds.Builder()
                all.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
                runCatching {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 140))
                }.onFailure { Logger.w(TAG, "fitBounds failed: ${it.message}") }
            }
            all.size == 1 -> map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(all[0].latitude, all[0].longitude), 14.0)
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun updateFootprints(style: Style, routes: List<List<TrackingPoint>>) {
    val collection = routes.toLineFeatureCollection()

    val existing = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(collection)
        return
    }

    style.addSource(GeoJsonSource(SOURCE_ID, collection))

    // 글로우(halo): 굵고 흐릿하고 옅은 청록 — 겹칠수록 밝아지는 발자취 느낌
    style.addLayer(
        LineLayer(HALO_LAYER_ID, SOURCE_ID).withProperties(
            lineColor("#16C9A6"),
            lineOpacity(0.18f),
            lineBlur(6f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineWidth(interpolate(linear(), zoom(), stop(8, 6f), stop(14, 13f), stop(18, 20f)))
        )
    )
    // 코어: 가늘고 밝은 라인
    style.addLayer(
        LineLayer(CORE_LAYER_ID, SOURCE_ID).withProperties(
            lineColor("#3DE8CF"),
            lineOpacity(0.85f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineWidth(interpolate(linear(), zoom(), stop(8, 1.6f), stop(14, 3.4f), stop(18, 5f)))
        )
    )
}

/** 세션·세그먼트별로 끊어 LineString을 만든다(일시정지 구간이 직선으로 이어지지 않도록). */
private fun List<List<TrackingPoint>>.toLineFeatureCollection(): FeatureCollection {
    val features = ArrayList<Feature>()
    for (route in this) {
        route.groupBy { it.segmentIndex }
            .values
            .filter { it.size >= 2 }
            .forEach { segment ->
                val pts = segment.map { Point.fromLngLat(it.longitude, it.latitude) }
                features.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
            }
    }
    return FeatureCollection.fromFeatures(features)
}

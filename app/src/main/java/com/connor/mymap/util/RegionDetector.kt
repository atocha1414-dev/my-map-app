package com.connor.mymap.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.connor.mymap.domain.model.MapCatalog
import com.connor.mymap.domain.model.MapRegion
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 현재 위치 → 국가(미국이면 주)를 판별해 카탈로그의 지역과 매칭한다.
 * 위치/지오코딩/매칭 실패 시 null → 호출부는 "직접 선택"으로 폴백해야 한다.
 *
 * 매칭 규칙:
 *  - 국가: ISO2 소문자 countryCode == MapCountry.id  (kr, us, jp …)
 *  - 지역 1개면 그 지역, 여러 개(미국)면 주(adminArea)로:
 *      regionId = "<country.id>-<adminArea 소문자, 공백→하이픈>"  (예: us-california, us-new-york)
 *    이는 Geofabrik 슬러그와 동일해 별도 매핑 테이블이 필요 없다.
 */
object RegionDetector {
    private const val LOCATION_FINE = android.Manifest.permission.ACCESS_FINE_LOCATION

    /** 위치 권한이 허용된 상태에서만 호출할 것. */
    @SuppressLint("MissingPermission")
    suspend fun detect(context: Context, catalog: MapCatalog): MapRegion? {
        val loc = currentLocation(context) ?: return null
        val addr = reverseGeocode(context, loc.latitude, loc.longitude) ?: return null
        val countryCode = addr.countryCode?.lowercase() ?: return null
        val country = catalog.countries.firstOrNull { it.id == countryCode } ?: return null

        if (country.regions.size == 1) return country.regions.first()

        val stateSlug = addr.adminArea?.lowercase()?.trim()?.replace(" ", "-") ?: return null
        val regionId = "${country.id}-$stateSlug"
        return country.regions.firstOrNull { it.id == regionId }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        val hasFineLocation = context.checkSelfPermission(LOCATION_FINE) == PackageManager.PERMISSION_GRANTED
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val priority = if (hasFineLocation) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val fusedLocation = suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { cont.resume(it) }     // null일 수 있음
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        }
        // 일부 에뮬레이터/기기는 Fused 현재 위치가 null이어도 platform provider에 마지막 위치가 남아 있다.
        // 최초 지도 자동 다운로드가 괜히 수동 선택으로 떨어지지 않도록 한 번 더 확인한다.
        fusedLocation ?: lastKnownPlatformLocation(context, hasFineLocation)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownPlatformLocation(context: Context, hasFineLocation: Boolean): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = if (hasFineLocation) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): Address? =
        withContext(Dispatchers.IO) {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            cont.resume(addresses.firstOrNull())
                        }
                        override fun onError(message: String?) {
                            cont.resume(null)
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
            }
        }
}

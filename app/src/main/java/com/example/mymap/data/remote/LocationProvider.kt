package com.example.mymap.data.remote

import android.annotation.SuppressLint
import android.content.Context
import com.example.mymap.domain.model.UserLocation
import com.example.mymap.util.Logger
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * GPS 위치 제공자
 * - FusedLocationProviderClient 사용 (Google Play Services)
 */
class LocationProvider(context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * 현재 위치 한 번 가져오기
     * - 권한이 있어야 호출 가능
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): UserLocation? =
        suspendCancellableCoroutine { continuation ->
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        Logger.w(TAG, "Location is null")
                        continuation.resume(null)
                    } else {
                        val userLocation = UserLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy
                        )
                        Logger.d(TAG, "Got location: $userLocation")
                        continuation.resume(userLocation)
                    }
                }
                .addOnFailureListener { e ->
                    Logger.e(TAG, "Failed to get location", e)
                    continuation.resume(null)
                }
        }
}
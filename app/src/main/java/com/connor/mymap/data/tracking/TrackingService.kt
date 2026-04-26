package com.connor.mymap.data.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.connor.mymap.MainActivity
import com.connor.mymap.R
import com.connor.mymap.data.local.TrackingStorage
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
import com.connor.mymap.util.TrackingCalculator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var trackingStorage: TrackingStorage
    private var lastAcceptedPoint: TrackingPoint? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                val point = TrackingPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestampMillis = location.time
                )

                if (!TrackingCalculator.shouldAcceptPoint(lastAcceptedPoint, point)) {
                    Logger.d(TAG, "Track point ignored by GPS filter: $point")
                    return@forEach
                }

                lastAcceptedPoint = point
                TrackingState.addTrackPoint(point)
                serviceScope.launch { trackingStorage.appendPoint(point) }
                Logger.d(TAG, "Track point saved: $point")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        trackingStorage = TrackingStorage(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> startTracking()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        TrackingState.setTracking(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (!hasFineLocationPermission()) {
            Logger.w(TAG, "Cannot start tracking without fine location permission")
            stopSelf()
            return
        }

        // 정책 반영: 백그라운드 경로 기록은 사용자가 명시적으로 시작한 경우에만
        // ForegroundService로 실행하고, 추적 중임을 고정 알림으로 계속 표시한다.
        startForeground(NOTIFICATION_ID, buildNotification())

        // 변경 이유: 시스템이 START_STICKY로 서비스를 재시작하면 이 메서드가 다시 불리는데,
        // 이 때 진행 중인 세션의 시작 시각을 덮어쓰면 UI 타이머가 리셋된다.
        // 디스크에 저장된 세션 시작 시각이 있으면 재사용하고, 없으면 새로 시작한다.
        // (runBlocking은 onStartCommand 동기 흐름을 유지하기 위해 짧은 파일 IO 한 번에만 사용)
        val resumedStart = runBlocking { trackingStorage.readSessionStart() }
        val startedAt = if (resumedStart != null) {
            Logger.d(TAG, "Resuming session that started at $resumedStart")
            resumedStart
        } else {
            val now = System.currentTimeMillis()
            serviceScope.launch { trackingStorage.saveSessionStart(now) }
            now
        }
        TrackingState.startTracking(startedAt)

        lastAcceptedPoint = runBlocking { trackingStorage.readPoints() }.lastOrNull()

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_INTERVAL_MILLIS
        )
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        Logger.d(TAG, "Background tracking started")
    }

    private fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)
        TrackingState.setTracking(false)
        serviceScope.launch { trackingStorage.clearSession() }
        Logger.d(TAG, "Background tracking stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle("MyMap 이동 경로 기록 중")
            .setContentText("앱이 닫혀 있어도 현재 위치를 기기 안에 저장합니다.")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification_tracking, "기록 중지", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "이동 경로 기록",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "백그라운드 이동 경로 기록 상태를 표시합니다."
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MILLIS = 5_000L
        private const val FASTEST_LOCATION_INTERVAL_MILLIS = 2_000L
        private const val MIN_DISTANCE_METERS = 5f

        const val ACTION_STOP = "com.connor.mymap.action.STOP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

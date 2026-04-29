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
import android.location.Location
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class TrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var trackingStorage: TrackingStorage
    private lateinit var notificationManager: NotificationManager
    @Volatile
    private var lastAcceptedPoint: TrackingPoint? = null
    // 변경 이유: serviceScope.launch(Dispatchers.Default)에서 쓰고
    // locationCallback(mainLooper)에서 읽는다. 메모리 가시성 보장 위해 @Volatile 필요.
    @Volatile
    private var activeSegmentIndex = 0
    @Volatile
    private var isStartingTracking = false
    @Volatile
    private var isLocationUpdatesRegistered = false
    private var startJob: Job? = null
    private var notificationTickerJob: Job? = null
    @Volatile
    private var trackingStartedAtMillis: Long = 0L
    @Volatile
    private var totalDistanceMeters: Float = 0f
    @Volatile
    private var tickerFrameIndex: Int = 0

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                val point = TrackingPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestampMillis = location.time,
                    segmentIndex = activeSegmentIndex
                )

                if (!TrackingCalculator.shouldAcceptPoint(lastAcceptedPoint, point)) {
                    Logger.d(TAG, "Track point ignored by GPS filter")
                    return@forEach
                }

                val previous = lastAcceptedPoint
                if (previous != null && previous.segmentIndex == point.segmentIndex) {
                    totalDistanceMeters += distanceBetween(previous, point)
                }

                lastAcceptedPoint = point
                TrackingState.addTrackPoint(point)
                serviceScope.launch {
                    trackingStorage.appendPoint(point)
                }
                updateForegroundNotification()
                Logger.d(TAG, "Track point saved, accuracy=${point.accuracy}m")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        trackingStorage = TrackingStorage(this)
        notificationManager = getSystemService(NotificationManager::class.java)
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
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        fusedClient.removeLocationUpdates(locationCallback)
        isLocationUpdatesRegistered = false
        isStartingTracking = false
        TrackingState.setTracking(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (isStartingTracking || isLocationUpdatesRegistered) {
            Logger.d(TAG, "Tracking already starting or running")
            return
        }

        if (!hasFineLocationPermission()) {
            Logger.w(TAG, "Cannot start tracking without fine location permission")
            stopSelf()
            return
        }

        // 정책 반영: 백그라운드 경로 기록은 사용자가 명시적으로 시작한 경우에만
        // ForegroundService로 실행하고, 추적 중임을 고정 알림으로 계속 표시한다.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                tickerText = buildTickerText(0L),
                statusText = "서비스 시작 중..."
            )
        )
        isStartingTracking = true

        startJob = serviceScope.launch {
            try {
                // 변경 이유: 서비스 시작 경로에서 runBlocking으로 파일 IO를 기다리면
                // 트랙 파일이 커졌을 때 메인 스레드 지연/ANR 위험이 있다.
                // 세션 복구와 마지막 포인트 조회를 백그라운드에서 끝낸 뒤 위치 업데이트를 등록한다.
                val resumedStart = trackingStorage.readSessionStart()
                val startedAt = resumedStart ?: System.currentTimeMillis().also { now ->
                    trackingStorage.saveSessionStart(now)
                }
                if (resumedStart != null) {
                    Logger.d(TAG, "Resuming existing tracking session")
                }

                trackingStartedAtMillis = startedAt
                val lastPoint = trackingStorage.readLastPoint()
                activeSegmentIndex = if (resumedStart != null) {
                    lastPoint?.segmentIndex ?: 0
                } else {
                    (lastPoint?.segmentIndex ?: -1) + 1
                }
                TrackingState.startTracking(startedAt)
                lastAcceptedPoint = if (resumedStart != null) lastPoint else null

                totalDistanceMeters = if (resumedStart != null) {
                    val existingPoints = trackingStorage.readPoints()
                    TrackingCalculator.calculateStats(existingPoints).distanceMeters
                } else {
                    0f
                }
                tickerFrameIndex = 0
                startNotificationTicker()
                updateForegroundNotification()

                val request = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    LOCATION_INTERVAL_MILLIS
                )
                    .setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL_MILLIS)
                    .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                    .build()

                fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
                isLocationUpdatesRegistered = true
                Logger.d(TAG, "Background tracking started")
            } catch (e: SecurityException) {
                Logger.w(TAG, "Location permission revoked while starting tracking", e)
                stopSelf()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start background tracking", e)
                stopSelf()
            } finally {
                isStartingTracking = false
            }
        }
    }

    private fun stopTracking() {
        startJob?.cancel()
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        fusedClient.removeLocationUpdates(locationCallback)
        isLocationUpdatesRegistered = false
        isStartingTracking = false
        TrackingState.setTracking(false)
        serviceScope.launch {
            // 변경 이유: clearSession()을 비동기로 던진 직후 stopSelf()를 호출하면
            // onDestroy()에서 serviceScope가 취소되어 current_session.txt가 남을 수 있다.
            // 세션 마커 삭제를 끝낸 뒤 서비스를 종료해 유령 세션 복구를 막는다.
            trackingStorage.clearSession()
            Logger.d(TAG, "Background tracking stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startNotificationTicker() {
        notificationTickerJob?.cancel()
        notificationTickerJob = serviceScope.launch {
            while (isLocationUpdatesRegistered || isStartingTracking) {
                updateForegroundNotification()
                delay(NOTIFICATION_UPDATE_INTERVAL_MILLIS)
            }
        }
    }

    private fun updateForegroundNotification() {
        val startedAt = trackingStartedAtMillis
        if (startedAt <= 0L) return
        val elapsedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val tickerText = buildTickerText(elapsedMillis)
        val statusText = if (isLocationUpdatesRegistered) {
            "서비스 실행 중 · [기록 중지] 버튼으로 종료"
        } else {
            "서비스 시작 중..."
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                tickerText = tickerText,
                statusText = statusText
            )
        )
    }

    private fun buildTickerText(elapsedMillis: Long): String {
        val frame = TAXI_FRAMES[tickerFrameIndex % TAXI_FRAMES.size]
        tickerFrameIndex = (tickerFrameIndex + 1) % TAXI_FRAMES.size
        return "$frame ${formatDistance(totalDistanceMeters)} · ${formatDuration(elapsedMillis)}"
    }

    private fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters >= 1_000f) {
            String.format(Locale.KOREA, "%.2fkm", distanceMeters / 1_000f)
        } else {
            "${distanceMeters.toInt()}m"
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.KOREA, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.KOREA, "%02d:%02d", minutes, seconds)
        }
    }

    private fun distanceBetween(from: TrackingPoint, to: TrackingPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            results
        )
        return results[0]
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(
        tickerText: String,
        statusText: String
    ): Notification {
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
            // 요청 반영: 윗줄(Title)에 애니메이션/거리/시간, 아랫줄(Text)에 서비스 상태를 배치
            .setContentTitle(tickerText)
            .setContentText(statusText)
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
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val LOCATION_INTERVAL_MILLIS = 5_000L
        private const val FASTEST_LOCATION_INTERVAL_MILLIS = 2_000L
        private const val MIN_DISTANCE_METERS = 5f
        private val TAXI_FRAMES = listOf("🚕·", "🚕··", "🚕···", "🚕··")

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

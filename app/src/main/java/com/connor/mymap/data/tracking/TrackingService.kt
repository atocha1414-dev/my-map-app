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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.connor.mymap.MainActivity
import com.connor.mymap.R
import com.connor.mymap.data.local.TrackingStorage
import com.connor.mymap.util.Formats
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
import com.connor.mymap.util.TrackingCalculator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
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
    // 앵커 기반 + 이동 확정 필터(정지 중 GPS 드리프트로 제자리 선이 그려지는 것을 막는다).
    private val pointFilter = TrackPointFilter()
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
                // 변경 이유: MIN_DISTANCE_METERS=0 이후 FusedProvider가 캐시된(stale) 첫 픽스를 즉시 전달.
                // 이 픽스의 timestamp는 수 분~수십 분 전일 수 있어, PlaybackViewModel.totalMs(포인트 ts 기반)가
                // 실제 세션 길이보다 훨씬 길게 표시되는 문제가 있었다. elapsedRealtime 기준으로 오래된 픽스는 버린다.
                val ageMillis =
                    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
                if (ageMillis > MAX_LOCATION_AGE_MILLIS) {
                    Logger.d(TAG, "Stale location dropped, age=${ageMillis}ms")
                    return@forEach
                }

                val point = TrackingPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestampMillis = location.time,
                    segmentIndex = activeSegmentIndex
                )

                val elapsedSinceStartMillis = if (trackingStartedAtMillis > 0L) {
                    (point.timestampMillis - trackingStartedAtMillis).coerceAtLeast(0L)
                } else {
                    0L
                }
                val candidateSpeedMetersPerSecond = if (location.hasSpeed()) location.speed else null

                // 앵커 기반 + 이동 확정 필터로 정지 중 드리프트를 거른다.
                // (정확도·속도 sanity는 필터 내부에서 shouldAcceptPoint로 함께 처리한다.)
                val committed = pointFilter.process(
                    candidate = point,
                    elapsedSinceStartMillis = elapsedSinceStartMillis,
                    candidateSpeedMetersPerSecond = candidateSpeedMetersPerSecond
                )
                if (committed == null) {
                    Logger.d(TAG, "Track point held/ignored by stationary-aware filter")
                    return@forEach
                }

                val previous = lastAcceptedPoint
                if (previous != null && previous.segmentIndex == committed.segmentIndex) {
                    totalDistanceMeters += distanceBetween(previous, committed)
                }

                lastAcceptedPoint = committed
                TrackingState.addTrackPoint(committed)
                serviceScope.launch {
                    trackingStorage.appendPoint(committed)
                }
                updateForegroundNotification()
                Logger.d(TAG, "Track point saved, accuracy=${committed.accuracy}m")
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
                // 필터 앵커를 현재 기준점(재개 시 마지막 점, 신규 시작 시 null)으로 초기화한다.
                pointFilter.reset(lastAcceptedPoint)

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
                    // 변경 이유: 트래킹 시작 직후 품질이 낮은 첫 GPS를 곧바로 쓰면 경로가 튀기 쉽다.
                    // 정확한 위치를 우선 기다리고(FINE granularity), 수집 간격도 촘촘하게 조정한다.
                    .setWaitForAccurateLocation(true)
                    .setGranularity(Granularity.GRANULARITY_FINE)
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

    private fun formatDistance(distanceMeters: Float): String = Formats.distance(distanceMeters)

    private fun formatDuration(durationMillis: Long): String = Formats.duration(durationMillis)

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
        // 정확도 우선 프리셋
        private const val LOCATION_INTERVAL_MILLIS = 3_000L
        private const val FASTEST_LOCATION_INTERVAL_MILLIS = 1_000L
        // 변경 이유: FusedProvider 단계에서 거리 컷오프를 두면 천천히 걷기 시작할 때 첫 몇 미터가 누락된다.
        // 노이즈 필터링은 TrackingCalculator.MIN_ACCEPTED_DISTANCE_METERS에서 이미 처리하므로
        // 여기서는 0으로 두고 모든 업데이트를 받아 더 촘촘한 트랙을 만든다.
        private const val MIN_DISTANCE_METERS = 0f
        // 변경 이유: 캐시된 stale 픽스를 거르는 임계값. 5초보다 오래된 측정은 새 트랙에 부적절.
        private const val MAX_LOCATION_AGE_MILLIS = 5_000L
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

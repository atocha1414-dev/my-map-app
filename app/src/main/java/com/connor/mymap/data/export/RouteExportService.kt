package com.connor.mymap.data.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.connor.mymap.MainActivity
import com.connor.mymap.R
import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Formats
import com.connor.mymap.util.Logger
import com.connor.mymap.util.TrackingCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 경로 영상 내보내기를 포그라운드 서비스로 실행한다.
 * 화면(상세 재생)을 벗어나도 렌더링이 계속되고, 진행 상황을 알림으로 보여준다.
 * 결과/진행 상태는 RouteExportManager(전역)로 공유해, 화면이 열려 있으면 인앱 UI에도 반영된다.
 */
class RouteExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notificationManager: NotificationManager
    private var exportJob: Job? = null
    @Volatile
    private var currentSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelExport()
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentSessionId = sessionId
        // FGS는 시작 직후(5~10초 내) startForeground 필수.
        startForeground(NOTIFICATION_ID, buildProgressNotification(0f))
        exportJob = scope.launch { runExport(sessionId) }
        return START_NOT_STICKY
    }

    /** 알림의 [취소]로 호출. 진행 중인 렌더를 중단하고 상태를 Idle로 되돌린 뒤 서비스를 종료한다. */
    private fun cancelExport() {
        val sid = currentSessionId
        exportJob?.cancel()
        if (sid != null) RouteExportManager.consume(sid)
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runExport(sessionId: String) {
        try {
            RouteExportManager.updateProgress(sessionId, 0f)

            val points = TrackingHistoryStorage(this).loadPoints(sessionId, maxPoints = 0)
            if (points.size < 2) {
                finishError(sessionId, "내보낼 경로가 없습니다.")
                return
            }
            val mapFile = MapFileStorage(this).getMapFile(Constants.Map.DEFAULT_MAP_FILENAME)
            if (!mapFile.exists()) {
                finishError(sessionId, "지도 파일이 없습니다.")
                return
            }

            val bounds = routeBoundsOf(points)
            val stats = TrackingCalculator.calculateStats(points)
            val title = Formats.dateTime(points.first().timestampMillis)
            val subtitle = "${Formats.distance(stats.distanceMeters)} · ${Formats.duration(stats.durationMillis)}"
            val dateTaken = points.first().timestampMillis

            var lastNotifiedPct = -1
            val done = GalleryVideoExporter(this).exportToGallery(
                mapFilePath = mapFile.absolutePath,
                points = points,
                bounds = bounds,
                title = title,
                subtitle = subtitle,
                dateTakenMillis = dateTaken,
                onProgress = { p ->
                    RouteExportManager.updateProgress(sessionId, p)
                    val pct = (p * 100).toInt()
                    if (pct != lastNotifiedPct) {
                        lastNotifiedPct = pct
                        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(p))
                    }
                }
            )

            RouteExportManager.finish(sessionId, done)
            showResultNotification(done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Background export failed", e)
            finishError(sessionId, e.message ?: "내보내기에 실패했습니다.")
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishError(sessionId: String, message: String) {
        RouteExportManager.finish(sessionId, ExportState.Error(message))
        notificationManager.notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_tracking)
                .setContentTitle("내보내기 실패")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showResultNotification(done: ExportState.Done) {
        val viewUri = done.galleryUri ?: done.shareUri
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle(if (done.savedToGallery) "영상 저장 완료" else "영상 생성 완료")
            .setContentText(
                if (done.savedToGallery) "사진첩(Movies/MyMap)에 저장했어요. 눌러서 보기"
                else "영상을 만들었어요. 눌러서 열기"
            )
            .setContentIntent(viewPendingIntent(viewUri))
            .addAction(0, "공유", sharePendingIntent(done.shareUri))
            .setAutoCancel(true)
        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun viewPendingIntent(uri: Uri): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "영상 보기")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, REQ_VIEW, chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun sharePendingIntent(uri: Uri): PendingIntent {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, "경로 영상 공유")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, REQ_SHARE, chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildProgressNotification(progress: Float): Notification {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, REQ_OPEN, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = Intent(this, RouteExportService::class.java).setAction(ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(
            this, REQ_CANCEL, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle("경로 영상 내보내는 중 $pct%")
            // 앱을 닫아도 백그라운드에서 계속된다는 사실을 알려준다.
            .setContentText("앱을 닫아도 백그라운드에서 계속 진행됩니다")
            .setProgress(100, pct, progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "취소", cancelPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "영상 내보내기",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "경로 영상 내보내기 진행 상황을 표시합니다."
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RouteExportService"
        private const val CHANNEL_ID = "route_export_channel"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val REQ_OPEN = 10
        private const val REQ_VIEW = 11
        private const val REQ_SHARE = 12
        private const val REQ_CANCEL = 13
        private const val EXTRA_SESSION_ID = "extra_session_id"
        const val ACTION_CANCEL = "com.connor.mymap.action.CANCEL_EXPORT"

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, RouteExportService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

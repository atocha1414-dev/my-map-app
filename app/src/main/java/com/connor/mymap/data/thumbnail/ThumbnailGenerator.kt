package com.connor.mymap.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import com.connor.mymap.data.local.ThumbnailStorage
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
import com.connor.mymap.util.buildOfflineMapStyle
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 완료된 트래킹 세션의 썸네일 PNG를 생성한다.
 * MapSnapshotter로 지도 비트맵을 만들고 그 위에 경로를 직접 그려 PNG로 저장한다.
 *
 * 비용이 큰 작업이므로 Mutex로 직렬화한다.
 * 메모리에 동시에 두 개의 MapSnapshotter가 존재하면 OOM 위험이 크다.
 */
class ThumbnailGenerator(
    private val context: Context,
    private val mapFilePath: String,
    private val thumbnailStorage: ThumbnailStorage
) {

    private val mutex = Mutex()
    private val sizePx: Int =
        (THUMBNAIL_SIZE_DP * context.resources.displayMetrics.density).toInt()

    /**
     * 세션 썸네일을 생성해 PNG로 저장한다.
     * 이미 존재하면 기존 파일을 그대로 반환한다.
     */
    suspend fun generate(
        sessionId: String,
        points: List<TrackingPoint>
    ): File? {
        if (points.isEmpty()) return null

        val target = thumbnailStorage.getFile(sessionId)
        if (target.exists()) return target

        return mutex.withLock {
            // 락을 잡은 사이 다른 호출에서 같은 파일을 만들었을 수 있다.
            if (target.exists()) return@withLock target

            try {
                val bounds = computePaddedBounds(points)
                val mapBitmap = takeMapSnapshot(bounds)
                val withPath = withContext(Dispatchers.Default) {
                    drawPathOverlay(mapBitmap, points, bounds)
                }
                if (withPath !== mapBitmap) mapBitmap.recycle()

                withContext(Dispatchers.IO) {
                    FileOutputStream(target).use { out ->
                        withPath.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    }
                }
                withPath.recycle()
                target
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to generate thumbnail for session $sessionId", e)
                if (target.exists()) target.delete()
                null
            }
        }
    }

    /**
     * 트랙의 위경도 범위에 여백(15%)을 더해 LatLngBounds를 만든다.
     * 여백이 없으면 경로가 썸네일 가장자리에 붙어서 답답해 보인다.
     */
    private fun computePaddedBounds(points: List<TrackingPoint>): LatLngBounds {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }

        // 단일 위치 또는 매우 좁은 범위는 최소 영역을 강제한다.
        val latRange = (maxLat - minLat).coerceAtLeast(MIN_RANGE_DEG)
        val lonRange = (maxLon - minLon).coerceAtLeast(MIN_RANGE_DEG)

        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0
        val halfLat = latRange / 2.0 * (1.0 + PADDING_RATIO * 2.0)
        val halfLon = lonRange / 2.0 * (1.0 + PADDING_RATIO * 2.0)

        return LatLngBounds.from(
            centerLat + halfLat, // north
            centerLon + halfLon, // east
            centerLat - halfLat, // south
            centerLon - halfLon  // west
        )
    }

    /**
     * MapSnapshotter는 메인 스레드에서 만들어야 하고 콜백도 메인 스레드에서 온다.
     */
    private suspend fun takeMapSnapshot(bounds: LatLngBounds): Bitmap =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val styleJson = buildOfflineMapStyle(mapFilePath)
                val options = MapSnapshotter.Options(sizePx, sizePx)
                    .withRegion(bounds)
                    .withStyleBuilder(Style.Builder().fromJson(styleJson))
                    .withLogo(false)

                val snapshotter = NoOverlayMapSnapshotter(context, options)
                cont.invokeOnCancellation {
                    runCatching { snapshotter.cancel() }
                }

                snapshotter.start(
                    object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            cont.resumeOnce { snapshot.bitmap }
                            runCatching { snapshotter.cancel() }
                        }
                    },
                    object : MapSnapshotter.ErrorHandler {
                        override fun onError(error: String) {
                            cont.resumeWithExceptionOnce {
                                IllegalStateException("MapSnapshotter error: $error")
                            }
                            runCatching { snapshotter.cancel() }
                        }
                    }
                )
            }
        }

    /**
     * 지도 비트맵 위에 트래킹 경로를 그린다.
     * MapSnapshotter가 withRegion으로 그린 영역과 동일한 bounds를 기준으로
     * 단순 선형 보간한다(작은 영역에서 mercator 왜곡은 무시 가능).
     */
    private fun drawPathOverlay(
        mapBitmap: Bitmap,
        points: List<TrackingPoint>,
        bounds: LatLngBounds
    ): Bitmap {
        val result = mapBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return mapBitmap
        val canvas = android.graphics.Canvas(result)
        val density = context.resources.displayMetrics.density

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(PATH_COLOR)
            strokeWidth = PATH_WIDTH_DP * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val north = bounds.latitudeNorth
        val south = bounds.latitudeSouth
        val east = bounds.longitudeEast
        val west = bounds.longitudeWest
        val w = result.width.toFloat()
        val h = result.height.toFloat()
        val lonSpan = (east - west).takeIf { it > 0.0 } ?: 1.0
        val latSpan = (north - south).takeIf { it > 0.0 } ?: 1.0

        fun toX(lon: Double) = ((lon - west) / lonSpan * w).toFloat()
        fun toY(lat: Double) = ((north - lat) / latSpan * h).toFloat()

        // 일시정지로 끊긴 segment끼리는 직선으로 잇지 않는다.
        points.groupBy { it.segmentIndex }.values.forEach { segment ->
            if (segment.size < 2) return@forEach
            val path = android.graphics.Path()
            segment.forEachIndexed { i, p ->
                val x = toX(p.longitude)
                val y = toY(p.latitude)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        return result
    }

    private inline fun <T> CancellableContinuation<T>.resumeOnce(producer: () -> T) {
        if (isActive) resume(producer())
    }

    private inline fun <T> CancellableContinuation<T>.resumeWithExceptionOnce(
        producer: () -> Throwable
    ) {
        if (isActive) resumeWithException(producer())
    }

    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val THUMBNAIL_SIZE_DP = 200
        private const val PADDING_RATIO = 0.15
        private const val MIN_RANGE_DEG = 0.0005 // ≈ 50m, 단일 지점도 최소 영역 보장
        private const val PATH_COLOR = "#1976D2"
        private const val PATH_WIDTH_DP = 3f
        private const val PNG_QUALITY = 100
    }
}

/**
 * MapLibre 11.5.0의 MapSnapshotter는 snapshot 완료 직후 로고/attribution overlay를
 * 자동으로 그리는데, 일부 환경에서 logo bitmap decode가 null이 되어 SDK 내부 NPE가 난다.
 * 변경 이유: 썸네일 생성 실패가 앱 전체 크래시로 번지지 않도록 자동 overlay를 건너뛰고,
 * 트래킹 경로 overlay는 ThumbnailGenerator가 직접 그린다.
 */
private class NoOverlayMapSnapshotter(
    context: Context,
    options: MapSnapshotter.Options
) : MapSnapshotter(context, options) {
    override fun addOverlay(mapSnapshot: MapSnapshot) = Unit
}

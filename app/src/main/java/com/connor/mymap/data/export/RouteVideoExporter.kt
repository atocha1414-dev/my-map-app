package com.connor.mymap.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.buildOfflineMapStyle
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jcodec.api.android.AndroidSequenceEncoder
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 경로재생을 mp4로 내보낸다.
 *
 * 방식: 경로 전체에 맞춘 지도 스냅샷 1장을 띄우고(MapSnapshotter),
 * 그 위에 '시작→끝'으로 진행하는 점·트레일을 프레임마다 그려 JCodec으로 mp4(H.264) 인코딩.
 * 카메라가 고정이라 스냅샷이 1장이면 충분해 빠르고 안정적이다.
 */
class RouteVideoExporter(private val context: Context) {

    suspend fun export(
        mapFilePath: String,
        points: List<TrackingPoint>,
        bounds: LatLngBounds,
        title: String,
        subtitle: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        require(points.size >= 2) { "재생할 포인트가 부족합니다." }

        onProgress(0.02f)
        // 1) 경로 전체에 맞춘 지도 스냅샷
        val snapshot = takeSnapshot(mapFilePath, bounds, WIDTH, HEIGHT)
        val base = snapshot.bitmap
        val w = base.width
        val h = base.height
        onProgress(0.10f)

        // 2) 모든 포인트의 화면 픽셀 좌표를 미리 투영
        val px = points.map { snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude)) }

        val t0 = points.first().timestampMillis
        val totalMs = (points.last().timestampMillis - t0).coerceAtLeast(1L)

        // 3) 프레임 렌더 + 인코딩
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "mymap_route_${System.currentTimeMillis()}.mp4")
        val encoder = AndroidSequenceEncoder.createSequenceEncoder(outFile, FPS)

        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)

        val totalFrames = FPS * DURATION_SEC
        var cursor = 0
        try {
            for (i in 0 until totalFrames) {
                val p = if (totalFrames <= 1) 1f else i.toFloat() / (totalFrames - 1)
                val targetMs = (p * totalMs).toLong()

                // head 위치(시간 기반) 계산
                while (cursor < points.lastIndex &&
                    (points[cursor + 1].timestampMillis - t0) <= targetMs
                ) cursor++

                val idx = cursor
                val sameSeg = idx < points.lastIndex &&
                    points[idx].segmentIndex == points[idx + 1].segmentIndex
                val head: PointF = if (sameSeg) {
                    val segStart = points[idx].timestampMillis - t0
                    val segEnd = points[idx + 1].timestampMillis - t0
                    val f = ((targetMs - segStart).toFloat() / (segEnd - segStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    PointF(
                        px[idx].x + (px[idx + 1].x - px[idx].x) * f,
                        px[idx].y + (px[idx + 1].y - px[idx].y) * f
                    )
                } else px[idx]

                drawFrame(canvas, base, points, px, idx, head, title, subtitle, w, h)
                encoder.encodeImage(frame)

                if (i % 4 == 0) onProgress(0.10f + 0.88f * p)
            }
        } finally {
            encoder.finish()
            frame.recycle()
        }
        onProgress(1f)
        outFile
    }

    private fun drawFrame(
        canvas: Canvas,
        base: Bitmap,
        points: List<TrackingPoint>,
        px: List<PointF>,
        headIdx: Int,
        head: PointF,
        title: String,
        subtitle: String,
        w: Int,
        h: Int,
    ) {
        canvas.drawBitmap(base, 0f, 0f, null)

        // 전체 경로(옅게)
        drawRoute(canvas, points, px, 0, points.lastIndex, null, faintPaint)
        // 진행된 경로(진하게) + head까지
        drawRoute(canvas, points, px, 0, headIdx, head, brightPaint)

        // 시작 마커
        canvas.drawCircle(px[0].x, px[0].y, 16f, startFill)
        canvas.drawCircle(px[0].x, px[0].y, 16f, markerRing)

        // head 마커
        canvas.drawCircle(head.x, head.y, 22f, headHalo)
        canvas.drawCircle(head.x, head.y, 13f, headFill)
        canvas.drawCircle(head.x, head.y, 13f, markerRing)

        // 캡션(하단)
        val pad = 28f
        val boxH = 132f
        val box = RectF(pad, h - boxH - pad, w - pad, h - pad)
        canvas.drawRoundRect(box, 28f, 28f, captionBg)
        canvas.drawText(title, box.left + 30f, box.top + 54f, titlePaint)
        canvas.drawText(subtitle, box.left + 30f, box.top + 102f, subtitlePaint)
        // 워터마크
        canvas.drawText("MyMap", w - pad - 22f, pad + 44f, watermarkPaint)
    }

    /** points[from..to] 구간을 세그먼트 경계를 끊어 그린다. head가 있으면 마지막에 이어 그린다. */
    private fun drawRoute(
        canvas: Canvas,
        points: List<TrackingPoint>,
        px: List<PointF>,
        from: Int,
        to: Int,
        head: PointF?,
        paint: Paint,
    ) {
        if (to < from) return
        val path = Path()
        var started = false
        var prevSeg = Int.MIN_VALUE
        for (i in from..to) {
            val seg = points[i].segmentIndex
            if (!started || seg != prevSeg) {
                path.moveTo(px[i].x, px[i].y)
                started = true
            } else {
                path.lineTo(px[i].x, px[i].y)
            }
            prevSeg = seg
        }
        if (head != null && to in points.indices &&
            (to >= points.lastIndex || points[to].segmentIndex == points.getOrNull(to + 1)?.segmentIndex)
        ) {
            path.lineTo(head.x, head.y)
        }
        canvas.drawPath(path, paint)
    }

    private suspend fun takeSnapshot(
        mapFilePath: String,
        bounds: LatLngBounds,
        width: Int,
        height: Int,
    ): MapSnapshot = withContext(Dispatchers.Main) {
        MapLibre.getInstance(context)
        suspendCancellableCoroutine { cont: CancellableContinuation<MapSnapshot> ->
            val options = MapSnapshotter.Options(width, height)
                .withPixelRatio(1f)
                .withRegion(bounds)
                // 변경 이유: MapLibre가 스냅샷에 로고/저작권 오버레이를 그릴 때
                // createScaledLogo에서 NPE가 나 앱이 죽는다(내부 메인스레드라 catch 불가).
                // 로고 오버레이를 꺼서 우회한다.
                .withLogo(false)
                .withStyleBuilder(Style.Builder().fromJson(buildOfflineMapStyle(mapFilePath)))
            // NoOverlayMapSnapshotter: 로고/저작권 오버레이 그리기(createScaledLogo NPE)를 건너뛴다.
            val snapshotter = NoOverlayMapSnapshotter(context, options)
            snapshotter.start(
                { snapshot -> if (cont.isActive) cont.resume(snapshot) },
                { error -> if (cont.isActive) cont.resumeWithException(RuntimeException("지도 스냅샷 실패: $error")) }
            )
            cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
        }
    }

    // ─── paints ───
    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#551F6FEB")
        strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#1F6FEB")
        strokeWidth = 12f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val startFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 6f
    }
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A73E8") }
    private val headHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#331A73E8") }
    private val captionBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3000000") }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF"); textSize = 30f
    }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF"); textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT
        setShadowLayer(6f, 0f, 2f, Color.parseColor("#88000000"))
    }

    companion object {
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val FPS = 24
        private const val DURATION_SEC = 15
    }
}

/**
 * MapLibre 11.5.0 MapSnapshotter는 스냅샷 직후 로고/attribution 오버레이를 자동으로 그리는데,
 * 일부 환경에서 logo bitmap decode가 null이 되어 SDK 내부 createScaledLogo에서 NPE가 난다
 * (내부 메인스레드 콜백이라 호출부 try/catch로 막을 수 없음).
 * addOverlay를 no-op으로 막아 우회한다. (ThumbnailGenerator와 동일한 방식)
 */
private class NoOverlayMapSnapshotter(
    context: Context,
    options: MapSnapshotter.Options
) : MapSnapshotter(context, options) {
    override fun addOverlay(mapSnapshot: MapSnapshot) = Unit
}

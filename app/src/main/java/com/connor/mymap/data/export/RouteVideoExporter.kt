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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
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
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

        // 2) 모든 포인트의 화면 픽셀 좌표를 미리 투영한 뒤, 영상 렌더링용으로만 단순화한다.
        val originalPx = points.map { snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude)) }
        val exportRoute = simplifyRouteForExport(points, originalPx)
        val exportPoints = exportRoute.points
        val px = exportRoute.pixels

        val t0 = exportPoints.first().timestampMillis
        val totalMs = (exportPoints.last().timestampMillis - t0).coerceAtLeast(1L)

        // 3) 프레임 렌더 + 인코딩
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(dir, "mymap_route_${System.currentTimeMillis()}.mp4")
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val staticFrameBase = buildStaticFrameBase(base, exportPoints, px, title, subtitle, w, h)

        try {
            encodeFrames(
                outFile = outFile,
                frame = frame,
                canvas = canvas,
                staticFrameBase = staticFrameBase,
                points = exportPoints,
                px = px,
                startTimeMillis = t0,
                totalMillis = totalMs,
                onProgress = onProgress
            )
        } finally {
            staticFrameBase.recycle()
            frame.recycle()
        }
        onProgress(1f)
        outFile
    }

    private fun encodeFrames(
        outFile: File,
        frame: Bitmap,
        canvas: Canvas,
        staticFrameBase: Bitmap,
        points: List<TrackingPoint>,
        px: List<PointF>,
        startTimeMillis: Long,
        totalMillis: Long,
        onProgress: (Float) -> Unit
    ) {
        val hardwareEncoder = runCatching {
            HardwareFrameEncoder(outFile, frame.width, frame.height, FPS, VIDEO_BIT_RATE)
        }.getOrElse { error ->
            Logger.w(TAG, "Hardware video encoder unavailable, falling back to JCodec", error)
            null
        }

        if (hardwareEncoder != null) {
            try {
                encodeFramesWith(
                    encoder = hardwareEncoder,
                    frame = frame,
                    canvas = canvas,
                    staticFrameBase = staticFrameBase,
                    points = points,
                    px = px,
                    startTimeMillis = startTimeMillis,
                    totalMillis = totalMillis,
                    onProgress = onProgress
                )
                return
            } catch (error: Exception) {
                Logger.w(TAG, "Hardware video encoder failed, falling back to JCodec", error)
                runCatching { outFile.delete() }
                onProgress(0.10f)
            }
        }

        encodeFramesWith(
            encoder = JCodecFrameEncoder(outFile, FPS),
            frame = frame,
            canvas = canvas,
            staticFrameBase = staticFrameBase,
            points = points,
            px = px,
            startTimeMillis = startTimeMillis,
            totalMillis = totalMillis,
            onProgress = onProgress
        )
    }

    private fun encodeFramesWith(
        encoder: FrameEncoder,
        frame: Bitmap,
        canvas: Canvas,
        staticFrameBase: Bitmap,
        points: List<TrackingPoint>,
        px: List<PointF>,
        startTimeMillis: Long,
        totalMillis: Long,
        onProgress: (Float) -> Unit
    ) {
        val totalFrames = FPS * DURATION_SEC
        var cursor = 0
        try {
            for (i in 0 until totalFrames) {
                val p = if (totalFrames <= 1) 1f else i.toFloat() / (totalFrames - 1)
                val targetMs = (p * totalMillis).toLong()

                // head 위치(시간 기반) 계산
                while (cursor < points.lastIndex &&
                    (points[cursor + 1].timestampMillis - startTimeMillis) <= targetMs
                ) cursor++

                val idx = cursor
                val sameSeg = idx < points.lastIndex &&
                    points[idx].segmentIndex == points[idx + 1].segmentIndex
                val head: PointF = if (sameSeg) {
                    val segStart = points[idx].timestampMillis - startTimeMillis
                    val segEnd = points[idx + 1].timestampMillis - startTimeMillis
                    val f = ((targetMs - segStart).toFloat() / (segEnd - segStart).coerceAtLeast(1L))
                        .coerceIn(0f, 1f)
                    PointF(
                        px[idx].x + (px[idx + 1].x - px[idx].x) * f,
                        px[idx].y + (px[idx + 1].y - px[idx].y) * f
                    )
                } else {
                    px[idx]
                }

                drawFrame(canvas, staticFrameBase, points, px, idx, head)
                encoder.encodeFrame(frame, presentationTimeUs = i * 1_000_000L / FPS)

                if (i % 4 == 0) onProgress(0.10f + 0.88f * p)
            }
        } finally {
            encoder.finish()
        }
    }

    private fun simplifyRouteForExport(
        points: List<TrackingPoint>,
        px: List<PointF>
    ): ExportRoute {
        if (points.size <= MAX_EXPORT_POINTS) return ExportRoute(points, px)

        val keep = BooleanArray(points.size)
        var start = 0
        while (start < points.size) {
            val segment = points[start].segmentIndex
            var end = start
            while (end + 1 < points.size && points[end + 1].segmentIndex == segment) {
                end++
            }
            markSimplifiedSegment(px, start, end, keep)
            start = end + 1
        }

        val simplifiedIndices = keep.indices.filter { keep[it] }
        val cappedIndices = capExportPointCount(simplifiedIndices, MAX_EXPORT_POINTS)
        return ExportRoute(
            points = cappedIndices.map { points[it] },
            pixels = cappedIndices.map { px[it] }
        )
    }

    private fun markSimplifiedSegment(
        px: List<PointF>,
        start: Int,
        end: Int,
        keep: BooleanArray
    ) {
        keep[start] = true
        keep[end] = true
        if (end - start < 2) return

        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.add(start to end)

        while (ranges.isNotEmpty()) {
            val (from, to) = ranges.removeLast()
            var farthestIndex = -1
            var farthestDistance = 0f

            for (i in from + 1 until to) {
                val distance = distanceToLine(px[i], px[from], px[to])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = i
                }
            }

            if (farthestIndex != -1 && farthestDistance > EXPORT_SIMPLIFY_TOLERANCE_PX) {
                keep[farthestIndex] = true
                ranges.add(from to farthestIndex)
                ranges.add(farthestIndex to to)
            }
        }
    }

    private fun capExportPointCount(indices: List<Int>, maxCount: Int): List<Int> {
        if (indices.size <= maxCount) return indices
        if (maxCount <= 2) return listOf(indices.first(), indices.last())

        val capped = ArrayList<Int>(maxCount)
        val lastSourceIndex = indices.lastIndex
        for (i in 0 until maxCount) {
            val sourceIndex = (i.toFloat() * lastSourceIndex / (maxCount - 1)).roundToInt()
            val index = indices[sourceIndex]
            if (capped.lastOrNull() != index) {
                capped.add(index)
            }
        }

        if (capped.first() != indices.first()) capped.add(0, indices.first())
        if (capped.last() != indices.last()) capped[capped.lastIndex] = indices.last()
        return capped
    }

    private fun distanceToLine(point: PointF, start: PointF, end: PointF): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (dx == 0f && dy == 0f) {
            return distance(point, start)
        }

        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy))
            .coerceIn(0f, 1f)
        val projection = PointF(start.x + dx * t, start.y + dy * t)
        return distance(point, projection)
    }

    private fun distance(from: PointF, to: PointF): Float {
        val dx = from.x - to.x
        val dy = from.y - to.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun buildStaticFrameBase(
        base: Bitmap,
        points: List<TrackingPoint>,
        px: List<PointF>,
        title: String,
        subtitle: String,
        w: Int,
        h: Int,
    ): Bitmap {
        val staticFrameBase = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(staticFrameBase)
        canvas.drawBitmap(base, 0f, 0f, null)

        // 변경 이유: 지도 배경, 전체 경로, 시작점, 캡션, 워터마크는 모든 프레임에서 동일하다.
        // 한 번만 그린 비트맵을 캐시하고 매 프레임에는 진행된 경로와 현재 위치만 추가한다.
        drawRoute(canvas, points, px, 0, points.lastIndex, null, faintPaint)

        // 시작 마커
        canvas.drawCircle(px[0].x, px[0].y, 16f, startFill)
        canvas.drawCircle(px[0].x, px[0].y, 16f, markerRing)

        // 캡션(하단)
        val pad = 28f
        val boxH = 132f
        val box = RectF(pad, h - boxH - pad, w - pad, h - pad)
        canvas.drawRoundRect(box, 28f, 28f, captionBg)
        canvas.drawText(title, box.left + 30f, box.top + 54f, titlePaint)
        canvas.drawText(subtitle, box.left + 30f, box.top + 102f, subtitlePaint)
        // 워터마크
        canvas.drawText("MyMap", w - pad - 22f, pad + 44f, watermarkPaint)
        return staticFrameBase
    }

    private fun drawFrame(
        canvas: Canvas,
        staticFrameBase: Bitmap,
        points: List<TrackingPoint>,
        px: List<PointF>,
        headIdx: Int,
        head: PointF,
    ) {
        canvas.drawBitmap(staticFrameBase, 0f, 0f, null)

        // 진행된 경로(진하게) + head까지
        drawRoute(canvas, points, px, 0, headIdx, head, brightPaint)

        // head 마커
        canvas.drawCircle(head.x, head.y, 22f, headHalo)
        canvas.drawCircle(head.x, head.y, 13f, headFill)
        canvas.drawCircle(head.x, head.y, 13f, markerRing)
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

    private interface FrameEncoder {
        fun encodeFrame(frame: Bitmap, presentationTimeUs: Long)
        fun finish()
    }

    private class JCodecFrameEncoder(
        outFile: File,
        fps: Int
    ) : FrameEncoder {
        private val encoder = AndroidSequenceEncoder.createSequenceEncoder(outFile, fps)

        override fun encodeFrame(frame: Bitmap, presentationTimeUs: Long) {
            encoder.encodeImage(frame)
        }

        override fun finish() {
            encoder.finish()
        }
    }

    private class HardwareFrameEncoder(
        private val outFile: File,
        private val width: Int,
        private val height: Int,
        private val fps: Int,
        bitRate: Int
    ) : FrameEncoder {
        private val codecConfig = selectHardwareEncoder()
            ?: error("No byte-buffer AVC encoder")
        private val codec = MediaCodec.createByCodecName(codecConfig.name)
        private val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val bufferInfo = MediaCodec.BufferInfo()
        private val pixels = IntArray(width * height)
        private val yuv = ByteArray(width * height * 3 / 2)

        private var trackIndex = -1
        private var muxerStarted = false
        private var finished = false
        private var lastPresentationTimeUs = 0L

        init {
            val format = MediaFormat.createVideoFormat(MIME_TYPE_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, codecConfig.colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun encodeFrame(frame: Bitmap, presentationTimeUs: Long) {
            drainEncoder(expectEndOfStream = false)
            val inputIndex = dequeueInputBuffer()
            val inputBuffer = codec.getInputBuffer(inputIndex)
                ?: error("Input buffer is unavailable")
            inputBuffer.clear()
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            convertArgbToYuv420(pixels, yuv, width, height, codecConfig.colorFormat)
            inputBuffer.put(yuv)
            codec.queueInputBuffer(
                inputIndex,
                0,
                yuv.size,
                presentationTimeUs,
                0
            )
            lastPresentationTimeUs = presentationTimeUs
            drainEncoder(expectEndOfStream = false)
        }

        override fun finish() {
            if (finished) return
            finished = true
            runCatching {
                val inputIndex = dequeueInputBuffer()
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    lastPresentationTimeUs + 1_000_000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                drainEncoder(expectEndOfStream = true)
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }

        private fun dequeueInputBuffer(): Int {
            while (true) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) return inputIndex
                drainEncoder(expectEndOfStream = false)
            }
        }

        private fun drainEncoder(expectEndOfStream: Boolean) {
            while (true) {
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!expectEndOfStream) return
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Output format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (outputIndex < 0) continue
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                            ?: error("Output buffer is unavailable")

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "Muxer has not started" }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }

                        val endOfStream =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) return
                    }
                }
            }
        }

        private fun convertArgbToYuv420(
            pixels: IntArray,
            out: ByteArray,
            width: Int,
            height: Int,
            colorFormat: Int
        ) {
            val frameSize = width * height
            val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            val uPlaneStart = frameSize
            val vPlaneStart = frameSize + frameSize / 4
            var yIndex = 0
            var uvIndex = frameSize

            for (row in 0 until height) {
                for (col in 0 until width) {
                    val pixel = pixels[row * width + col]
                    val r = pixel shr 16 and 0xff
                    val g = pixel shr 8 and 0xff
                    val b = pixel and 0xff

                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[yIndex++] = y.clampedByte()

                    if (row % 2 == 0 && col % 2 == 0) {
                        if (planar) {
                            val uvOffset = (row / 2) * (width / 2) + (col / 2)
                            out[uPlaneStart + uvOffset] = u.clampedByte()
                            out[vPlaneStart + uvOffset] = v.clampedByte()
                        } else {
                            out[uvIndex++] = u.clampedByte()
                            out[uvIndex++] = v.clampedByte()
                        }
                    }
                }
            }
        }

        private fun Int.clampedByte(): Byte = coerceIn(0, 255).toByte()

        companion object {
            private fun selectHardwareEncoder(): CodecConfig? {
                val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                for (codecInfo in codecInfos) {
                    if (!codecInfo.isEncoder) continue
                    if (codecInfo.supportedTypes.none { it.equals(MIME_TYPE_AVC, ignoreCase = true) }) continue

                    val capabilities = runCatching {
                        codecInfo.getCapabilitiesForType(MIME_TYPE_AVC)
                    }.getOrNull() ?: continue

                    val colorFormat = SUPPORTED_YUV_COLOR_FORMATS
                        .firstOrNull { supported -> capabilities.colorFormats.any { it == supported } }
                        ?: continue

                    return CodecConfig(codecInfo.name, colorFormat)
                }
                return null
            }

            private val SUPPORTED_YUV_COLOR_FORMATS = intArrayOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }
    }

    private data class CodecConfig(
        val name: String,
        val colorFormat: Int
    )

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
        private const val TAG = "RouteVideoExporter"
        private const val MIME_TYPE_AVC = "video/avc"
        private const val VIDEO_BIT_RATE = 1_200_000
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val CODEC_TIMEOUT_US = 10_000L
        // 빠른 내보내기 프리셋.
        // 변경 이유: 기존 720x1280/24fps/15초는 360프레임을 JCodec으로 소프트웨어 인코딩해
        // 공유용 영상 생성 시간이 길었다. 540x960/12fps/10초로 줄여 총 프레임을 120장으로 낮춘다.
        private const val WIDTH = 540
        private const val HEIGHT = 960
        private const val FPS = 12
        private const val DURATION_SEC = 10
        // 변경 이유: 장시간 기록은 수천~수만 포인트가 될 수 있는데, 공유용 10초 영상에서
        // 모든 포인트를 매 프레임 그리면 체감 대기 시간이 길다. 원본은 유지하고 내보내기 복사본만 줄인다.
        private const val MAX_EXPORT_POINTS = 900
        private const val EXPORT_SIMPLIFY_TOLERANCE_PX = 2.5f
    }

    private data class ExportRoute(
        val points: List<TrackingPoint>,
        val pixels: List<PointF>
    )
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

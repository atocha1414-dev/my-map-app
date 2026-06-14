package com.connor.mymap.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import java.io.File

/**
 * 렌더된 mp4를 사진첩(MediaStore)에 기록하는 오케스트레이션.
 * - API29+ & 하드웨어 인코더가 있으면 MediaStore FileDescriptor에 직접 mux(캐시·복사 단계 제거)
 * - 아니면 캐시 파일로 렌더한 뒤 갤러리에 복사
 * (기존 PlaybackViewModel에 있던 로직을 추출해 백그라운드 서비스에서도 재사용)
 */
class GalleryVideoExporter(private val context: Context) {

    suspend fun exportToGallery(
        mapFilePath: String,
        points: List<TrackingPoint>,
        bounds: LatLngBounds,
        title: String,
        subtitle: String,
        dateTakenMillis: Long,
        onProgress: (Float) -> Unit
    ): ExportState.Done {
        // 이전 내보내기 캐시 정리(성공 시 삭제되지만 실패/구버전 잔여물 대비 안전망)
        withContext(Dispatchers.IO) { pruneExportCache() }

        // 영상 길이는 기록 시간에 비례(내보내기 인코더와 동일 계산) → 갤러리 DURATION 메타데이터 일치.
        val recordedMillis = points.last().timestampMillis - points.first().timestampMillis
        val durationMs = RouteVideoExporter.videoDurationSec(recordedMillis) * 1000L

        // ① API 29+ & 하드웨어 인코더가 있으면 MediaStore FileDescriptor에 직접 mux.
        val directUri: Uri? = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            RouteVideoExporter.hasHardwareEncoder()
        ) {
            exportDirectToGallery(
                mapFilePath, points, bounds, title, subtitle, dateTakenMillis, durationMs, onProgress
            )
        } else null

        if (directUri != null) {
            return ExportState.Done(shareUri = directUri, galleryUri = directUri, savedToGallery = true)
        }

        // ② 폴백: 캐시 파일로 렌더한 뒤 갤러리에 복사(구버전/하드웨어 미지원/직접 경로 실패)
        val file = RouteVideoExporter(context).export(mapFilePath, points, bounds, title, subtitle, onProgress)
        val galleryUri = withContext(Dispatchers.IO) {
            runCatching { saveToGallery(file, dateTakenMillis, durationMs) }.getOrNull()
        }
        val shareUri = if (galleryUri != null) {
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            galleryUri
        } else {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        return ExportState.Done(shareUri, galleryUri, savedToGallery = galleryUri != null)
    }

    /**
     * ① API 29+: MediaStore pending 항목을 만들고 그 FileDescriptor에 영상을 직접 mux.
     * 캐시 파일·복사 단계 없이 갤러리에 바로 기록한다. 실패하면 항목을 지우고 null을 반환해
     * 호출부가 파일 렌더 + 복사로 폴백하게 한다.
     */
    private suspend fun exportDirectToGallery(
        mapFilePath: String,
        points: List<TrackingPoint>,
        bounds: LatLngBounds,
        title: String,
        subtitle: String,
        dateTakenMillis: Long,
        durationMillis: Long,
        onProgress: (Float) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "mymap_route_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyMap")
            put(MediaStore.Video.Media.DATE_TAKEN, dateTakenMillis)
            put(MediaStore.Video.Media.DURATION, durationMillis)
            put(MediaStore.Video.Media.WIDTH, RouteVideoExporter.WIDTH)
            put(MediaStore.Video.Media.HEIGHT, RouteVideoExporter.HEIGHT)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = withContext(Dispatchers.IO) {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } ?: return null
        return try {
            withContext(Dispatchers.IO) {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    RouteVideoExporter(context).exportToFileDescriptor(
                        pfd.fileDescriptor, mapFilePath, points, bounds, title, subtitle, onProgress
                    )
                } ?: error("MediaStore FileDescriptor unavailable")
            }
            withContext(Dispatchers.IO) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: CancellationException) {
            // 취소: 만들던 pending 항목을 정리하고 취소를 전파한다(폴백 렌더로 넘어가지 않도록).
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Direct-to-gallery export failed; falling back to file copy")
            withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
            null
        }
    }

    private fun saveToGallery(
        file: File,
        dateTakenMillis: Long,
        durationMillis: Long
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyMap")
            // 갤러리 타임라인 정렬·미리보기를 위한 메타데이터
            put(MediaStore.Video.Media.DATE_TAKEN, dateTakenMillis)
            put(MediaStore.Video.Media.DURATION, durationMillis)
            put(MediaStore.Video.Media.WIDTH, RouteVideoExporter.WIDTH)
            put(MediaStore.Video.Media.HEIGHT, RouteVideoExporter.HEIGHT)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, bufferSize = 1 shl 20) } // 1MB 버퍼
            } ?: error("Gallery output stream is unavailable")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 내보내기 캐시(cacheDir/exports)에 남아 있는 이전 mp4들을 정리한다. */
    private fun pruneExportCache() {
        runCatching {
            val dir = File(context.cacheDir, "exports")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".mp4")) f.delete()
            }
        }.onFailure { Logger.w(TAG, "Failed to prune export cache") }
    }

    companion object {
        private const val TAG = "GalleryVideoExporter"
    }
}

/**
 * 경로 전체를 담는 카메라 bounds(여백 20%).
 * 재생 화면 카메라와 내보내기 프레이밍을 동일하게 맞추기 위한 공용 계산.
 */
fun routeBoundsOf(points: List<TrackingPoint>): LatLngBounds {
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val latPad = (maxLat - minLat).coerceAtLeast(0.001) * 0.2
    val lonPad = (maxLon - minLon).coerceAtLeast(0.001) * 0.2
    return LatLngBounds.from(
        maxLat + latPad,
        maxLon + lonPad,
        minLat - latPad,
        minLon - lonPad
    )
}

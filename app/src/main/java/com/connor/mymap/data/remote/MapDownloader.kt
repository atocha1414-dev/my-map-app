package com.connor.mymap.data.remote


import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 원격 서버(R2)에서 지도 파일을 다운로드
 */
class MapDownloader(
    private val fileStorage: MapFileStorage
) {
    companion object {
        private const val TAG = "MapDownloader"
        private const val PROGRESS_INTERVAL_MILLIS = 250L
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
        private const val MAX_ATTEMPTS = 5
        private const val MAX_BACKOFF_MILLIS = 8_000L
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Constants.Network.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.Network.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 다운로드 진행 정보
     */
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    /**
     * 지도 파일 다운로드
     *
     * @param url 다운로드 URL
     * @param filename 저장할 파일명
     * @param onProgress 진행률 콜백
     * @return 성공 시 파일 경로, 실패 시 예외
     */
    suspend fun download(
        url: String = Constants.Map.DEFAULT_MAP_URL,
        filename: String = Constants.Map.DEFAULT_MAP_FILENAME,
        onProgress: (Progress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempFile = fileStorage.getTempFile(filename)
        var etag: String? = null

        // 끊김 시 이미 받은 부분(임시파일)부터 Range로 이어받기 + 지수 백오프 재시도.
        for (attempt in 1..MAX_ATTEMPTS) {
            val existing = if (tempFile.exists()) tempFile.length() else 0L
            try {
                Logger.i(TAG, "Download attempt $attempt (resume from $existing bytes): $url")

                val builder = Request.Builder().url(url)
                if (existing > 0L) {
                    builder.header("Range", "bytes=$existing-")
                    // 같은 파일임을 보장(서버 파일이 바뀌었으면 200 전체로 응답 → 새로 받음)
                    etag?.let { builder.header("If-Range", it) }
                }

                httpClient.newCall(builder.build()).execute().use { response ->
                    // 416(요청 범위 불가): 이미 전부 받았을 수 있으니 마무리 시도
                    if (response.code == 416 && existing > 0L) {
                        if (fileStorage.finalizeTempFile(filename)) {
                            return@withContext Result.success(fileStorage.getMapFile(filename).absolutePath)
                        }
                    }
                    if (!response.isSuccessful) {
                        val error = "HTTP ${response.code}: ${response.message}"
                        Logger.e(TAG, "Download failed: $error")
                        fileStorage.deleteTempFile(filename)
                        return@withContext Result.failure(Exception(error))
                    }

                    etag = response.header("ETag") ?: etag
                    val body = response.body
                        ?: return@withContext Result.failure(Exception("응답 본문이 비어있습니다"))

                    // 206이면서 이어받기 요청이었을 때만 append. 그 외(200)는 처음부터.
                    val append = response.code == 206 && existing > 0L
                    val startOffset = if (append) existing else 0L
                    val totalBytes = parseTotalBytes(response, startOffset, body.contentLength())

                    var downloadedBytes = startOffset
                    var lastProgressBytes = startOffset
                    var lastProgressAtMillis = 0L

                    FileOutputStream(tempFile, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(Constants.Network.BUFFER_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                val enoughBytes = downloadedBytes - lastProgressBytes >= PROGRESS_STEP_BYTES
                                val enoughTime = now - lastProgressAtMillis >= PROGRESS_INTERVAL_MILLIS
                                val completed = totalBytes > 0L && downloadedBytes >= totalBytes
                                if (enoughBytes || enoughTime || completed) {
                                    lastProgressBytes = downloadedBytes
                                    lastProgressAtMillis = now
                                    onProgress(Progress(downloadedBytes, totalBytes))
                                }
                            }
                        }
                    }

                    val finalized = fileStorage.finalizeTempFile(filename)
                    if (!finalized) {
                        return@withContext Result.failure(Exception("파일 저장에 실패했습니다"))
                    }
                    Logger.i(TAG, "Download completed: $downloadedBytes bytes")
                    return@withContext Result.success(fileStorage.getMapFile(filename).absolutePath)
                }
            } catch (e: IOException) {
                // 네트워크 끊김 → 임시파일은 남겨두고(이어받기) 백오프 후 재시도
                Logger.w(TAG, "Download interrupted (attempt $attempt/$MAX_ATTEMPTS): ${e.message}")
                if (attempt < MAX_ATTEMPTS) {
                    delay(backoffMillis(attempt))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Download error", e)
                fileStorage.deleteTempFile(filename)
                return@withContext Result.failure(e)
            }
        }

        // 모든 재시도 소진
        fileStorage.deleteTempFile(filename)
        Result.failure(IOException("다운로드 재시도 횟수($MAX_ATTEMPTS)를 초과했습니다"))
    }

    /** 206 응답의 Content-Range(bytes X-Y/Z)에서 전체 크기 Z를 얻고, 없으면 offset+contentLength. */
    private fun parseTotalBytes(response: Response, startOffset: Long, contentLength: Long): Long {
        response.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }
        return if (contentLength >= 0L) startOffset + contentLength else -1L
    }

    /** 1s, 2s, 4s, 8s … (상한 8s) */
    private fun backoffMillis(attempt: Int): Long =
        (1_000L shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MILLIS)
}

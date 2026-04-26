package com.connor.mymap.data.remote


import com.connor.mymap.data.local.MapFileStorage
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 원격 서버(R2)에서 지도 파일을 다운로드
 */
class MapDownloader(
    private val fileStorage: MapFileStorage
) {
    companion object {
        private const val TAG = "MapDownloader"
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
        try {
            Logger.i(TAG, "Starting download: $url")

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val error = "HTTP ${response.code}: ${response.message}"
                Logger.e(TAG, "Download failed: $error")
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("응답 본문이 비어있습니다"))

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val tempFile = fileStorage.getTempFile(filename)

            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(Constants.Network.BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        onProgress(Progress(downloadedBytes, totalBytes))
                    }
                }
            }

            // 완료되면 최종 이름으로 변경
            val finalized = fileStorage.finalizeTempFile(filename)
            if (!finalized) {
                return@withContext Result.failure(Exception("파일 저장에 실패했습니다"))
            }

            val finalPath = fileStorage.getMapFile(filename).absolutePath
            Logger.i(TAG, "Download completed: $finalPath ($downloadedBytes bytes)")
            Result.success(finalPath)

        } catch (e: Exception) {
            Logger.e(TAG, "Download error", e)
            // 실패 시 임시 파일 정리
            fileStorage.deleteTempFile(filename)
            Result.failure(e)
        }
    }
}
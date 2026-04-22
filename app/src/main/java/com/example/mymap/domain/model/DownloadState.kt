package com.example.mymap.domain.model


/**
 * 지도 다운로드 상태
 */
sealed class DownloadState {

    /** 초기 상태 / 파일 존재 여부 확인 중 */
    data object Checking : DownloadState()

    /** 다운로드가 필요한 상태 (파일 없음) */
    data object NeedsDownload : DownloadState()

    /** 다운로드 진행 중 */
    data class InProgress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState() {

        val percentage: Float
            get() = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f

        val downloadedMb: Float
            get() = downloadedBytes / (1024f * 1024f)

        val totalMb: Float
            get() = totalBytes / (1024f * 1024f)

        val percentageInt: Int
            get() = (percentage * 100).toInt()
    }

    /** 다운로드 완료, 지도 사용 가능 */
    data object Ready : DownloadState()

    /** 에러 발생 */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : DownloadState()
}
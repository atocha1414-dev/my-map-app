package com.connor.mymap.data.export

import android.net.Uri

/**
 * 경로 영상 내보내기 상태. 화면(PlaybackViewModel)·포그라운드 서비스(RouteExportService)가 공유한다.
 * 이전에는 PlaybackViewModel 내부 타입이었으나, 백그라운드 서비스와 공유하기 위해 최상위로 분리.
 */
sealed interface ExportState {
    data object Idle : ExportState
    data class Rendering(val progress: Float) : ExportState
    data class Done(
        val shareUri: Uri,
        val galleryUri: Uri?,
        val savedToGallery: Boolean
    ) : ExportState
    data class Error(val message: String) : ExportState
}

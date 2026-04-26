package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.domain.model.TrackingPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 이동 경로를 기기 내부 저장소에만 저장한다.
 * 변경 이유: 백그라운드 위치 추적은 민감도가 높으므로 서버 전송 없이
 * 사용자의 기기 안에 경로를 남기는 구조로 시작한다.
 *
 * 동시성 정책: append/clear/read/세션 메타 모두 단일 스레드 디스패처에서 실행한다.
 * Service의 위치 콜백과 ViewModel의 초기 로딩, 사용자 삭제가 같은 파일에 동시 접근하기 때문이다.
 */
class TrackingStorage(context: Context) {

    private val trackFile = File(context.applicationContext.filesDir, TRACK_FILE_NAME)
    private val sessionFile = File(context.applicationContext.filesDir, SESSION_FILE_NAME)

    suspend fun appendPoint(point: TrackingPoint) = withContext(ioDispatcher) {
        trackFile.appendText(point.toCsvLine())
    }

    suspend fun readPoints(): List<TrackingPoint> = withContext(ioDispatcher) {
        if (!trackFile.exists()) return@withContext emptyList()

        trackFile
            .readLines()
            .mapNotNull { line -> line.toTrackingPointOrNull() }
    }

    suspend fun clearPoints() = withContext(ioDispatcher) {
        if (trackFile.exists()) {
            trackFile.delete()
        }
    }

    suspend fun saveSessionStart(startedAtMillis: Long) = withContext(ioDispatcher) {
        sessionFile.writeText(startedAtMillis.toString())
    }

    suspend fun readSessionStart(): Long? = withContext(ioDispatcher) {
        if (!sessionFile.exists()) return@withContext null
        sessionFile.readText().trim().toLongOrNull()
    }

    suspend fun clearSession() = withContext(ioDispatcher) {
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    private fun TrackingPoint.toCsvLine(): String {
        return "$timestampMillis,$latitude,$longitude,$accuracy\n"
    }

    private fun String.toTrackingPointOrNull(): TrackingPoint? {
        val parts = split(",")
        if (parts.size != 4) return null

        return TrackingPoint(
            timestampMillis = parts[0].toLongOrNull() ?: return null,
            latitude = parts[1].toDoubleOrNull() ?: return null,
            longitude = parts[2].toDoubleOrNull() ?: return null,
            accuracy = parts[3].toFloatOrNull() ?: return null
        )
    }

    companion object {
        private const val TRACK_FILE_NAME = "current_track.csv"
        private const val SESSION_FILE_NAME = "current_session.txt"

        // 변경 이유: 위치 콜백, ViewModel 초기 로딩, 사용자 삭제가 모두 같은 파일에 접근한다.
        // 단일 스레드 디스패처를 공유해 쓰기 순서를 보장하고 race를 제거한다.
        private val ioDispatcher: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}

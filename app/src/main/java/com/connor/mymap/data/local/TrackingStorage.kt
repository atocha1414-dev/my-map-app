package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.Logger
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
        runCatching {
            trackFile.appendText(point.toCsvLine())
        }.onFailure { e ->
            Logger.e(TAG, "Failed to append tracking point", e)
        }
    }

    suspend fun readPoints(): List<TrackingPoint> = withContext(ioDispatcher) {
        runCatching {
            if (!trackFile.exists()) return@runCatching emptyList()
            // readLines()는 파일 전체를 List<String>으로 메모리에 올림 → 대용량 파일에서 피크 메모리 2배.
            // useLines는 한 줄씩 스트리밍 처리해 피크 메모리를 최소화한다.
            trackFile.bufferedReader().useLines { lines ->
                lines.mapNotNull { it.toTrackingPointOrNull() }.toList()
            }
        }.getOrElse { e ->
            Logger.e(TAG, "Failed to read tracking points", e)
            emptyList()
        }
    }

    suspend fun readLastPoint(): TrackingPoint? = withContext(ioDispatcher) {
        runCatching {
            if (!trackFile.exists()) return@runCatching null

            var lastPoint: TrackingPoint? = null
            trackFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    line.toTrackingPointOrNull()?.let { lastPoint = it }
                }
            }
            lastPoint
        }.getOrElse { e ->
            Logger.e(TAG, "Failed to read last tracking point", e)
            null
        }
    }

    suspend fun clearPoints() = withContext(ioDispatcher) {
        runCatching {
            if (trackFile.exists()) {
                trackFile.delete()
            }
        }.onFailure { e ->
            Logger.e(TAG, "Failed to clear tracking points", e)
        }
    }

    suspend fun saveSessionStart(startedAtMillis: Long) = withContext(ioDispatcher) {
        runCatching {
            sessionFile.writeText(startedAtMillis.toString())
        }.onFailure { e ->
            Logger.e(TAG, "Failed to save tracking session marker", e)
        }
    }

    suspend fun readSessionStart(): Long? = withContext(ioDispatcher) {
        runCatching {
            if (!sessionFile.exists()) return@runCatching null
            sessionFile.readText().trim().toLongOrNull()
        }.getOrElse { e ->
            Logger.e(TAG, "Failed to read tracking session marker", e)
            null
        }
    }

    suspend fun clearSession() = withContext(ioDispatcher) {
        runCatching {
            if (sessionFile.exists()) {
                sessionFile.delete()
            }
        }.onFailure { e ->
            Logger.e(TAG, "Failed to clear tracking session marker", e)
        }
    }

    private fun TrackingPoint.toCsvLine(): String {
        return "$timestampMillis,$latitude,$longitude,$accuracy,$segmentIndex\n"
    }

    private fun String.toTrackingPointOrNull(): TrackingPoint? {
        val parts = split(",")
        if (parts.size !in 4..5) return null

        return TrackingPoint(
            timestampMillis = parts[0].toLongOrNull() ?: return null,
            latitude = parts[1].toDoubleOrNull() ?: return null,
            longitude = parts[2].toDoubleOrNull() ?: return null,
            accuracy = parts[3].toFloatOrNull() ?: return null,
            segmentIndex = parts.getOrNull(4)?.toIntOrNull() ?: 0
        )
    }

    companion object {
        private const val TAG = "TrackingStorage"
        private const val TRACK_FILE_NAME = "current_track.csv"
        private const val SESSION_FILE_NAME = "current_session.txt"

        // 변경 이유: 위치 콜백, ViewModel 초기 로딩, 사용자 삭제가 모두 같은 파일에 접근한다.
        // 단일 스레드 디스패처를 공유해 쓰기 순서를 보장하고 race를 제거한다.
        private val ioDispatcher: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}

package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.TrackingSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 완료된 이동 세션을 기기 내부 저장소에 보관한다.
 * 한 세션 = 한 텍스트 파일.
 *   첫 줄: META|started|ended|distanceM|durationMs|pointCount
 *   그 뒤: POINT|timestamp|lat|lon|accuracy
 *
 * 목록 조회는 첫 줄(META)만 읽어 빠르게 처리한다.
 */
class TrackingHistoryStorage(context: Context) {

    private val historyDir = File(context.applicationContext.filesDir, HISTORY_DIR_NAME)

    suspend fun save(
        startedAtMillis: Long,
        endedAtMillis: Long,
        durationMillis: Long,
        distanceMeters: Float,
        points: List<TrackingPoint>
    ): TrackingSession = withContext(ioDispatcher) {
        if (!historyDir.exists()) historyDir.mkdirs()

        val id = endedAtMillis.toString()
        val file = File(historyDir, "$id$FILE_EXTENSION")

        val meta = "META|$startedAtMillis|$endedAtMillis|$distanceMeters|$durationMillis|${points.size}\n"
        val pointLines = points.joinToString(separator = "") { p ->
            "POINT|${p.timestampMillis}|${p.latitude}|${p.longitude}|${p.accuracy}\n"
        }

        file.writeText(meta + pointLines)

        TrackingSession(
            id = id,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            pointCount = points.size
        )
    }

    suspend fun list(): List<TrackingSession> = withContext(ioDispatcher) {
        if (!historyDir.exists()) return@withContext emptyList()

        historyDir.listFiles { _, name -> name.endsWith(FILE_EXTENSION) }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.endedAtMillis }
            ?: emptyList()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        File(historyDir, "$id$FILE_EXTENSION").takeIf { it.exists() }?.delete()
    }

    private fun readMeta(file: File): TrackingSession? {
        return try {
            val firstLine = file.bufferedReader().use { it.readLine() } ?: return null
            val parts = firstLine.split("|")
            if (parts.size < 6 || parts[0] != "META") return null

            TrackingSession(
                id = file.nameWithoutExtension,
                startedAtMillis = parts[1].toLongOrNull() ?: return null,
                endedAtMillis = parts[2].toLongOrNull() ?: return null,
                distanceMeters = parts[3].toFloatOrNull() ?: return null,
                durationMillis = parts[4].toLongOrNull() ?: return null,
                pointCount = parts[5].toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val HISTORY_DIR_NAME = "history"
        private const val FILE_EXTENSION = ".txt"

        private val ioDispatcher: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}

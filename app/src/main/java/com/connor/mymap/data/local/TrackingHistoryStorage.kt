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

    data class HistoryFileInfo(
        val id: String,
        val endedAtMillis: Long,
        val sizeBytes: Long
    )

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

        // 변경 이유: 장시간 트래킹(수천 포인트)이면 joinToString으로 1MB+ 문자열을 만들어
        // 메모리에 올리는 게 부담된다. BufferedWriter로 한 줄씩 흘려보낸다.
        file.bufferedWriter().use { writer ->
            writer.write("META|$startedAtMillis|$endedAtMillis|$distanceMeters|$durationMillis|${points.size}\n")
            points.forEach { p ->
                writer.write("POINT|${p.timestampMillis}|${p.latitude}|${p.longitude}|${p.accuracy}|${p.segmentIndex}\n")
            }
        }

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

    /**
     * 첫 화면 체감 속도를 위해 최근 N개 세션만 우선 조회한다.
     * 파일명을 endedAtMillis로 저장하고 있으므로, 파일명 기준 정렬 후 상위 N개만 META를 읽는다.
     */
    suspend fun listRecent(limit: Int): List<TrackingSession> = withContext(ioDispatcher) {
        if (!historyDir.exists() || limit <= 0) return@withContext emptyList()

        historyDir.listFiles { _, name -> name.endsWith(FILE_EXTENSION) }
            ?.asSequence()
            ?.sortedWith(
                compareByDescending<File> { it.nameWithoutExtension.toLongOrNull() ?: Long.MIN_VALUE }
                    .thenByDescending { it.lastModified() }
            )
            ?.take(limit)
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.endedAtMillis }
            ?.toList()
            ?: emptyList()
    }

    suspend fun loadPoints(id: String, maxPoints: Int = MAX_RENDER_POINTS): List<TrackingPoint> =
        withContext(ioDispatcher) {
            val file = File(historyDir, "$id$FILE_EXTENSION")
            if (!file.exists()) return@withContext emptyList()

            val all = mutableListOf<TrackingPoint>()
            file.bufferedReader().use { reader ->
                reader.readLine() // META 줄 건너뜀
                var line = reader.readLine()
                while (line != null) {
                    val p = line.split("|")
                    if (p.size >= 5 && p[0] == "POINT") {
                        val ts = p[1].toLongOrNull()
                        val lat = p[2].toDoubleOrNull()
                        val lon = p[3].toDoubleOrNull()
                        val acc = p[4].toFloatOrNull()
                        val segmentIndex = p.getOrNull(5)?.toIntOrNull() ?: 0
                        if (ts != null && lat != null && lon != null && acc != null) {
                            all += TrackingPoint(lat, lon, acc, ts, segmentIndex)
                        }
                    }
                    line = reader.readLine()
                }
            }

            if (maxPoints <= 0 || all.size <= maxPoints) all
            else {
                val step = all.size / maxPoints
                all.filterIndexed { i, _ -> i % step == 0 }
            }
        }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        File(historyDir, "$id$FILE_EXTENSION").takeIf { it.exists() }?.delete()
    }

    /**
     * 보존 정책 계산용 파일 메타 목록.
     * 파일명(id)이 endedAtMillis 규칙을 따르므로 숫자 파싱을 우선 사용한다.
     */
    suspend fun listFileInfos(): List<HistoryFileInfo> = withContext(ioDispatcher) {
        if (!historyDir.exists()) return@withContext emptyList()

        historyDir.listFiles { _, name -> name.endsWith(FILE_EXTENSION) }
            ?.mapNotNull { file ->
                val id = file.nameWithoutExtension
                val endedAtMillis = id.toLongOrNull() ?: readMeta(file)?.endedAtMillis ?: return@mapNotNull null
                HistoryFileInfo(
                    id = id,
                    endedAtMillis = endedAtMillis,
                    sizeBytes = file.length()
                )
            }
            ?.sortedByDescending { it.endedAtMillis }
            ?: emptyList()
    }

    suspend fun deleteMany(ids: Set<String>): Int = withContext(ioDispatcher) {
        ids.count { id ->
            val file = File(historyDir, "$id$FILE_EXTENSION")
            file.exists() && file.delete()
        }
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
        private const val MAX_RENDER_POINTS = 300

        private val ioDispatcher: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}

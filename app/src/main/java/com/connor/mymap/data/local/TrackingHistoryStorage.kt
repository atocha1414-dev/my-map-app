package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.domain.model.TrackingSession
import com.connor.mymap.util.Logger
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

        // 변경 이유: 캐시된 GPS 픽스나 시계 이상으로 timestamp가 세션 윈도우를 크게 벗어난 포인트가 섞이면
        // 재생 화면 totalMs(포인트 ts 기반)가 META durationMillis와 어긋나 사용자가 혼란을 겪는다.
        // 저장 시점에 윈도우 밖 포인트는 제외해 데이터 일관성을 보장한다.
        val cleanPoints = points.filterInWindow(startedAtMillis, endedAtMillis)
        val droppedCount = points.size - cleanPoints.size
        if (droppedCount > 0) {
            Logger.w(TAG, "Dropped $droppedCount points with out-of-window timestamps on save")
        }

        val id = endedAtMillis.toString()
        val file = File(historyDir, "$id$FILE_EXTENSION")

        // 변경 이유: 장시간 트래킹(수천 포인트)이면 joinToString으로 1MB+ 문자열을 만들어
        // 메모리에 올리는 게 부담된다. BufferedWriter로 한 줄씩 흘려보낸다.
        file.bufferedWriter().use { writer ->
            writer.write("META|$startedAtMillis|$endedAtMillis|$distanceMeters|$durationMillis|${cleanPoints.size}\n")
            cleanPoints.forEach { p ->
                writer.write("POINT|${p.timestampMillis}|${p.latitude}|${p.longitude}|${p.accuracy}|${p.segmentIndex}\n")
            }
        }

        TrackingSession(
            id = id,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            pointCount = cleanPoints.size
        )
    }

    private fun List<TrackingPoint>.filterInWindow(
        startedAtMillis: Long,
        endedAtMillis: Long
    ): List<TrackingPoint> {
        val low = startedAtMillis - WINDOW_GRACE_MILLIS
        val high = endedAtMillis + WINDOW_GRACE_MILLIS
        return filter { it.timestampMillis in low..high }
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

            // META와 POINT를 단일 패스로 읽는다.
            // 변경 이유: 과거에 저장된 손상 데이터(stale 캐시 픽스 등)에 대비해
            // META의 시간 윈도우 밖 포인트는 로드 시점에도 다시 한 번 걸러낸다 (방어적 정합성 유지).
            var startedAt: Long? = null
            var endedAt: Long? = null
            val rawPoints = mutableListOf<TrackingPoint>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("META|")) {
                        val p = line.split("|")
                        startedAt = p.getOrNull(1)?.toLongOrNull()
                        endedAt = p.getOrNull(2)?.toLongOrNull()
                    } else {
                        line.toTrackingPointOrNull()?.let { rawPoints += it }
                    }
                }
            }

            val s = startedAt
            val e = endedAt
            val all = if (s != null && e != null) {
                val cleaned = rawPoints.filterInWindow(s, e)
                if (cleaned.size != rawPoints.size) {
                    Logger.w(TAG, "Dropped ${rawPoints.size - cleaned.size} stale points on load id=$id")
                }
                cleaned
            } else {
                rawPoints
            }

            if (maxPoints <= 0 || all.size <= maxPoints) all
            else {
                val step = all.size / maxPoints
                all.filterIndexed { i, _ -> i % step == 0 }
            }
        }

    private fun String.toTrackingPointOrNull(): TrackingPoint? {
        val p = split("|")
        if (p.size < 5 || p[0] != "POINT") return null
        val ts = p[1].toLongOrNull() ?: return null
        val lat = p[2].toDoubleOrNull() ?: return null
        val lon = p[3].toDoubleOrNull() ?: return null
        val acc = p[4].toFloatOrNull() ?: return null
        val segmentIndex = p.getOrNull(5)?.toIntOrNull() ?: 0
        return TrackingPoint(lat, lon, acc, ts, segmentIndex)
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
        private const val TAG = "TrackingHistoryStorage"
        private const val HISTORY_DIR_NAME = "history"
        private const val FILE_EXTENSION = ".txt"
        private const val MAX_RENDER_POINTS = 300
        // 변경 이유: 시계 동기화 오차나 첫/마지막 포인트의 측정 지연을 흡수하기 위한 grace.
        // 이 범위를 벗어난 timestamp는 stale 캐시·손상 데이터로 간주한다.
        private const val WINDOW_GRACE_MILLIS = 60_000L

        private val ioDispatcher: CoroutineDispatcher =
            Dispatchers.IO.limitedParallelism(1)
    }
}

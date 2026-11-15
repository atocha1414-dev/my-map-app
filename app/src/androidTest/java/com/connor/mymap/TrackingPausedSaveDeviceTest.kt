package com.connor.mymap

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.connor.mymap.data.local.TrackingHistoryStorage
import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.TrackingCalculator
import com.connor.mymap.util.TrackingSaveWindow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 일시정지가 있는 기록을 저장해도 앞부분 포인트가 보존되는지 확인한다.
 * 합성 포인트만 쓰고, 저장소는 캐시 아래 격리 폴더로 돌려 사용자의 기록 폴더를 건드리지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class TrackingPausedSaveDeviceTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolatedDir = File(appContext.cacheDir, "paused-save-device-test")

    private val isolatedContext = object : ContextWrapper(appContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedDir.also { it.mkdirs() }
    }

    @After fun cleanup() {
        isolatedDir.deleteRecursively()
    }

    @Test fun pausedRecordKeepsEarlyPointsAfterSaveAndLoad() = runBlocking {
        val storage = TrackingHistoryStorage(isolatedContext)
        val endedAt = System.currentTimeMillis()
        // 30분 기록(구간 0) → 60분 일시정지 → 30분 기록(구간 1) → 저장. 활동 시간 60분.
        val firstStart = endedAt - 120 * MIN
        val points = (0..30).map { point(firstStart + it * MIN, segment = 0, step = it) } +
            (0..30).map { point(firstStart + (90 + it) * MIN, segment = 1, step = 31 + it) }
        val activeDuration = 60 * MIN

        val session = storage.save(
            startedAtMillis = TrackingSaveWindow.recordStartMillis(endedAt, activeDuration, points),
            endedAtMillis = endedAt,
            durationMillis = activeDuration,
            distanceMeters = TrackingCalculator.calculateStats(points).distanceMeters,
            points = points
        )

        assertEquals(points.size, session.pointCount)
        assertEquals(firstStart, session.startedAtMillis)
        val loaded = storage.loadPoints(session.id, maxPoints = 0)
        assertEquals(points.size, loaded.size)
        assertEquals(firstStart, loaded.first().timestampMillis)
        assertTrue(isolatedDir.resolve("history").exists())
    }

    @Test fun oldBackCalculationWouldDropEarlyPoints() = runBlocking {
        // 수정 전 계산(종료 - 활동 시간)으로 저장하면 앞 구간이 버려졌음을 같은 경로로 확인한다.
        val storage = TrackingHistoryStorage(isolatedContext)
        val endedAt = System.currentTimeMillis()
        val firstStart = endedAt - 120 * MIN
        val points = (0..30).map { point(firstStart + it * MIN, segment = 0, step = it) } +
            (0..30).map { point(firstStart + (90 + it) * MIN, segment = 1, step = 31 + it) }

        val session = storage.save(
            startedAtMillis = endedAt - 60 * MIN,
            endedAtMillis = endedAt,
            durationMillis = 60 * MIN,
            distanceMeters = 0f,
            points = points
        )

        assertTrue(session.pointCount < points.size)
    }

    private fun point(ts: Long, segment: Int, step: Int) = TrackingPoint(
        latitude = 37.5 + step * 0.0005,
        longitude = 127.0,
        accuracy = 5f,
        timestampMillis = ts,
        segmentIndex = segment
    )

    private companion object {
        const val MIN = 60_000L
    }
}

package com.connor.mymap.util

import com.connor.mymap.domain.model.TrackingPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingSaveWindowTest {

    @Test
    fun pausedRecordStartsAtFirstPointInsteadOfActiveDurationBackCalculation() {
        // 30분 기록 → 60분 일시정지 → 30분 기록 → 저장. 활동 시간은 60분.
        val points = listOf(point(ts = 0L), point(ts = 30 * MIN), point(ts = 90 * MIN), point(ts = 120 * MIN))

        val start = TrackingSaveWindow.recordStartMillis(
            endedAtMillis = 120 * MIN,
            activeDurationMillis = 60 * MIN,
            points = points
        )

        assertEquals(0L, start)
    }

    @Test
    fun recordWithoutPauseKeepsActiveDurationStart() {
        // 첫 포인트는 GPS 대기 때문에 시작보다 늦게 들어온다.
        val start = TrackingSaveWindow.recordStartMillis(
            endedAtMillis = 10 * MIN,
            activeDurationMillis = 10 * MIN,
            points = listOf(point(ts = 20_000L), point(ts = 10 * MIN))
        )

        assertEquals(0L, start)
    }

    @Test
    fun recordWithoutPointsUsesActiveDuration() {
        val start = TrackingSaveWindow.recordStartMillis(
            endedAtMillis = 10 * MIN,
            activeDurationMillis = 4 * MIN,
            points = emptyList()
        )

        assertEquals(6 * MIN, start)
    }

    @Test
    fun futurePointDoesNotMoveStartAfterEnd() {
        val start = TrackingSaveWindow.recordStartMillis(
            endedAtMillis = 10 * MIN,
            activeDurationMillis = 0L,
            points = listOf(point(ts = 99 * MIN))
        )

        assertEquals(10 * MIN, start)
    }

    @Test
    fun mergeAppendsOnlyLivePointsNewerThanDisk() {
        val disk = listOf(point(ts = 1_000L), point(ts = 2_000L))
        val live = listOf(point(ts = 1_000L), point(ts = 2_000L), point(ts = 3_000L))

        val merged = TrackingSaveWindow.mergePendingLivePoints(disk, live)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), merged.map { it.timestampMillis })
    }

    @Test
    fun mergeKeepsDiskWhenLiveListIsCappedOlderWindow() {
        val disk = (1..5).map { point(ts = it * 1_000L) }
        val live = disk.takeLast(2)

        val merged = TrackingSaveWindow.mergePendingLivePoints(disk, live)

        assertEquals(disk, merged)
    }

    @Test
    fun mergeUsesLivePointsWhenDiskIsEmpty() {
        val live = listOf(point(ts = 1_000L))

        assertEquals(live, TrackingSaveWindow.mergePendingLivePoints(emptyList(), live))
    }

    private fun point(ts: Long) = TrackingPoint(
        latitude = 37.0,
        longitude = 127.0,
        accuracy = 5f,
        timestampMillis = ts
    )

    private companion object {
        const val MIN = 60_000L
    }
}

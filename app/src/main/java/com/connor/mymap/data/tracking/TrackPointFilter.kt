package com.connor.mymap.data.tracking

import com.connor.mymap.domain.model.TrackingPoint
import com.connor.mymap.util.TrackingCalculator

/**
 * 앵커 기반 + 이동 확정(state machine) GPS 필터.
 *
 * 문제: 사용자가 멈춰 있어도 GPS 좌표는 정확도 반경 안에서 계속 흔들린다(드리프트).
 * 직전 점과만 비교하면 드리프트가 매번 임계를 넘겨 채택되고, 채택될 때마다 기준점이 이동해
 * 제자리에서 지그재그 선이 그려진다.
 *
 * 해결:
 *  - 기준점(anchor)을 고정한다. 앵커 반경(정확도 비례) 안의 점은 전부 정지 노이즈로 보고 버린다.
 *  - 앵커에서 충분히 멀어진 점이 와도 곧바로 채택하지 않고, 연속으로 멀어짐이 '지속'될 때만
 *    실제 이동으로 확정하고 앵커를 옮긴다(드리프트 스파이크가 되돌아오면 폐기).
 *  - 이동(MOVING) 중에는 곧바로 채택해 경로를 촘촘히 따라가고, 다시 앵커 근처에 머물면
 *    정지(STILL)로 전환한다.
 *
 * 스레드: process()는 위치 콜백(mainLooper), reset()은 시작 코루틴(Default)에서 호출되므로
 * @Synchronized로 가시성과 원자성을 보장한다.
 */
class TrackPointFilter {

    private var anchor: TrackingPoint? = null
    private var moving = false
    private var stillStreak = 0
    private val pending = ArrayList<TrackingPoint>()

    /** 세션/세그먼트 시작 시 호출. resumeAnchor가 있으면 그 점을 앵커로 이어간다. */
    @Synchronized
    fun reset(resumeAnchor: TrackingPoint?) {
        anchor = resumeAnchor
        moving = false
        stillStreak = 0
        pending.clear()
    }

    /**
     * 후보 점을 받아 트랙에 추가할 점을 반환한다(추가하지 않을 경우 null).
     * 정확도·과속 등 1차 sanity는 [TrackingCalculator.shouldAcceptPoint]로 거른다.
     */
    @Synchronized
    fun process(
        candidate: TrackingPoint,
        elapsedSinceStartMillis: Long,
        candidateSpeedMetersPerSecond: Float?
    ): TrackingPoint? {
        val a = anchor
            ?: run {
                // 첫 점: 정확도 sanity만 통과하면 앵커로 삼는다.
                if (!TrackingCalculator.shouldAcceptPoint(
                        previous = null,
                        candidate = candidate,
                        elapsedSinceStartMillis = elapsedSinceStartMillis,
                        candidateSpeedMetersPerSecond = candidateSpeedMetersPerSecond
                    )
                ) return null
                anchor = candidate
                moving = false
                stillStreak = 0
                pending.clear()
                return candidate
            }

        val d = TrackingCalculator.distance(a, candidate)
        val moveThreshold = TrackingCalculator.significantMovementDistance(a, candidate)

        // 앵커 반경 안 = 정지 노이즈
        if (d < moveThreshold) {
            if (moving) {
                stillStreak++
                if (stillStreak >= STILL_CONFIRM_COUNT) {
                    moving = false
                    pending.clear()
                }
            } else {
                pending.clear()
            }
            return null
        }

        // 앵커에서 충분히 멀어진 후보 — 과속(텔레포트) 등 sanity 통과해야 함
        if (!TrackingCalculator.shouldAcceptPoint(
                previous = a,
                candidate = candidate,
                elapsedSinceStartMillis = elapsedSinceStartMillis,
                candidateSpeedMetersPerSecond = candidateSpeedMetersPerSecond
            )
        ) return null

        // 이동 중: 즉시 채택 + 재앵커 (경로를 촘촘히 따라간다)
        if (moving) {
            stillStreak = 0
            anchor = candidate
            return candidate
        }

        // 정지 → 멀어진 후보: 드리프트 스파이크 방지를 위해 '지속 이동'을 확정해야 채택
        pending.add(candidate)
        if (pending.size > PENDING_CAP) pending.removeAt(0)

        val strongMove = d >= moveThreshold * STRONG_MOVE_MULTIPLIER || d >= STRONG_MOVE_ABSOLUTE_METERS
        val progressing = pending.size >= 2 &&
            TrackingCalculator.distance(a, pending.last()) >=
            TrackingCalculator.distance(a, pending.first()) * PROGRESS_RATIO
        val confirmed = strongMove || (pending.size >= CONFIRM_COUNT && progressing)

        if (confirmed) {
            moving = true
            stillStreak = 0
            anchor = candidate
            pending.clear()
            return candidate
        }
        return null
    }

    companion object {
        /** 정지→이동 확정에 필요한 연속 '멀어짐' 점 수. */
        private const val CONFIRM_COUNT = 2
        /** 이동→정지 전환에 필요한 연속 '앵커 근처' 점 수. */
        private const val STILL_CONFIRM_COUNT = 2
        /** 이 배수 이상 멀면 단발이라도 즉시 이동 확정(빠른 이동 지연 방지). */
        private const val STRONG_MOVE_MULTIPLIER = 2.5f
        /** 이 절대 거리(m) 이상이면 즉시 이동 확정. */
        private const val STRONG_MOVE_ABSOLUTE_METERS = 30f
        /** 마지막 대기점이 첫 대기점만큼(×비율) 더 멀어졌는지 — 되돌아오는 드리프트 배제. */
        private const val PROGRESS_RATIO = 0.8f
        private const val PENDING_CAP = 5
    }
}

package com.connor.mymap.data.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 내보내기 상태를 프로세스 전역에서 공유한다.
 * 포그라운드 서비스(RouteExportService)가 갱신하고 화면(PlaybackViewModel)이 관찰한다.
 * 화면을 벗어나(뷰모델이 사라져)도 상태가 유지되므로, 긴 내보내기 중에도 앱을 자유롭게 쓸 수 있다.
 */
object RouteExportManager {

    /** 어떤 세션을 내보내는 중인지 + 그 상태. 세션별로 화면이 자기 것만 표시하도록 sessionId를 함께 보관. */
    data class Snapshot(val sessionId: String?, val state: ExportState)

    private val _snapshot = MutableStateFlow(Snapshot(null, ExportState.Idle))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    val isRunning: Boolean
        get() = _snapshot.value.state is ExportState.Rendering

    /** 진행 중인 내보내기가 없을 때만 시작 표시. 이미 진행 중이면 false(중복 시작 방지). */
    @Synchronized
    fun beginIfIdle(sessionId: String): Boolean {
        if (_snapshot.value.state is ExportState.Rendering) return false
        _snapshot.value = Snapshot(sessionId, ExportState.Rendering(0f))
        return true
    }

    // 진행 콜백은 "그 세션이 아직 Rendering일 때만" 반영한다.
    // 취소(consume→Idle)나 완료 이후 늦게 도착하는 진행 업데이트가 상태를 되돌리는 race를 막는다.
    @Synchronized
    fun updateProgress(sessionId: String, progress: Float) {
        val cur = _snapshot.value
        if (cur.sessionId == sessionId && cur.state is ExportState.Rendering) {
            _snapshot.value = Snapshot(sessionId, ExportState.Rendering(progress))
        }
    }

    /** 완료(Done)/오류(Error)로 마무리. 이미 취소된 세션이면 무시(Rendering일 때만 적용). */
    @Synchronized
    fun finish(sessionId: String, state: ExportState) {
        val cur = _snapshot.value
        if (cur.sessionId == sessionId && cur.state is ExportState.Rendering) {
            _snapshot.value = Snapshot(sessionId, state)
        }
    }

    /** 결과 소비(닫기) 또는 취소 시 Idle로 되돌린다(해당 세션일 때만). */
    @Synchronized
    fun consume(sessionId: String) {
        if (_snapshot.value.sessionId == sessionId) {
            _snapshot.value = Snapshot(null, ExportState.Idle)
        }
    }
}

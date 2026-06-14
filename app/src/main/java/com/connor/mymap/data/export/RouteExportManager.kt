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

    fun updateProgress(sessionId: String, progress: Float) {
        _snapshot.value = Snapshot(sessionId, ExportState.Rendering(progress))
    }

    /** 완료(Done) 또는 오류(Error) 상태로 마무리. */
    fun finish(sessionId: String, state: ExportState) {
        _snapshot.value = Snapshot(sessionId, state)
    }

    /** 화면이 완료/오류 결과를 소비(닫기)하면 Idle로 되돌린다(해당 세션일 때만). */
    fun consume(sessionId: String) {
        if (_snapshot.value.sessionId == sessionId) {
            _snapshot.value = Snapshot(null, ExportState.Idle)
        }
    }
}

package com.connor.mymap.domain.repository


import com.connor.mymap.domain.model.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * 지도 데이터 관리 인터페이스
 * - UI Layer는 이 인터페이스만 알면 됨
 * - 구현 방식이 바뀌어도 UI는 수정 불필요
 */
interface MapRepository {

    /**
     * 현재 지도 파일 상태 확인
     */
    suspend fun checkMapStatus(): DownloadState

    /**
     * 지도 다운로드 (진행 상태를 Flow로 방출)
     */
    fun downloadMap(): Flow<DownloadState>

    /**
     * 지도 파일 삭제 (재다운로드 테스트용)
     */
    suspend fun deleteMap(): Boolean

    /**
     * 로컬 지도 파일 경로 (있으면)
     */
    fun getLocalMapPath(): String?
}
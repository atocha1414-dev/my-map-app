package com.example.mymap.data.local


import android.content.Context
import com.example.mymap.util.Logger
import java.io.File

/**
 * 로컬 파일 시스템에서 지도 파일 관리
 * - 앱 내부 저장소 (filesDir) 사용
 * - 다른 앱이 접근 불가, 앱 삭제 시 함께 삭제됨
 */
class MapFileStorage(private val context: Context) {

    companion object {
        private const val TAG = "MapFileStorage"
    }

    /**
     * 특정 이름의 지도 파일 경로를 반환
     */
    fun getMapFile(filename: String): File {
        return File(context.filesDir, filename)
    }

    /**
     * 다운로드 중인 임시 파일 경로
     */
    fun getTempFile(filename: String): File {
        return File(context.filesDir, "$filename.tmp")
    }

    /**
     * 지도 파일이 유효하게 존재하는지 확인
     */
    fun isMapFileValid(filename: String): Boolean {
        val file = getMapFile(filename)
        val valid = file.exists() && file.length() > 0
        Logger.d(TAG, "Map file '$filename' valid: $valid (size: ${file.length()})")
        return valid
    }

    /**
     * 지도 파일 삭제
     */
    fun deleteMapFile(filename: String): Boolean {
        val file = getMapFile(filename)
        val result = if (file.exists()) file.delete() else true
        Logger.d(TAG, "Deleted map file '$filename': $result")
        return result
    }

    /**
     * 임시 파일 정리 (다운로드 실패 시)
     */
    fun deleteTempFile(filename: String): Boolean {
        val tempFile = getTempFile(filename)
        return if (tempFile.exists()) tempFile.delete() else true
    }

    /**
     * 임시 파일을 최종 파일로 이름 변경 (다운로드 완료 시)
     */
    fun finalizeTempFile(filename: String): Boolean {
        val temp = getTempFile(filename)
        val final = getMapFile(filename)

        if (!temp.exists()) {
            Logger.w(TAG, "Temp file doesn't exist: ${temp.absolutePath}")
            return false
        }

        // 기존 파일이 있으면 삭제
        if (final.exists()) final.delete()

        val success = temp.renameTo(final)
        Logger.d(TAG, "Finalized temp file '$filename': $success")
        return success
    }

    /**
     * 지도 파일의 크기 반환 (바이트, 없으면 0)
     */
    fun getMapFileSize(filename: String): Long {
        return getMapFile(filename).takeIf { it.exists() }?.length() ?: 0
    }
}
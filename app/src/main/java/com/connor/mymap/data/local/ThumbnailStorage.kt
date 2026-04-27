package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.util.Logger
import java.io.File

/**
 * 세션 썸네일 PNG 파일을 관리한다.
 * filesDir/thumbnails/<sessionId>.png 한 세션당 한 파일.
 */
class ThumbnailStorage(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    fun getFile(sessionId: String): File {
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$sessionId$EXTENSION")
    }

    fun exists(sessionId: String): Boolean = getFile(sessionId).exists()

    fun delete(sessionId: String): Boolean {
        return runCatching {
            val f = getFile(sessionId)
            if (f.exists()) f.delete() else true
        }.getOrElse { e ->
            Logger.e(TAG, "Failed to delete thumbnail $sessionId", e)
            false
        }
    }

    companion object {
        private const val TAG = "ThumbnailStorage"
        private const val DIR_NAME = "thumbnails"
        private const val EXTENSION = ".png"
    }
}

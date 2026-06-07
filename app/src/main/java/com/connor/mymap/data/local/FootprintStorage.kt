package com.connor.mymap.data.local

import android.content.Context
import com.connor.mymap.domain.model.Footprint
import com.connor.mymap.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 사용자가 만든 "발자취"를 기기 내부 저장소에 보관한다.
 * 한 발자취 = 한 텍스트 파일.
 *   CREATED|<millis>
 *   NAME|<name>            (첫 '|' 이후 전부가 이름; 줄바꿈은 저장 시 제거)
 *   SESSION|<sessionId>
 *   ...
 */
class FootprintStorage(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    suspend fun save(name: String, sessionIds: List<String>): Footprint = withContext(ioDispatcher) {
        if (!dir.exists()) dir.mkdirs()
        val createdAt = System.currentTimeMillis()
        val id = createdAt.toString()
        val safeName = name.replace("\n", " ").trim().ifEmpty { "발자취" }
        File(dir, "$id$EXT").bufferedWriter().use { w ->
            w.write("CREATED|$createdAt\n")
            w.write("NAME|$safeName\n")
            sessionIds.forEach { w.write("SESSION|$it\n") }
        }
        Footprint(id, safeName, createdAt, sessionIds)
    }

    suspend fun list(): List<Footprint> = withContext(ioDispatcher) {
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { _, n -> n.endsWith(EXT) }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.createdAtMillis }
            ?: emptyList()
    }

    suspend fun get(id: String): Footprint? = withContext(ioDispatcher) {
        read(File(dir, "$id$EXT"))
    }

    suspend fun delete(id: String) {
        withContext(ioDispatcher) {
            File(dir, "$id$EXT").takeIf { it.exists() }?.delete()
        }
    }

    private fun read(file: File): Footprint? {
        if (!file.exists()) return null
        return try {
            var created: Long? = null
            var name = "발자취"
            val ids = mutableListOf<String>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("CREATED|") -> created = line.substringAfter("|").toLongOrNull()
                        line.startsWith("NAME|") -> name = line.substringAfter("|")
                        line.startsWith("SESSION|") ->
                            line.substringAfter("|").takeIf { it.isNotBlank() }?.let { ids += it }
                    }
                }
            }
            val c = created ?: file.nameWithoutExtension.toLongOrNull() ?: return null
            Footprint(file.nameWithoutExtension, name, c, ids)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read footprint ${file.name}", e)
            null
        }
    }

    companion object {
        private const val TAG = "FootprintStorage"
        private const val DIR_NAME = "footprints"
        private const val EXT = ".txt"
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}

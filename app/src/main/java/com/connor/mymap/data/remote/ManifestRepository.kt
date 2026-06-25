package com.connor.mymap.data.remote

import com.connor.mymap.domain.model.MapCatalog
import com.connor.mymap.domain.model.MapCountry
import com.connor.mymap.domain.model.MapRegion
import com.connor.mymap.util.Constants
import com.connor.mymap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 지도 카탈로그(manifest.json)를 원격에서 가져와 파싱한다.
 * kotlinx.serialization 의존성 없이 내장 org.json으로 파싱(가벼움).
 */
class ManifestRepository(
    private val manifestUrl: String = Constants.Map.MANIFEST_URL
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 성공 시 카탈로그, 실패 시 예외(네트워크/파싱). 호출부에서 수동 선택 폴백 가능. */
    suspend fun fetch(): Result<MapCatalog> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("빈 응답")
                parse(JSONObject(body))
            }
        }.onFailure { Logger.e(TAG, "manifest fetch 실패: $manifestUrl", it) }
    }

    private fun parse(json: JSONObject): MapCatalog {
        val countriesArr = json.getJSONArray("countries")
        val countries = (0 until countriesArr.length()).map { i ->
            val c = countriesArr.getJSONObject(i)
            val regionsArr = c.getJSONArray("regions")
            val regions = (0 until regionsArr.length()).map { j ->
                val r = regionsArr.getJSONObject(j)
                MapRegion(
                    id = r.getString("id"),
                    name = r.getString("name"),
                    url = r.getString("url"),
                    sizeMB = r.optInt("sizeMB", 0)
                )
            }
            MapCountry(id = c.getString("id"), name = c.getString("name"), regions = regions)
        }
        return MapCatalog(schema = json.optInt("schema", 1), countries = countries)
    }

    companion object {
        private const val TAG = "ManifestRepository"
    }
}

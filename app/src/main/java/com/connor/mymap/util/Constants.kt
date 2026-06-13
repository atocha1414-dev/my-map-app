package com.connor.mymap.util

import com.connor.mymap.BuildConfig

object Constants {

    // ═══ 지도 파일 서버 ═══
    object Map {
        val DOWNLOAD_BASE_URL: String
            get() = BuildConfig.MAP_DOWNLOAD_BASE_URL

        const val DEFAULT_MAP_FILENAME = "korea.mbtiles"

        // 전체 다운로드 URL
        val DEFAULT_MAP_URL: String
            get() = "$DOWNLOAD_BASE_URL/$DEFAULT_MAP_FILENAME"
    }

    // ═══ 네트워크 설정 ═══
    object Network {
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 300L // 대용량 파일용
        const val BUFFER_SIZE = 65536 // 64KB: 대용량 다운로드 시 syscall 오버헤드 감소
    }

    // ═══ 지도 초기 위치 (서울 시청) ═══
    object MapDefaults {
        const val INITIAL_LATITUDE = 37.5665
        const val INITIAL_LONGITUDE = 126.9780
        const val INITIAL_ZOOM = 12.0
        const val MY_LOCATION_ZOOM = 16.0
    }

    // ═══ 저장소 키 ═══
    object PrefKeys {
        const val MAP_VERSION = "map_version"
        const val LAST_DOWNLOAD_DATE = "last_download_date"
        const val FIRST_LAUNCH = "first_launch"
    }
}

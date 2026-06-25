package com.connor.mymap.domain.model

/**
 * 원격(R2 등)에 호스팅된 지도 카탈로그(manifest.json) 모델.
 * 구조: 국가 → (미국이면 주 단위) 지역. 앱은 이 카탈로그로 "받을 지역"을 정한다.
 */
data class MapCatalog(
    val schema: Int,
    val countries: List<MapCountry>
)

data class MapCountry(
    val id: String,            // ISO2 소문자: "kr", "us", "jp"
    val name: String,          // 표시명: "한국", "미국"
    val regions: List<MapRegion>
)

data class MapRegion(
    val id: String,            // "kr", "us-california"
    val name: String,          // "대한민국", "California"
    val url: String,           // 다운로드 URL(절대경로)
    val sizeMB: Int
)

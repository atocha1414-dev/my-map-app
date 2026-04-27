package com.connor.mymap.util

/**
 * 오프라인 MBTiles를 사용하는 MapLibre 스타일 JSON.
 * 변경 이유: MapLibreView(메인 지도)와 ThumbnailGenerator(세션 썸네일)가
 * 동일한 시각적 스타일을 공유해야 한다. 한 곳에서 관리한다.
 */
fun buildOfflineMapStyle(mapFilePath: String): String {
    return """
    {
      "version": 8,
      "name": "MyMap",
      "sources": {
        "openmaptiles": {
          "type": "vector",
          "url": "mbtiles:///$mapFilePath"
        }
      },
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": { "background-color": "#f8f4f0" }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "water",
          "paint": { "fill-color": "#a0c8f0" }
        },
        {
          "id": "landuse",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "landuse",
          "paint": { "fill-color": "#e8e0d0", "fill-opacity": 0.5 }
        },
        {
          "id": "park",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "park",
          "paint": { "fill-color": "#c8e6c0" }
        },
        {
          "id": "buildings",
          "type": "fill",
          "source": "openmaptiles",
          "source-layer": "building",
          "minzoom": 13,
          "paint": { "fill-color": "#d8d0c0", "fill-outline-color": "#b8b0a0" }
        },
        {
          "id": "roads-minor",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "minor", "service", "track"],
          "minzoom": 13,
          "paint": { "line-color": "#ffffff", "line-width": 1 }
        },
        {
          "id": "roads-major",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "primary", "secondary", "tertiary"],
          "paint": {
            "line-color": "#ffffff",
            "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 16, 4]
          }
        },
        {
          "id": "highways",
          "type": "line",
          "source": "openmaptiles",
          "source-layer": "transportation",
          "filter": ["in", "class", "motorway", "trunk"],
          "paint": {
            "line-color": "#fcd16e",
            "line-width": ["interpolate", ["linear"], ["zoom"], 5, 1, 16, 6]
          }
        }
      ]
    }
    """.trimIndent()
}

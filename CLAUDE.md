# MyMap (내경로)

오프라인 지도 기반 GPS 이동 경로 기록 Android 앱. Kotlin + Jetpack Compose + MapLibre.
지도 타일(mbtiles)을 최초 실행 시 1회 내려받아 **네트워크 없이** 동작한다.

## 빌드 · 실행

Gradle은 **JDK 21**로 실행해야 한다 (프로젝트 컴파일 타깃은 Java 11).

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:installDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest
```

`adb`는 PATH에 없다. 전체 경로를 쓸 것:

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

## ⚠️ 패키지명 함정

`namespace`(코드 패키지)와 `applicationId`(스토어 패키지)가 **다르다**. 스토어 게시 후
applicationId는 영구 고정이라 분리한 것이다.

| 항목 | 값 |
|---|---|
| namespace (코드) | `com.connor.mymap` |
| applicationId (릴리스) | `com.yhgps.mymap` |
| applicationId (디버그) | `com.yhgps.mymap.debug` — `.debug` suffix, 앱 이름 "MyMap Dev" |

이 때문에 `am start -n com.yhgps.mymap/.MainActivity`는 **실패한다** (`.MainActivity`가
`com.yhgps.mymap.MainActivity`로 해석되는데 실제 클래스는 `com.connor.mymap.MainActivity`).
런처로 띄울 것:

```bash
~/Library/Android/sdk/platform-tools/adb shell monkey -p com.yhgps.mymap.debug -c android.intent.category.LAUNCHER 1
```

## 스크린샷 주의

이 기기의 `screencap`은 stdout에 경고 문구를 섞어 PNG를 깨뜨린다.
`adb exec-out screencap -p > x.png` 대신 기기에 저장 후 pull:

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell screencap -p /sdcard/s.png && $ADB pull /sdcard/s.png . && $ADB shell rm /sdcard/s.png
```

## 구조

```
com.connor.mymap/
├── data/
│   ├── tracking/    TrackingService(포그라운드 서비스), TrackingState(프로세스 전역 상태), TrackPointFilter(GPS 노이즈 제거)
│   ├── local/       파일 기반 저장소 — TrackingStorage(진행중), TrackingHistoryStorage(완료), ThumbnailStorage, MapFileStorage
│   ├── remote/      ManifestRepository(타일 카탈로그), MapDownloader, LocationProvider
│   ├── export/      RouteVideoExporter(MediaCodec+OpenGL mp4), RouteExportService(백그라운드 서비스)
│   └── thumbnail/   ThumbnailGenerator(MapSnapshotter로 목록 썸네일 PNG 생성)
├── ui/
│   ├── main/        MainScreen — 하단 탭 셸 (지도 / 이동 기록)
│   ├── map/         MapScreen, MapViewModel, MapLibreView(AndroidView 래퍼)
│   ├── profile/     ProfileScreen(목록+달력), SessionDetailScreen(재생), MonthCalendar
│   ├── download/    최초 실행 시 지역 자동감지 + 타일 다운로드
│   └── theme/       Color.kt — 브랜드 색 정의
└── util/            TrackingCalculator(거리·속도), Formats(단위 표기), PermissionHelper
```

**상태 관리**: DI 프레임워크 없음. ViewModel + StateFlow, 프로세스 전역 상태는
`TrackingState`/`RouteExportManager` 같은 싱글턴 `object`. 저장은 Room이 아니라 **파일**.

**지도 타일**: Cloudflare R2에 호스팅. 카탈로그는 `docs/manifest.json`
(GitHub Pages). 앱은 사용자 위치를 역지오코딩해 해당 국가/주 타일만 받는다.

## 중요한 제약

- **minSdk 24** — `java.time.LocalDate` 사용 불가. 날짜 계산은 `java.util.Calendar`로.
- **인메모리 포인트에 캡** — `TrackingState.MAX_LIVE_POINTS = 2000`. 지도 렌더링용이라
  잘려 있다. **거리·통계를 이 값으로 계산하면 장시간 세션에서 틀린다.** 저장·집계는
  반드시 디스크의 전체 포인트를 읽어서 할 것.
- **MapLibreView 인스턴스는 동시에 1개** — 2개가 동시에 살아있으면 네이티브 메모리가
  급증한다. 세션 상세가 열리면 홈 지도를 언마운트하는 로직이 `MainScreen`에 있다.
- **썸네일은 한 번 만들면 재생성 안 됨** — 렌더 스타일을 바꾸면 `ThumbnailStorage.STYLE_VERSION`을
  올려야 기존 캐시가 비워진다.

## 디자인 규칙

M3 롤(`MaterialTheme.colorScheme.*`)을 기본으로 쓴다. 브랜드 색은 **절제**해서 쓴다:

- **`BrandGradient`** (남색→청록): 히어로/축하 순간에만. 화면당 1회. 위 텍스트·아이콘은 흰색만.
  → 탭바, 일반 버튼, 카드 배경, 다이얼로그에는 **금지**.
- **`RecordingCoral`** `#F26B4E`: "기록·경로" 의미에만 — 경로선, 상태 배지 점, 기록 중 FAB, 도착점.
  → 일반 강조색(버튼·링크·헤드라인)으로 확장 **금지**.
- **`TrackBlue`** `#1F6FEB`: "내 위치"(퍽·재생 헤드) 전용. 기록된 경로와 의미를 구분한다.

경로선은 지도·썸네일·내보내기 영상 **모두** 흰 casing + 코랄 본선으로 통일되어 있다.
한쪽만 바꾸면 어긋나므로 `MapLibreView`, `ThumbnailGenerator`, `RouteVideoExporter`를 함께 볼 것.

## 커밋

- 브랜치는 `main` 직접 커밋 (이 저장소의 기존 관례).
- git identity `connor / grzbear14@gmail.com`는 의도된 설정이다. **변경하지 말 것.**
- 커밋·푸시는 **사용자가 명시적으로 요청할 때만** 한다.
- **커밋 전 반드시 `git status`로 범위를 확인할 것.** 이 작업 트리에는 미커밋 작업이
  오래 남아있는 경우가 있어, `git add -A`로 뭉뚱그리면 무관한 변경이 섞인다.
  변경한 파일만 명시적으로 스테이징한다.
- Claude가 작성한 커밋에만 `Co-Authored-By` 트레일러를 넣는다.

## 참고

- 커밋하지 않는 로컬 디렉토리: `play_store_assets/`, `play_release_package/`,
  `design_handoff_mymap_ui/`(디자인 핸드오프 문서), `.idea/`
- `SHOW_FOOTPRINTS_UI = false` — 발자취 기능은 코드는 유지하되 UI에서 숨긴 상태.

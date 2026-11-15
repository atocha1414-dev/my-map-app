# 작업 일지 — "종료하고 저장" 미동작·저장 실패 피드백 원인 분석 (2026-11-15)

## 요청 / 목적

사용자 피드백("종료하고 저장 버튼이 눌리지 않는다 / 저장이 안 된다", 2026-09-29 댓글)의 원인을 조사한다.

## 범위

- 읽기 전용 원인 분석. **코드·설정 변경 없음, 빌드·단위 시험·실기기 시험 없음.**
- 이 일지 파일만 새로 작성했다.
- 피드백 원문(Play Console 리뷰 등)은 저장소에 없어 **확인하지 못했다.** 기기·버전·재현 조건을 모른다.
- 참고 기록: [workflow-2026-09-14-tracking-save-dialog-fix.md](workflow-2026-09-14-tracking-save-dialog-fix.md).

## 확인한 사실

### 1. 9/14 "확인창 누락" 문제는 현재 코드에서 해결된 상태

- `MapScreen`의 `showSaveConfirm` → `AlertDialog` → `MapViewModel.onFinishAndSaveClick()` 연결이
  커밋본(HEAD, versionCode 8 / 1.0.7)과 현재 작업 트리(versionCode 10 / 1.0.9) 모두에 있다.
- 따라서 같은 원인이 출시본에 남아 있다고 볼 근거는 찾지 못했다.
  (9/14 당시 작업 트리에서만 빠져 있던 것으로 보인다. 실제 출시 빌드가 어느 커밋인지는 확인하지 않았다.)

### 2. 일시정지가 있으면 저장 시 앞부분 경로 포인트가 버려진다 (확정적 코드 결함)

- `onFinishAndSaveClick()`(`MapViewModel.kt` 349행 부근)은 시작 시각을
  `startedAtMillis = endedAt - TrackingState.elapsedDurationMillis()`로 **역산**한다.
- `elapsedDurationMillis()`는 **일시정지 시간을 뺀 활동 시간**이다
  (`TrackingStateTest.pauseKeepsElapsedTimeAndStopsActiveTimer` 등이 이 동작을 확인).
- `TrackingHistoryStorage.save()`는 `filterInWindow(startedAt, endedAt)`로
  `startedAt - 60초`보다 이른 포인트를 **버린다**(`WINDOW_GRACE_MILLIS = 60_000L`).
- 결과적으로 **기록 중 일시정지(종료 직전 일시정지 포함)한 시간의 합이 1분을 넘으면**
  그만큼 앞부분 포인트가 저장 파일에서 사라진다. 예시:
  - 30분 걷고 일시정지 → 10분 뒤 저장: 처음 약 9분 경로가 사라진다.
  - 30분 걷고 1시간 쉬고 30분 더 걷고 저장: 처음 30분 경로 전체가 사라진다.
  - 기록 후 일시정지 상태로 오래 두었다 저장: 포인트가 0개가 될 수 있다.
- 거리(META)는 버리기 전의 전체 포인트로 계산하므로, 목록에는 거리가 보이는데 경로·썸네일·재생이
  비거나 잘리는 불일치가 생긴다. 사용자에게는 "저장이 안 됐다"로 보일 수 있다.
- 시작 시각 자체도 실제보다 늦게 기록된다.
- 이 코드는 HEAD(출시 계열)에도 동일하게 있다(`MapViewModel.kt` HEAD 271·290행).

### 3. 저장 실패 시 사용자에게 아무 표시가 없다

- `onFinishAndSaveClick()`의 예외는 `Logger.e`만 남긴다. 성공 스낵바만 있고 실패 안내가 없다.
- 실패하면 현재 기록 파일은 남지만(`canClearCurrentTrack = false`), 화면 상태(`TrackingState`)는 이미 초기화되고
  서비스도 정지된 뒤라 사용자는 버튼이 반응하지 않았거나 저장이 안 된 것으로 느낄 수 있다.
- 실제 실패 빈도는 모른다(로그·Crashlytics 비치명 오류 미확인).

### 4. 기타 관찰 (영향 작음 / 미확정)

- 기록 중(일시정지 없이) 바로 저장하면 서비스의 `appendPoint`가 `serviceScope`에서 비동기로 쓰이는 중에
  ViewModel이 디스크를 읽고, 서비스 `onDestroy`가 `serviceScope`를 취소한다. 마지막 몇 포인트가 빠질 수 있다.
- "버튼이 눌리지 않는다": 확인창 연결은 정상이다. 시트 접힘 높이(116dp) 아래에 버튼이 있어 시트를
  올려야 보이는 점, 몰입 모드에서 시트가 숨겨지는 점은 있으나 원문 없이 원인으로 단정하지 않았다.
- 활동 시간 10초 미만이고 포인트가 0개면 저장하지 않고 아무 안내도 없다(의도된 동작이지만 안내 부재).

## 결론

- 9/14에 고친 확인창 누락은 현재 원인이 아니다.
- "저장이 안 된다"의 유력 원인은 **일시정지 시간이 빠진 시작 시각 역산 + 60초 시간 범위 필터로 인한 포인트 손실**이다.
  출시 계열 코드에 있고, 일시정지를 쓰는 일반적인 흐름에서 재현될 수 있다.
- 저장 실패 시 안내 부재가 체감 문제를 키운다.

## 수정 방향 (제안, 미구현)

1. 실제 세션 시작 시각을 일시정지와 무관하게 보존하고(또는 디스크 첫 포인트 시각 사용),
   저장 시간 범위를 `실제 시작 ~ 종료`로 잡는다. `durationMillis`는 활동 시간으로 유지한다.
2. 저장 실패 시 오류 안내를 띄우고, 남은 현재 기록을 다시 저장할 수 있게 한다.
3. 서비스 정지 전 대기 중인 `appendPoint`가 끝난 뒤 읽도록 순서를 보장한다.
4. 회귀 시험: 일시정지 구간이 긴 합성 세션을 저장해 포인트 수·첫 포인트 시각이 보존되는지 확인(실기기 `am instrument`, 합성 데이터).
5. 이미 잘려 저장된 기록은 원본 포인트가 없어 복구할 수 없다.

## 검증

- 실제 수행: 소스 읽기(`MapScreen`, `MapViewModel`, `TrackingService`, `TrackingState`, `TrackingStorage`,
  `TrackingHistoryStorage`), 기존 단위 시험 코드 확인, HEAD와 작업 트리 비교.
- 수행하지 않음: 빌드, 단위 시험 실행, 실기기 재현, 피드백 원문·Play Console·Crashlytics 확인.

## 미해결 / 다음 작업

- 9/29 피드백 원문(기기, 앱 버전, 재현 상황) 확인.
- 수정 승인 후 위 1~4 구현 및 실기기 검증.

## 커밋·푸시

- 하지 않음.

---

## 후속: 수정 및 실기기 검증 (2026-11-15)

### 요청

사용자 요청으로 위 분석의 수정 방향 1~4를 구현하고 연결된 실기기에서 검증했다.

### 변경 파일과 핵심 변경

- `app/src/main/java/com/connor/mymap/util/TrackingSaveWindow.kt` (신규)
  - `recordStartMillis()`: 시작 시각 = min(종료 − 활동 시간, 첫 포인트 시각). 일시정지가 있어도 앞부분 포인트가 저장 범위 안에 든다.
    미래 시각 포인트는 종료 시각 이후로 시작을 옮기지 못하게 제한.
  - `mergePendingLivePoints()`: 디스크 마지막 포인트보다 새로운 인메모리 포인트(아직 비동기로 쓰이지 않은 것)를 붙인다.
- `ui/map/MapViewModel.kt` `onFinishAndSaveClick()`
  - 위 두 함수 적용. `durationMillis`는 기존대로 활동 시간.
  - 저장 실패 시 `TrackingState.restorePaused(활동 시간)`으로 기록을 일시정지 상태로 되돌리고 `sessionSaveFailed` 이벤트 발행.
    현재 기록 파일은 기존대로 남긴다.
  - 저장 성공 후 보존 정책 정리 실패는 저장 실패로 취급하지 않게 분리.
  - 저장 진행 중 중복 호출 방지(`isSavingSession`).
- `data/tracking/TrackingState.kt`: `restorePaused()` 추가.
- `ui/map/MapScreen.kt`: 저장 실패 스낵바(Long) 추가.
- `res/values/strings.xml`, `res/values-en/strings.xml`: `tracking_save_failed` 추가.
- 시험: `app/src/test/.../util/TrackingSaveWindowTest.kt`(신규 7건), `TrackingStateTest.kt`(1건 추가),
  `app/src/androidTest/.../TrackingPausedSaveDeviceTest.kt`(신규 2건, 캐시 아래 격리 폴더 + 합성 포인트만 사용).

### 실제 수행한 검증과 결과

- 빌드: `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 성공(JDK 21).
- 단위 시험: `:app:testDebugUnitTest` 전체 74건 통과(실패·오류 0). 신규 `TrackingSaveWindowTest` 7건, `TrackingStateTest` 4건 포함.
- 실기기(Samsung SM-F711N, `com.yhgps.mymap.debug` versionCode 10):
  - 설치 전: 진행 중 서비스 없음, 진행 중 기록 파일 없음. 지도 3·기록 6 해시 기준값 확보.
  - debug APK·테스트 APK를 `adb install -r`로 갱신 설치(삭제·`pm clear` 없음).
  - `am instrument` `TrackingPausedSaveDeviceTest`: OK(2건).
    - 30분 기록 → 60분 일시정지 → 30분 기록 합성 세션 저장·재로드 시 62개 포인트 전부와 첫 포인트 시각 보존.
    - 수정 전 계산식으로 같은 세션을 저장하면 포인트가 줄어드는 것을 같은 경로에서 재현.
  - `am instrument -e recordDialogsProbe true` `TrackingRecordDialogsDeviceTest`: OK(1건).
    (인자 없이 실행했을 때는 가정 미충족으로 건너뜀 — 통과로 세지 않고 인자를 넣어 다시 실행.)
  - 시험 후 지도·기록 해시가 설치 전과 동일, 진행 중 기록 파일·격리 시험 폴더 잔여 없음.
  - 런처로 앱 실행 후 프로세스 유지, crash 버퍼 기록 없음.
- `git diff --check` 통과(변경 파일 대상).

### 미검증·미해결

- 실제 GPS로 기록 → 일시정지 → 저장하는 수동 실기기 흐름은 사용자 기록을 새로 만들게 되어 수행하지 않았다.
- 저장 실패 스낵바는 실패를 일부러 만들 방법이 없어 화면으로 확인하지 않았다(코드·빌드로만 확인).
- 이미 잘려 저장된 과거 기록은 원본 포인트가 없어 복구할 수 없다.
- 일시정지 중 프로세스가 종료되면 세션 표시 파일이 이미 지워져 있어 별도 복구 흐름이 다르게 동작할 수 있다(이번 범위 밖, 미조사).
- 9/29 피드백 원문은 여전히 확인하지 못했다. "버튼이 눌리지 않는다" 부분은 이번 수정으로 해결됐다고 단정하지 않는다.
- 릴리스 AAB는 만들지 않았다. versionCode는 바꾸지 않았다(배포 시 상향 필요 여부 확인).

### 다음 작업

- 실제 걷기 기록으로 일시정지 포함 저장을 한 번 확인.
- 배포 시 versionCode 상향 및 릴리스 빌드.

### 커밋·푸시

- 하지 않음. 작업 트리에는 이번 작업과 무관한 미커밋 변경이 많으므로, 커밋 시 위 파일만 명시적으로 스테이징해야 한다.

---

## 후속: 커밋 (2026-11-15)

- 사용자 요청으로 `main`에 커밋했다. 푸시하지 않았다.
- 범위: 저장 결함 수정만. 작업 트리의 `MapViewModel.kt`·`MapScreen.kt`·`strings.xml`에 섞인 10월 이후 미커밋 작업은 넣지 않았다.
  - 마지막 커밋(HEAD)을 임시 worktree로 꺼내 수정 부분만 옮기고, 그 차이만 스테이징했다.
  - HEAD에는 다국어 리소스가 없어, 커밋본의 저장 실패 문구는 기존 커밋 방식대로 한국어를 코드에 직접 썼다.
    작업 트리는 `R.string.tracking_save_failed`(한·영) 그대로다. 미커밋 다국어 작업을 커밋할 때 이 부분을 맞춘다.
  - 기기 시험의 재로드 호출을 커밋본에도 있는 `loadPoints(id, maxPoints = 0)`로 바꿨다.
- 커밋 후보(HEAD + 수정) 검증: `:app:testDebugUnitTest`(16건 통과), `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 성공.
  커밋 후보 APK는 versionCode 8이라 기기(versionCode 10)에 설치하지 않았다 — 커밋본 자체의 실기기 시험은 미수행.
- 작업 트리 쪽: 시험 파일 수정 후 `TrackingPausedSaveDeviceTest` 실기기 재실행 OK(2건), 지도·기록 해시 설치 전과 동일.

## 후속: 푸시 (2026-11-15)

- 사용자 요청으로 `a6c9856`을 `origin/main`(atocha1414-dev/my-map-app)에 푸시했다(`4127974..a6c9856`). 푸시 전 원격에 새 커밋 없음, 함께 올라간 다른 커밋 없음.
- 이 일지 파일이 `docs/` 아래라 GitHub Pages 배포 입력에 포함될 수 있다. 일지에는 합성 시험 값만 있고 실제 좌표·키는 없다. 실제 Pages 배포 상태는 확인하지 않았다.
- 이 "푸시" 항목은 사용자 요청으로 별도 일지 커밋으로 커밋·푸시했다.

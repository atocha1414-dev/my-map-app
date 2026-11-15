# 작업 일지 — 릴리스 빌드 1.0.10 (11) (2026-11-15)

## 요청 / 목적

저장 결함 수정([workflow-2026-11-15-finish-save-feedback-analysis.md](workflow-2026-11-15-finish-save-feedback-analysis.md))을 포함한 릴리스 AAB를 만든다.

## 사용자 결정

- Play Console 최고 versionCode는 10(사용자 확인) → 11로 올린다.
- 빌드 기준은 현재 작업 트리(미커밋 작업 + 저장 수정). 커밋된 코드는 versionCode 8이라 제외.

## 변경 파일

- `app/build.gradle.kts`: `versionCode 10 → 11`, `versionName 1.0.9 → 1.0.10`.
- `play_release_package/RELEASE_1.0.10_BUILD_11.md` 신규(제출 정보, 출시 노트 초안).
- 이 일지.

## 실제 수행한 검증과 결과

- `:app:testDebugUnitTest` 74건 통과, `:app:lintRelease` 오류 0(경고 104·힌트 5), `:app:bundleRelease` 성공(JDK 21).
- `jarsigner -verify` → `jar verified.` 서명 인증서 SHA-256이 이전 `MyMap-1.0.9-build10.aab`와 같다.
- 병합 릴리스 매니페스트: 패키지 `com.yhgps.mymap`, `versionCode=11`, `versionName=1.0.10`, `AD_ID` 없음.
- 산출물을 `~/Downloads/MyMap-1.0.10-build11.aab`로 복사. SHA-256 `068df29a…391e303` 일치. 기존 AAB 덮어쓰기 없음.

## 미검증·미해결

- 릴리스 빌드 자체의 실기기 설치·실행은 하지 않았다(실기기 시험은 같은 작업 트리의 debug 빌드로만 수행).
- `google-services.json`이 없어 이 번들에는 Crashlytics SDK가 들어가지 않았다. 이전 출시본 포함 여부는 확인하지 않았다.
- Play Console 업로드·제출은 하지 않았다. 최고 versionCode 10은 사용자 답변 기준이다.
- 릴리스가 미커밋 코드 기준이라 커밋만으로는 같은 번들을 재현할 수 없다. 미커밋 작업 커밋 시 `strings.xml`의 다국어 저장 실패 문구와 커밋본의 직접 표기 문구를 맞춰야 한다.

## 커밋·푸시

- 하지 않음(`app/build.gradle.kts` 버전 변경 포함 모두 미커밋).

## 후속: 버전 변경 커밋·푸시 (2026-11-16)

- 사용자 요청으로 `app/build.gradle.kts`의 버전 두 줄(versionCode 11, versionName 1.0.10)과 이 일지만 `main`에 커밋·푸시했다.
- 같은 파일의 다른 미커밋 변경은 넣지 않았다. 커밋본 파일에 버전 줄만 바꾼 내용을 스테이징했다.
- `play_release_package/`는 Git 추적 대상이 아니어서 제출 정보 문서는 커밋하지 않았다.
- 문서·버전 값만 커밋했고 추가 빌드·시험은 하지 않았다. 커밋된 코드는 여전히 미커밋 작업이 빠져 있어 이 버전 번호로 빌드한 AAB와 같지 않다.

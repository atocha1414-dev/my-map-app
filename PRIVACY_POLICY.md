# 개인정보처리방침 / Privacy Policy

**시행일 / Effective date:** 2026-05-08
**앱 / App:** MyMap (com.connor.mymap)
**개발자 / Developer:** Connor

---

## 한국어

### 1. 수집하는 정보

MyMap은 다음 정보를 사용자의 **기기 내부에만** 저장합니다.

- **위치 정보**: 위도·경도·정확도·시각. 사용자가 "기록 시작" 버튼을 눌렀을 때만 수집되며, "기록 정지"를 누르면 즉시 중단됩니다.
- **이동 경로 기록**: 위 위치 데이터를 모은 트랙 파일과 미리보기 썸네일.
- **약관 동의 여부**: 최초 실행 시 약관 동의 상태(true/false)만 저장.

### 2. 정보 사용 목적

수집한 정보는 오직 **사용자 본인의 이동 경로를 화면에 표시하고 기기에 보관**하기 위해 사용됩니다.

### 3. 저장 위치 및 보관 기간

- 모든 데이터는 사용자의 Android 기기 내부 저장소(앱 전용 영역)에만 저장되며 외부로 전송되지 않습니다.
- 6개월이 지난 기록은 자동 삭제되며, 총 용량이 300MB를 초과하면 오래된 기록부터 자동 삭제됩니다.
- 사용자가 앱 내에서 기록을 직접 삭제하거나, 앱을 제거하면 모든 데이터가 즉시 삭제됩니다.

### 4. 제3자 공유

위치 정보 및 이동 경로 데이터는 **어떤 제3자와도 공유되지 않으며**, 외부 서버에 업로드되지 않습니다.

### 5. 네트워크 사용

지도 타일 파일(MBTiles)을 Cloudflare R2 CDN(`https://pub-cf65b93161b54fe6aec05e54dbe1bfe7.r2.dev`)에서 다운로드하기 위해 인터넷에 접속합니다. 이 과정에서 위치 정보·이동 경로·개인 식별 정보는 전송되지 않습니다.

### 6. 권한 사용

| 권한 | 용도 |
|------|------|
| `ACCESS_FINE_LOCATION` | 정확한 GPS 좌표로 이동 경로 기록 |
| `ACCESS_BACKGROUND_LOCATION` | 사용자가 기록 시작 후 앱을 백그라운드로 보내거나 화면을 꺼도 경로 기록 지속 |
| `FOREGROUND_SERVICE_LOCATION` | 위치 기록 중임을 알림으로 항상 표시 (Android 14+ 요구사항) |
| `POST_NOTIFICATIONS` | 기록 진행 상황을 알림으로 표시 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 지도 타일 파일 다운로드 |

### 7. 사용자 권리

- 앱 내 "이동 기록" 탭에서 개별 또는 전체 기록을 직접 삭제할 수 있습니다.
- 앱을 제거하면 모든 데이터가 즉시 영구 삭제됩니다.

### 8. 어린이의 개인정보

본 앱은 만 13세 미만 어린이를 대상으로 하지 않으며, 어린이의 개인정보를 의도적으로 수집하지 않습니다.

### 9. 정책 변경

본 방침이 변경될 경우 시행일을 갱신해 동일한 위치에 게시합니다.

### 10. 문의

개인정보 관련 문의: **atocha1414@gmail.com**

---

## English

### 1. Information We Collect

MyMap stores the following information **only on the user's device**.

- **Location data**: Latitude, longitude, accuracy, and timestamp. Collected only when the user presses "Start Recording" and stopped immediately when "Stop Recording" is pressed.
- **Movement track records**: Track files and preview thumbnails generated from the above location data.
- **Terms agreement status**: A boolean flag indicating whether the user agreed to the initial terms.

### 2. Purpose of Use

Collected information is used **solely to display and locally store the user's own movement paths**.

### 3. Storage and Retention

- All data is stored only in the app's private storage on the user's Android device and is never transmitted externally.
- Records older than 6 months are deleted automatically, and if total size exceeds 300MB, the oldest records are deleted first.
- Users can delete records manually within the app, or uninstalling the app immediately removes all data.

### 4. Third-Party Sharing

Location and tracking data are **never shared with any third party** and are never uploaded to any external server.

### 5. Network Usage

The app connects to the internet only to download map tile files (MBTiles) from Cloudflare R2 CDN (`https://pub-cf65b93161b54fe6aec05e54dbe1bfe7.r2.dev`). No location data, tracking data, or personally identifying information is transmitted during this process.

### 6. Permissions

| Permission | Purpose |
|-----------|---------|
| `ACCESS_FINE_LOCATION` | Record movement paths with precise GPS coordinates |
| `ACCESS_BACKGROUND_LOCATION` | Continue recording when the user backgrounds the app or turns off the screen after starting tracking |
| `FOREGROUND_SERVICE_LOCATION` | Display a persistent notification while location tracking is active (Android 14+ requirement) |
| `POST_NOTIFICATIONS` | Show tracking progress notifications |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Download map tile files |

### 7. User Rights

- Users can delete individual or all records from the "Records" tab within the app.
- Uninstalling the app immediately and permanently deletes all data.

### 8. Children's Privacy

This app is not directed to children under 13 and does not knowingly collect personal information from children.

### 9. Changes to This Policy

If this policy changes, we will update the effective date and post the updated version at the same location.

### 10. Contact

For privacy-related inquiries: **atocha1414@gmail.com**

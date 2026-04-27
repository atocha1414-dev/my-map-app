# ────────────────────────────────────────────────────────
# 출시 빌드 R8/Proguard 룰
# 대부분의 라이브러리는 consumerProguardFiles로 자체 룰을 포함하므로
# 여기서는 reflection/native에 의존해 깨지기 쉬운 항목만 명시한다.
# ────────────────────────────────────────────────────────

# 스택 트레이스를 사람이 읽을 수 있게 라인 번호 보존
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# native 메서드 보호 (MapLibre가 native 의존)
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── MapLibre ───────────────────────────────────────────
# MapLibre는 native + reflection 의존이 많아 보수적으로 keep
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-keep class com.mapbox.android.gestures.** { *; }
-dontwarn org.maplibre.android.**
-dontwarn com.mapbox.android.gestures.**

# ─── Play Services Location ─────────────────────────────
-keep class com.google.android.gms.location.** { *; }
-keep interface com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ─── OkHttp / Okio ──────────────────────────────────────
# AAR consumer rule이 있지만 Conscrypt/BouncyCastle 경고 억제
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ─── Kotlin / Coroutines ────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ─── 도메인 모델 (GeoJSON 직렬화 등에서 reflection 가능성) ──
-keep class com.connor.mymap.domain.model.** { *; }

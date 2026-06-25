import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 출시 서명 정보. keystore.properties는 .gitignore되어 있고 로컬·CI 환경에서만 채워 사용한다.
// 파일이 없으면 release signingConfig는 비어 있고, debug 빌드는 영향 없음.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.connor.mymap"
    compileSdk = 36

    defaultConfig {
        // 스토어에 노출되는 패키지명(게시 후 영구 고정). 내부 코드 패키지(namespace)와는 분리한다.
        applicationId = "com.yhgps.mymap"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MAP_DOWNLOAD_BASE_URL",
            "\"https://pub-cf65b93161b54fe6aec05e54dbe1bfe7.r2.dev\""
        )
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // 출시 빌드는 R8로 미사용 코드 제거와 난독화를 적용한다.
            // 위치 기반 앱은 내부 구현과 네트워크 URL 노출을 최소화하는 편이 안전하다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // keystore.properties가 있을 때만 release 서명 적용. 없으면 unsigned 빌드.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)



    // MapLibre Android SDK
    implementation("org.maplibre.gl:android-sdk:11.5.0")

    // 경로재생 mp4 내보내기: 비트맵 시퀀스를 mp4(H.264)로 인코딩 (순수 자바, GL 불필요)
    implementation("org.jcodec:jcodec-android:0.2.5")

    // OkHttp — R2에서 파일 다운로드
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Play Services Location — GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ViewModel + Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    // DataStore — 최초 실행 약관 동의 여부 저장
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
}

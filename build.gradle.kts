// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 8.x에는 Kotlin이 내장되어 있지 않으므로 Android용 Kotlin 플러그인을 명시한다.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

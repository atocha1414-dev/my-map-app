package com.connor.mymap.util


import android.util.Log
import com.connor.mymap.BuildConfig

/**
 * 앱 전역 로깅 유틸
 * - 디버그 빌드에서만 로그 출력
 * - 릴리즈 빌드에서는 로그 무시 (성능/보안)
 */
object Logger {

    private const val TAG_PREFIX = "MyMapApp"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d("$TAG_PREFIX-$tag", message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i("$TAG_PREFIX-$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w("$TAG_PREFIX-$tag", message, throwable)
    }

    // 변경 이유: e()는 릴리즈 빌드에서도 출력해야 출시 후 logcat/크래시 리포팅에서
    // 장애 원인을 추적할 수 있다. 호출부에서 좌표 등 민감정보를 메시지에 넣지 말 것.
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX-$tag", message, throwable)
    }
}

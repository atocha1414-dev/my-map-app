package com.connor.mymap.util


import android.util.Log

/**
 * 앱 전역 로깅 유틸
 * - 디버그 빌드에서만 로그 출력
 * - 릴리즈 빌드에서는 로그 무시 (성능/보안)
 */
object Logger {

    private const val TAG_PREFIX = "MyMapApp"
    private const val DEBUG = true // 추후 BuildConfig.DEBUG로 교체

    fun d(tag: String, message: String) {
        if (DEBUG) Log.d("$TAG_PREFIX-$tag", message)
    }

    fun i(tag: String, message: String) {
        if (DEBUG) Log.i("$TAG_PREFIX-$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (DEBUG) Log.w("$TAG_PREFIX-$tag", message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX-$tag", message, throwable)
    }
}
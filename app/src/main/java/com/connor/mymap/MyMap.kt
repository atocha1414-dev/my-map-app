package com.connor.mymap

import android.app.Application
import com.connor.mymap.util.Logger
import org.maplibre.android.MapLibre

class MyMap : Application() {

    companion object {
        private const val TAG = "MyMap"
    }

    override fun onCreate() {
        super.onCreate()

        // MapLibre 초기화 — 앱 시작 시 한 번만
        MapLibre.getInstance(this)

        Logger.i(TAG, "Application initialized")
    }
}
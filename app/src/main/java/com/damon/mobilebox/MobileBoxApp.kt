package com.damon.mobilebox

import android.app.Application
import android.util.Log

class MobileBoxApp : Application() {
    companion object {
        const val TAG = "MobileBox"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MobileBoxApp 初始化成功，运行在原生 Android 移动端模式")
    }
}

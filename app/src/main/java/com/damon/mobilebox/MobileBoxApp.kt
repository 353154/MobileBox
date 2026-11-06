package com.damon.mobilebox

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * MobileBox 全局 Application
 * 作为 Hilt 依赖注入容器的顶层根入口
 */
@HiltAndroidApp
class MobileBoxApp : Application() {
    companion object {
        const val TAG = "MobileBox"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MobileBoxApp 初始化成功，Hilt 依赖注入容器已就绪")
    }
}

package com.damon.mobilebox.core.device

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备硬件与环境配置提供者
 * 通过 Hilt 自动注入，展示依赖反转 (Dependency Inversion) 机制
 */
@Singleton
class DeviceProfileProvider @Inject constructor() {

    fun getDeviceSummary(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE
        val sdkInt = Build.VERSION.SDK_INT
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return "$manufacturer $model (Android $androidVersion, API $sdkInt, $abi)"
    }

    fun isHiltReady(): Boolean = true
}

package com.damon.mobilebox.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.damon.mobilebox.core.device.DeviceProfileProvider
import com.damon.mobilebox.ui.theme.ThemeManager
import com.damon.mobilebox.ui.theme.TvBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 移动端主界面
 * 基于 Jetpack Compose + HorizontalPager + MD3 柔和色彩体系
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deviceProfileProvider: DeviceProfileProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开启系统级边到边沉浸式 (Edge-to-Edge)
        enableEdgeToEdge()

        setContent {
            val themeManager = remember { ThemeManager(applicationContext) }
            TvBoxTheme(themeManager = themeManager) {
                AnimatedMainScreen(themeManager = themeManager)
            }
        }
    }
}

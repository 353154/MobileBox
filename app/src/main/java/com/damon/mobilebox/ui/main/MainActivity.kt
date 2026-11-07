package com.damon.mobilebox.ui.main

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.damon.mobilebox.core.device.DeviceProfileProvider
import com.damon.mobilebox.data.watch.PlaybackSettings
import com.damon.mobilebox.ui.navigation.AppNavigation
import com.damon.mobilebox.ui.player.PipController
import com.damon.mobilebox.ui.theme.ThemeManager
import com.damon.mobilebox.ui.theme.TvBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 移动端主界面
 * 基于 Jetpack Compose + SharedTransitionLayout 共享元素转场 + MD3 柔和色彩体系
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deviceProfileProvider: DeviceProfileProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 埋点：判断进/出画中画时 Activity 是否被系统重建（重建会丢播放器状态）
        android.util.Log.d("MB-PIP", "onCreate savedInstanceState=${savedInstanceState != null}")
        // 开启系统级边到边沉浸式 (Edge-to-Edge)
        enableEdgeToEdge()
        PipController.attach(this)

        setContent {
            val themeManager = remember { ThemeManager(applicationContext) }
            TvBoxTheme(themeManager = themeManager) {
                AppNavigation(themeManager = themeManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackSettings.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        PlaybackSettings.onAppBackground()
    }

    /**
     * 用户按 Home 离开时：如果播放器正开着、且画中画开关打开就进小窗继续播。
     * 开关关掉时正常退到后台，是否继续出声由「后台播放」开关决定（播放器里处理）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        android.util.Log.d("MB-PIP", "onUserLeaveHint playerActive=${PipController.playerActive}")
        PipController.tryEnterPip()
    }

    /** 系统在进出画中画时回调，把状态同步给 Compose 层 */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.onPipModeChanged(isInPictureInPictureMode)
    }
}

package com.damon.mobilebox.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.damon.mobilebox.data.watch.PlaybackSettings
import java.lang.ref.WeakReference

/**
 * 画中画（PiP）状态与入口。
 *
 * 架构说明：本工程是「单 Activity + Compose 导航」，播放器不是独立 Activity，
 * 所以进 PiP 时整个窗口会被缩小。好在详情页的播放器层本身就是 `fillMaxSize + zIndex(10f)`
 * 盖在最上层的，只要在 PiP 态下把播放器铺满、隐藏控制条与选集面板，小窗里自然只剩画面。
 *
 * 生命周期：
 * - [attach] 在 MainActivity.onCreate 调用，持有弱引用；
 * - [playerActive] 由 MobileVideoPlayer 的组合/销毁维护；
 * - 用户按 Home 时 MainActivity.onUserLeaveHint 调 [tryEnterPip]；
 * - 系统回调 onPictureInPictureModeChanged → [onPipModeChanged] 更新 Compose 可观察状态。
 */
object PipController {

    private var activityRef: WeakReference<Activity>? = null

    /** Compose 可观察：当前是否处于 PiP 小窗 */
    var isInPip by mutableStateOf(false)
        private set

    /** 播放器是否正在（某个页面里）活跃。决定按 Home 要不要进小窗。 */
    @Volatile
    var playerActive: Boolean = false

    /**
     * 当前可见路由（由 AppNavigation 上报）。
     *
     * 只靠 [playerActive] 不够：NavHost 的 280ms 退出转场期间，详情页仍在组合中、
     * 播放器还没 dispose，但**用户眼前已经是首页**。这时按 Home 会进 PiP，
     * 小窗里显示的却是首页（真实踩到过）。所以再加一道「可见路由必须是详情页」的校验。
     */
    @Volatile
    var visibleRoute: String? = null

    /** 最近一次拿到的画面比例，供 PiP 窗口尺寸使用 */
    @Volatile
    private var aspectWidth: Int = 16

    @Volatile
    private var aspectHeight: Int = 9

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** ExoPlayer 回调视频尺寸时调用（旋转的竖版视频也能得到正确的窗口形状） */
    fun updateAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            aspectWidth = width
            aspectHeight = height
        }
    }

    fun onPipModeChanged(inPip: Boolean) {
        isInPip = inPip
        android.util.Log.d("MB-PIP", "onPipModeChanged($inPip) playerActive=$playerActive")
    }

    /**
     * 尝试进入 PiP。只有系统版本够、播放器活跃、Activity 可用时才真正进入。
     * @return 是否成功进入
     */
    fun tryEnterPip(): Boolean {
        // 设置页的「画中画」开关：关掉后按 Home 就是普通退后台，不缩小窗口。
        if (!PlaybackSettings.pipEnabled) {
            android.util.Log.d("MB-PIP", "skip: 画中画开关已关闭")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            android.util.Log.d("MB-PIP", "skip: API < 26")
            return false
        }
        if (!playerActive) {
            android.util.Log.d("MB-PIP", "skip: 播放器未激活（当前不在播放页）")
            return false
        }
        // 转场期间播放器实例还在、但用户看到的已经是别的页面，这种情况不能进小窗
        if (visibleRoute?.startsWith("detail") != true) {
            android.util.Log.d("MB-PIP", "skip: 当前可见路由不是详情页 (visibleRoute=$visibleRoute)")
            return false
        }
        val activity = activityRef?.get() ?: run {
            android.util.Log.d("MB-PIP", "skip: Activity 引用为空")
            return false
        }
        if (activity.isFinishing || activity.isDestroyed) {
            android.util.Log.d("MB-PIP", "skip: Activity 已结束")
            return false
        }
        return try {
            val entered = activity.enterPictureInPictureMode(buildParams())
            android.util.Log.d(
                "MB-PIP",
                "enterPictureInPictureMode -> $entered, ratio=${aspectWidth}x$aspectHeight"
            )
            entered
        } catch (e: Exception) {
            // 某些定制 ROM 或不允许 PiP 的场景会抛 IllegalStateException，静默忽略即可
            android.util.Log.w("MB-PIP", "进入画中画失败: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /**
     * Rational 只接受 1:2.39 ~ 2.39:1 之间的比例，超范围会直接抛异常，
     * 所以对极端比例（超宽电影、竖版短视频）做一次钳制。
     */
    private fun buildParams(): PictureInPictureParams {
        val ratio = aspectWidth.toFloat() / aspectHeight.toFloat()
        val safe = when {
            ratio < 0.42f -> Rational(42, 100)
            ratio > 2.39f -> Rational(239, 100)
            else -> Rational(aspectWidth, aspectHeight)
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(safe)
            .build()
    }
}

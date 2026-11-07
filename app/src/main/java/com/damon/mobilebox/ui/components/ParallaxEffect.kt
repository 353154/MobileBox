package com.damon.mobilebox.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * 视差旋转角度实体
 * @param roll 左右倾斜比率 (-1f..1f)，相对握持中立姿态
 * @param pitch 上下俯仰比率 (-1f..1f)，相对握持中立姿态
 */
data class ParallaxAngles(
    val roll: Float = 0f,
    val pitch: Float = 0f
)

/**
 * 传感器状态放在组合树之外，跨页面保留角度与握持中立基线。
 *
 * 【监听器生命周期约定，别再改回引用计数注销】
 * 首页进入详情页后会被 NavHost 移出组合。旧实现用 refCount 在 onDispose 里注销监听器，
 * 返回首页再重新注册时 smoothX/Y 仍是进详情页之前的旧值，需要若干帧低通追到当前姿态，
 * 表现为「返回后所有卡片自己缓缓动一下」。
 * 因此监听器改为应用级常驻：只要 3D 开关是开的，详情页期间也持续输出角度，
 * 返回首页时角度就是最新值，零收敛。只有用户主动关闭开关才注销。
 */
private object ParallaxRuntime {
    val angles = mutableStateOf(ParallaxAngles())
    var sensorManager: SensorManager? = null
    var listener: SensorEventListener? = null
    var baselineX = 0f
    var baselineY = 0f
    var sampleCount = 0
    var smoothX = 0f
    var smoothY = 0f
    // 输出角度的短低通状态：抹平单帧不连续
    var outRoll = 0f
    var outPitch = 0f
    var initialized = false
    var enabled = true
    // 重新注册后首帧直接锚定当前姿态，不从旧值收敛
    var needsReanchor = false
    var justReanchored = false
}

/**
 * 以 State 形式向下提供姿态角。
 * 关键：graphicsLayer 在**绘制阶段**读取 .value，传感器 20ms 一帧的数据只让绘制层失效，
 * 不会引发整棵组合树每帧重组。
 */
val LocalParallaxAngles = compositionLocalOf<State<ParallaxAngles>> {
    mutableStateOf(ParallaxAngles())
}
val LocalParallaxEnabled = compositionLocalOf { true }

/**
 * 监听加速度传感器，输出相对「用户当前握持姿态」的倾斜比率。
 * 以启动后采样的中立姿态为零点，任何握持姿势下都能对称响应。
 */
@Composable
fun rememberParallaxAngles(enabled: Boolean = true): State<ParallaxAngles> {
    val context = LocalContext.current
    val rt = ParallaxRuntime

    DisposableEffect(enabled) {
        rt.enabled = enabled
        if (!enabled) {
            // 用户主动关闭开关：注销监听器、输出归零，卡片回到静态
            rt.sensorManager?.unregisterListener(rt.listener)
            rt.sensorManager = null
            rt.listener = null
            rt.angles.value = ParallaxAngles()
            rt.outRoll = 0f
            rt.outPitch = 0f
            rt.needsReanchor = true
            onDispose { }
        } else {
            if (rt.sensorManager == null) {
                val manager = context.applicationContext
                    .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event == null || !rt.enabled) return
                        val x = event.values[0]
                        val y = event.values[1]

                        if (rt.needsReanchor || !rt.initialized) {
                            // 重新注册后直接对齐当前姿态，避免从旧值低通收敛
                            rt.smoothX = x
                            rt.smoothY = y
                            rt.initialized = true
                            rt.needsReanchor = false
                            rt.justReanchored = true
                        }
                        rt.smoothX += 0.22f * (x - rt.smoothX)
                        rt.smoothY += 0.22f * (y - rt.smoothY)

                        // 基线只在首次建立一次，跨页面保留
                        if (rt.sampleCount < 30) {
                            rt.baselineX += (rt.smoothX - rt.baselineX) / (rt.sampleCount + 1)
                            rt.baselineY += (rt.smoothY - rt.baselineY) / (rt.sampleCount + 1)
                            rt.sampleCount++
                            return
                        }
                        val roll = (-(rt.smoothX - rt.baselineX) / 2.2f).coerceIn(-1f, 1f)
                        val pitch = ((rt.smoothY - rt.baselineY) / 2.2f).coerceIn(-1f, 1f)
                        if (rt.justReanchored) {
                            rt.outRoll = roll
                            rt.outPitch = pitch
                            rt.justReanchored = false
                        } else {
                            rt.outRoll += 0.35f * (roll - rt.outRoll)
                            rt.outPitch += 0.35f * (pitch - rt.outPitch)
                        }
                        rt.angles.value = ParallaxAngles(rt.outRoll, rt.outPitch)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                if (sensor != null) {
                    manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    rt.sensorManager = manager
                    rt.listener = listener
                }
            }
            // 页面被移出组合时故意不注销：详情页期间角度持续更新，
            // 返回首页无收敛过程，卡片不会「自己缓缓动一下」。
            onDispose { }
        }
    }
    return rt.angles
}

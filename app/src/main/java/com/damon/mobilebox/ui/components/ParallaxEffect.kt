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
import androidx.compose.runtime.remember
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
 * 以 State 形式向下提供姿态角。
 * 关键：graphicsLayer 在**绘制阶段**读取 .value，传感器 20ms 一帧的数据只让绘制层失效，
 * 不会引发 AnimatedMainScreen 整棵组合树每帧重组（旧实现用 animateFloatAsState 返回值，
 * 既触发全树重组，又用慢弹簧把高频姿态变化抹平，导致肉眼几乎看不到 3D 效果）。
 */
val LocalParallaxAngles = compositionLocalOf<State<ParallaxAngles>> {
    mutableStateOf(ParallaxAngles())
}
val LocalParallaxEnabled = compositionLocalOf { true }

/**
 * 监听加速度传感器，输出相对「用户当前握持姿态」的倾斜比率。
 *
 * 旧实现以固定 y=5.5f（约 45° 握持）为零点，竖屏竖直握持时 y≈9.8 会直接饱和到 1f，
 * 上下俯仰维度完全失去响应。这里改为启动后采集中立基线，任何握持姿势下都能对称响应。
 */
@Composable
fun rememberParallaxAngles(enabled: Boolean = true): State<ParallaxAngles> {
    val context = LocalContext.current
    val angles = remember { mutableStateOf(ParallaxAngles()) }

    DisposableEffect(enabled) {
        if (!enabled) {
            angles.value = ParallaxAngles()
            onDispose { }
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            // 加速度计覆盖机型最广，缺失时再退回重力传感器
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

            var baselineX = 0f
            var baselineY = 0f
            var sampleCount = 0
            var smoothX = 0f
            var smoothY = 0f
            var initialized = false

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null) return
                    val x = event.values[0]
                    val y = event.values[1]

                    if (!initialized) {
                        smoothX = x
                        smoothY = y
                        initialized = true
                    }
                    // 低通滤波抑制手持抖动，保留倾斜趋势
                    smoothX += 0.22f * (x - smoothX)
                    smoothY += 0.22f * (y - smoothY)

                    // 前 30 帧建立中立基线，采样期不输出倾斜，避免开场姿态突变
                    if (sampleCount < 30) {
                        baselineX += (smoothX - baselineX) / (sampleCount + 1)
                        baselineY += (smoothY - baselineY) / (sampleCount + 1)
                        sampleCount++
                        return
                    }

                    // 相对基线偏差：约 ±2.2 m/s² 达到满量程，轻微倾斜即可见
                    val roll = (-(smoothX - baselineX) / 2.2f).coerceIn(-1f, 1f)
                    val pitch = ((smoothY - baselineY) / 2.2f).coerceIn(-1f, 1f)
                    angles.value = ParallaxAngles(roll, pitch)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            if (sensor != null) {
                sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose {
                sensorManager?.unregisterListener(listener)
                angles.value = ParallaxAngles()
            }
        }
    }
    return angles
}

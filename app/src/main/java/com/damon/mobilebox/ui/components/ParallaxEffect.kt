package com.damon.mobilebox.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * 视差旋转角度实体
 * @param roll 左右倾斜比率 (-1f..1f)，向右倾斜为正
 * @param pitch 上下俯仰比率 (-1f..1f)，向前倾斜为正
 */
data class ParallaxAngles(
    val roll: Float = 0f,
    val pitch: Float = 0f
)

val LocalParallaxAngles = compositionLocalOf { ParallaxAngles() }
val LocalParallaxEnabled = compositionLocalOf { true }

/**
 * 监听硬件重力/加速度传感器，计算手持平滑倾斜姿态角
 * 经过灵敏度增强放大，确保在真机轻微手持倾斜时能展现清晰震撼的 3D 视差
 */
@Composable
fun rememberParallaxAngles(enabled: Boolean = true): ParallaxAngles {
    val context = LocalContext.current
    if (!enabled) return ParallaxAngles(0f, 0f)

    var rawRoll by remember { mutableFloatStateOf(0f) }
    var rawPitch by remember { mutableFloatStateOf(0f) }

    DisposableEffect(enabled) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // 优先使用重力传感器，若不支持则降级使用加速度计
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var lastRoll = 0f
        var lastPitch = 0f
        val filterFactor = 0.25f // 滤波响应因子

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]

                // 左右倾斜比率：轻晃手机 x 偏移约 1.5~3，除以 3.2f 进行放大以提升跟手度
                val targetRoll = (-(x / 3.2f)).coerceIn(-1f, 1f)
                // 上下俯仰比率：以约 45° 手持为基准（y ≈ 5.5f），除以 3.2f
                val targetPitch = ((y - 5.5f) / 3.2f).coerceIn(-1f, 1f)

                lastRoll += filterFactor * (targetRoll - lastRoll)
                lastPitch += filterFactor * (targetPitch - lastPitch)

                rawRoll = lastRoll
                rawPitch = lastPitch
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // 采用 Compose 弹簧阻尼平滑插值
    val smoothRoll by animateFloatAsState(
        targetValue = rawRoll,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "parallaxRoll"
    )
    val smoothPitch by animateFloatAsState(
        targetValue = rawPitch,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "parallaxPitch"
    )

    return ParallaxAngles(roll = smoothRoll, pitch = smoothPitch)
}

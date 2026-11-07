package com.damon.mobilebox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.ui.theme.ThemeColorMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 带有手绘圆形色盘 (Color Wheel) 的主题色选择器弹窗
 * 支持选择：默认极客蓝 / 跟随系统壁纸 / 自定义交互式色盘取色
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    initialMode: ThemeColorMode,
    onDismiss: () -> Unit,
    onSave: (ThemeColorMode, Int) -> Unit
) {
    var selectedMode by remember { mutableStateOf(initialMode) }
    
    // 从初始颜色提取 HSV
    val hsv = remember(initialColor) {
        val array = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), array)
        array
    }
    
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    
    val currentColor = Color.hsv(hue, saturation, value)
    val hexString = String.format("#%06X", 0xFFFFFF and currentColor.toArgb())

    StandardDialog(
        onDismissRequest = onDismiss,
        scrollable = false
    ) {
        Text(
            "选择主题色",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Column(modifier = Modifier.fillMaxWidth()) {
                ThemeColorMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedMode = mode }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.title, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (selectedMode == ThemeColorMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 实时预览与 HEX
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(currentColor)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(text = "预览色值", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = hexString,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 核心重构：直观的圆形交互色盘 (控制 Hue 和 Saturation)
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ColorWheel(
                            hue = hue,
                            saturation = saturation,
                            onColorChange = { h, s -> 
                                hue = h
                                saturation = s
                            },
                            modifier = Modifier.size(200.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 明度调节滑块
                    Text("明度 (Brightness)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = currentColor,
                            activeTrackColor = currentColor.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onSave(selectedMode, currentColor.toArgb()) }) {
                Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- 新增：纯 Compose 手绘交互式色盘组件 ---
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    onColorChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnColorChange by rememberUpdatedState(onColorChange)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                // 处理拖拽手势
                detectDragGestures { change, _ ->
                    change.consume()
                    updateColorFromPosition(change.position.x, change.position.y, size.width.toFloat(), currentOnColorChange)
                }
            }
            .pointerInput(Unit) {
                // 处理点击手势
                detectTapGestures { offset ->
                    updateColorFromPosition(offset.x, offset.y, size.width.toFloat(), currentOnColorChange)
                }
            }
    ) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)

        // 1. 绘制色相光谱 (SweepGradient)
        val spectrumColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        drawCircle(
            brush = Brush.sweepGradient(colors = spectrumColors, center = center),
            radius = radius
        )

        // 2. 绘制饱和度渐变 (中心白，边缘透明)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius
        )

        // 3. 计算并绘制选取器指示环 (Thumb)
        val angleRad = Math.toRadians(hue.toDouble())
        val thumbRadius = saturation * radius
        val thumbX = center.x + thumbRadius * cos(angleRad).toFloat()
        val thumbY = center.y + thumbRadius * sin(angleRad).toFloat()

        // 绘制白色外环与深色内环，确保在任何颜色背景下都清晰可见
        drawCircle(
            color = Color.White,
            radius = 12.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 3.dp.toPx())
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f),
            radius = 12.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

// 坐标转 HSV 工具函数
private fun updateColorFromPosition(x: Float, y: Float, wheelSize: Float, onColorChange: (Float, Float) -> Unit) {
    val radius = wheelSize / 2
    val centerX = radius
    val centerY = radius
    
    val dx = x - centerX
    val dy = y - centerY
    
    // 计算距离决定饱和度 (限制在 0..1 之间)
    val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val saturation = (distance / radius).coerceIn(0f, 1f)
    
    // 计算角度决定色相 (转换为 0..360)
    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (angle < 0) angle += 360f
    
    onColorChange(angle, saturation)
}

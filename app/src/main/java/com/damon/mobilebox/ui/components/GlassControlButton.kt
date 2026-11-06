package com.damon.mobilebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃控制按钮（iOS 26 Liquid Glass 的轻量近似）。
 *
 * 不做背景模糊副本：按钮本来就叠在已经是毛玻璃的预览层上，
 * 半透明渐变 + 白色细描边 + 上缘弧光 + 柔和投影即可透出背后画面并读成"玻璃"，
 * 只有一次绘制，没有逐帧模糊开销。
 *
 * 播放 / 暂停两态共用本组件，保证尺寸、中心点、材质完全一致 —— 这是位置对齐的根本保证。
 */
@Composable
fun GlassControlButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    iconSize: Dp = 32.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.38f),
                spotColor = Color.Black.copy(alpha = 0.38f)
            )
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.36f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.22f)
                    )
                )
            )
            .drawBehind {
                // 上缘弧光：玻璃厚度的关键识别特征
                drawArc(
                    color = Color.White.copy(alpha = 0.55f),
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    style = Stroke(width = 1.6f.dp.toPx())
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.58f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

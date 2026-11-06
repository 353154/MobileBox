package com.damon.mobilebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 现代轻量级极光渐变背景容器 (ModernBackgroundContainer)
 * 采用双径向渐变 (Radial Gradient Glow) 绘制，避免 RenderEffect 造成的低端机掉帧问题，
 * 并能随系统深浅模式及自定义主题色动态自适应。
 */
@Composable
fun ModernBackgroundContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawBehind {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // 绘制左上角的主题色光晕
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.12f), // 中心透明度略高
                            Color.Transparent // 边缘消散
                        ),
                        center = Offset(x = canvasWidth * 0.2f, y = canvasHeight * 0.1f),
                        radius = canvasWidth * 0.8f
                    ),
                    radius = canvasWidth * 0.8f,
                    center = Offset(x = canvasWidth * 0.2f, y = canvasHeight * 0.1f)
                )

                // 绘制右侧中下方的主题色光晕，营造空间交错感
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.08f), 
                            Color.Transparent
                        ),
                        center = Offset(x = canvasWidth * 0.9f, y = canvasHeight * 0.6f),
                        radius = canvasWidth * 0.7f
                    ),
                    radius = canvasWidth * 0.7f,
                    center = Offset(x = canvasWidth * 0.9f, y = canvasHeight * 0.6f)
                )
            }
    ) {
        content()
    }
}

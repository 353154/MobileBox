package com.damon.mobilebox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 全局统一的标准海报组件 (可在首页、搜索、分类等所有页面复用)
 * 严格按照 MD3 规范：2:3 黄金比例、右上角 6dp 评分暗底角标、渐入动效、自动跟随主题深浅色
 * 支持硬件传感器驱动的多层 3D 视差（外框倾斜 + 内图开窗景深位移 + 表面流光 + 角标悬浮）
 */
@Composable
fun StandardPosterCard(
    title: String,
    subtitle: String,
    score: String = "",
    posterUrl: String = "",
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    var isMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isMounted = true
    }
    val alphaAnim by animateFloatAsState(
        targetValue = if (isMounted) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "posterAlpha"
    )

    val parallax = LocalParallaxAngles.current
    val isParallaxEnabled = LocalParallaxEnabled.current

    Column(
        modifier = modifier
            .graphicsLayer { alpha = alphaAnim }
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        // 海报图片外部 3D 视差透视容器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f) // 严格约束 2:3 黄金海报比例
                .graphicsLayer {
                    if (isParallaxEnabled) {
                        rotationY = parallax.roll * 14f   // 左右倾斜 3D 旋转 (±14°)
                        rotationX = -parallax.pitch * 14f // 上下俯仰 3D 旋转 (±14°)
                        cameraDistance = 14f * density    // 强化 3D 视差相机景深焦距
                    }
                }
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant) // 使用主题的次级表面色作为占位底色
        ) {
            // 内层图片容器：通过反向平移产生强烈的“开窗”景深视差
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isParallaxEnabled) {
                            // 内层反向微移与小幅放大，消除黑边并营造立体景深
                            translationX = -parallax.roll * 14.dp.toPx()
                            translationY = -parallax.pitch * 14.dp.toPx()
                            scaleX = 1.12f
                            scaleY = 1.12f
                        }
                    }
            ) {
                if (posterUrl.isNotEmpty()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            // 统一的精美评分角标 (绝对定位在右上角，伴随轻微前凸立体位移)
            if (score.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .graphicsLayer {
                            if (isParallaxEnabled) {
                                // 评分角标正向微凸悬浮，构成前后 3 层空间景深
                                translationX = parallax.roll * 6.dp.toPx()
                                translationY = parallax.pitch * 6.dp.toPx()
                            }
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f)) // 恒定半透明黑底，防文字受背景图干扰
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = score,
                        color = Color.White, // 评分始终保持高亮白色
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 陀螺仪 3D 动态流光反射层 (高对比度 Specular Sheen 反光)
            if (isParallaxEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val highlightX = size.width * (0.5f - parallax.roll * 0.6f)
                            val highlightY = size.height * (0.5f - parallax.pitch * 0.6f)
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.35f), // 高对比白光高光
                                        Color.Transparent
                                    ),
                                    center = Offset(highlightX, highlightY),
                                    radius = size.width * 0.95f
                                )
                            )
                        }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // 标题排版
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 副标题排版
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

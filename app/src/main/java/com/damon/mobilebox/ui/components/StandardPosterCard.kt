package com.damon.mobilebox.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 全局统一的标准海报组件 (可在首页、搜索、分类等所有页面复用)
 * 严格按照 0.7f 黄金竖版比例，与详情页 1:1 严格对齐，彻底消除飞行形变
 * 严格控制 Modifier 链顺序：sharedBounds 位于 clip 之前，使用 8dp 圆角
 * 禁用 Coil crossfade 杜绝动画闪烁，结合硬件传感器 3D 视差
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StandardPosterCard(
    title: String,
    subtitle: String,
    score: String = "",
    posterUrl: String = "",
    movieId: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
    val context = LocalContext.current

    Column(
        modifier = modifier
            .graphicsLayer { alpha = alphaAnim }
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        // 海报图片外部 3D 视差透视容器：强制统一 aspectRatio(0.7f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f) // 核心修复2：统一比例为 0.7f
                .graphicsLayer {
                    if (isParallaxEnabled) {
                        rotationY = parallax.roll * 14f   // 左右倾斜 3D 旋转 (±14°)
                        rotationX = -parallax.pitch * 14f // 上下俯仰 3D 旋转 (±14°)
                        cameraDistance = 14f * density    // 强化 3D 视差相机景深焦距
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 内层图片容器：通过反向平移产生强烈的“开窗”景深视差
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isParallaxEnabled) {
                            translationX = -parallax.roll * 14.dp.toPx()
                            translationY = -parallax.pitch * 14.dp.toPx()
                            scaleX = 1.12f
                            scaleY = 1.12f
                        }
                    }
            ) {
                if (posterUrl.isNotEmpty()) {
                    // 核心修复3：sharedBounds 必须放在 clip 之前！
                    val imageModifier = Modifier
                        .fillMaxSize()
                        .let { m ->
                            if (sharedTransitionScope != null && animatedVisibilityScope != null && !movieId.isNullOrEmpty()) {
                                with(sharedTransitionScope) {
                                    m.sharedBounds(
                                        sharedContentState = rememberSharedContentState(key = "poster_$movieId"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                                        // 动画期间保持与静态态相同的圆角 (8dp)
                                        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
                                    )
                                }
                            } else {
                                m
                            }
                        }
                        .clip(RoundedCornerShape(8.dp)) // 静态圆角裁剪放在最后

                    AsyncImage(
                        model = remember(posterUrl) {
                            ImageRequest.Builder(context)
                                .data(posterUrl)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = imageModifier
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
                                translationX = parallax.roll * 6.dp.toPx()
                                translationY = parallax.pitch * 6.dp.toPx()
                            }
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = score,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 陀螺仪 3D 动态流光反射层
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
                                        Color.White.copy(alpha = 0.35f),
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

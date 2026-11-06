package com.damon.mobilebox.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalDensity
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
/**
 * 海报入场淡入闸门
 * Pager 的离屏页面会被销毁，切回首页/分类时卡片会重新组合。
 * 若每张卡片每次挂载都从 alpha=0 播放 350ms 淡入，就会在每次切 Tab 时出现整屏“闪一下”。
 * 这里把入场动画限定为进程启动后的首批海报（700ms 窗口），之后挂载的卡片直接以 alpha=1 呈现。
 */
private object PosterEntranceGate {
    private val firstMountMs = java.util.concurrent.atomic.AtomicLong(0L)

    fun shouldAnimate(): Boolean {
        val now = System.currentTimeMillis()
        val first = firstMountMs.get()
        if (first == 0L) {
            firstMountMs.compareAndSet(0L, now)
            return true
        }
        return now - first < 700L
    }
}

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
    // 仅首批海报播放入场淡入；切 Tab 重新组合的卡片直接完整显示，避免整屏闪烁
    var isMounted by remember { mutableStateOf(!PosterEntranceGate.shouldAnimate()) }
    LaunchedEffect(Unit) {
        isMounted = true
    }
    val alphaAnim by animateFloatAsState(
        targetValue = if (isMounted) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "posterAlpha"
    )

    val parallaxState = LocalParallaxAngles.current
    val isParallaxEnabled = LocalParallaxEnabled.current
    val density = LocalDensity.current.density
    val context = LocalContext.current

    // 仅当评分为有效正数时才渲染角标 (0.0 / 空值视为无评分)
    val hasScore = remember(score) { (score.toFloatOrNull() ?: 0f) > 0f }

    Column(
        modifier = modifier
            .graphicsLayer { alpha = alphaAnim }
            .fillMaxWidth()
            // 这里不裁剪：Column 的 clip 会连带裁掉海报 3D 旋转后超出卡片宽度的近边，
            // 让透视形变退化成中心缩放。圆角由海报 Box 自身的 clip 保证。
            .clickable { onClick() }
    ) {
        // 海报图片容器：强制统一 aspectRatio(0.7f)，平滑过渡
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                // 3D 陀螺仪视差：放在 clip 外层，让「已裁剪成圆角的整张卡片」一起做透视变换。
                // 旧实现把旋转放在图片内层，四角被父 Box 裁掉后只剩中心轻微缩放，
                // 不产生边缘透视形变，肉眼几乎看不出倾斜。
                // 角度取自相对握持基线的 State，绘制阶段读取，不触发全树重组。
                .graphicsLayer {
                    if (isParallaxEnabled) {
                        val p = parallaxState.value
                        rotationY = p.roll * 14f
                        rotationX = -p.pitch * 14f
                        cameraDistance = 14f * density
                        translationX = p.roll * 5f * density
                        translationY = -p.pitch * 5f * density
                        scaleX = 1.04f
                        scaleY = 1.04f
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (posterUrl.isNotEmpty()) {
                val imageModifier = Modifier
                    .fillMaxSize()
                    .let { m ->
                        if (sharedTransitionScope != null && animatedVisibilityScope != null && !movieId.isNullOrEmpty()) {
                            with(sharedTransitionScope) {
                                m.sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "poster_${movieId.trim()}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        tween(durationMillis = 380, easing = FastOutSlowInEasing)
                                    },
                                    resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
                                )
                            }
                        } else {
                            m
                        }
                    }
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

            // 统一的精美评分角标 (绝对定位在右上角，伴随轻微前凸立体位移)
            // 纯黑底板在深色海报上会融进背景，改用高不透明度的浅底 + 深色文字与深色细描边，
            // 明暗两类海报上都能保持边界；同时过滤 0.0 / 无效评分。
            if (hasScore) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .graphicsLayer {
                            if (isParallaxEnabled) {
                                val p = parallaxState.value
                                translationX = p.roll * 6.dp.toPx()
                                translationY = p.pitch * 6.dp.toPx()
                            }
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                        .border(0.5.dp, Color.Black.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = score,
                        color = Color(0xFF15181F),
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
                            val p = parallaxState.value
                            val highlightX = size.width * (0.5f - p.roll * 0.6f)
                            val highlightY = size.height * (0.5f - p.pitch * 0.6f)
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

        // 标题排版：单行截断 + 固定行高容器
        // 说明：仅靠 maxLines=1 仍会受到字体回退、Emoji、西文混排的行高差异影响，
        // 这里把文本块高度固定下来，保证同一行网格内的卡片高度像素级一致。
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(19.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        // 副标题排版：严格单行 + 固定高度，长字段一律省略号收口
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
        )
    }
}

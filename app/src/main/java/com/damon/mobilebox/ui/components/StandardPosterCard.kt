package com.damon.mobilebox.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 当前详情页对应的共享元素 key（由 AppNavigation 的 detail composable 显式维护）。
 *
 * 为什么不用 SharedTransitionScope.isTransitionActive：那是整个布局级别的全局状态，
 * 任何卡片读它都会让全屏卡片一起跳；而且它在 snapshotFlow 里实测不可追踪。
 * 这里改用导航生命周期直接驱动，只有 key 匹配的那张卡片会收敛 3D，其余卡片完全不受影响。
 */
object SharedPosterTransition {
    val activeKey = mutableStateOf<String?>(null)
}

/**
 * 全局统一的标准海报组件 (可在首页、搜索、分类等所有页面复用)
 * 严格按照 0.7f 黄金竖版比例，与详情页 1:1 严格对齐，彻底消除飞行形变
 * 禁用 Coil crossfade 杜绝动画闪烁，结合硬件传感器 3D 视差
 *
 * 【3D 与共享元素的约定，别再改回去】
 * 详情页 A 层是纯海报（无 3D），首页卡片带 3D —— sharedBounds 转场起止必须在这两份
 * 绘制内容之间切换，只有匹配的那张卡片会经历，所以「只有点进去的那张抖」。
 * 单纯把 3D 放内层 / 外层只是把跳变挪到起始帧或收尾帧，无法消除。
 * 用户拍板：详情页保持静止不动，改由本卡片在转场期间收敛成纯海报（weight -> 0）。
 * 【实测修正 09:20】返回后再播放 200ms「恢复 3D」动画，会在画面已静止后凭空多出一次
 * 目标卡片放大+倾斜+流光，用户判定的「只有点进去的那张抖一下」正是它。
 * 因此取消定时恢复：返回后保持纯海报绝对静止，等用户真正倾斜手机时才自然跟上。
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
    val parallaxState = LocalParallaxAngles.current
    val isParallaxEnabled = LocalParallaxEnabled.current
    val density = LocalDensity.current.density
    val context = LocalContext.current

    // 详情页字色采样的预热通道：卡片图片加载完成后顺手算一次（72x108，走同一份缓存），
    // 用户点进详情页时首帧就是最终字色。否则采样结果会在共享转场中途返回，
    // 触发详情页整页重组（数十个 Text 重建段落），实测能卡住一整个组合帧。
    val configuration = LocalConfiguration.current
    val prefetchScope = rememberCoroutineScope()
    val toneBandFraction = remember(configuration.screenHeightDp) {
        detailHeroBandBottomFraction(configuration.screenHeightDp.toFloat())
    }
    val loadablePosterUrl = remember(posterUrl) { posterUrl.toLoadableImageUrl() }

    val posterKey = movieId?.trim()?.takeIf { it.isNotEmpty() }?.let { "poster_$it" }

    // 3D 权重：1 = 正常视差，0 = 纯海报（与详情页 A 层一致）。
    // 只有本次详情页对应的那张卡片会变化；其余卡片恒为 1，绘制结果逐帧不变。
    // 权重在 graphicsLayer / drawBehind 的绘制阶段读取，动画每帧只重绘、不重组。
    val parallaxWeight = remember { Animatable(1f) }

    LaunchedEffect(posterKey) {
        if (posterKey == null) return@LaunchedEffect
        var waitingForMotion = false
        var baseAngles: ParallaxAngles? = null

        // 导航状态：进详情立刻收敛为纯海报；返回后**不播放任何定时恢复动画**，
        // 只标记等待用户真正倾斜手机 —— 转场结束后画面必须完全静止。
        launch {
            snapshotFlow { SharedPosterTransition.activeKey.value == posterKey }
                .distinctUntilChanged()
                .collectLatest { isDetailTarget ->
                    if (isDetailTarget) {
                        // 转场第一帧 Overlay 就必须是纯海报，不能留插值过程
                        parallaxWeight.snapTo(0f)
                        waitingForMotion = false
                        baseAngles = null
                    } else {
                        waitingForMotion = true
                        baseAngles = null
                    }
                }
        }

        // 只有用户主动倾斜手机才让这张卡片的 3D 自然跟上；此时整排卡片本来就在动，
        // 不会出现「页面已静止却突然自己放大倾斜」的孤立跳变。
        // waitingForMotion 为 false 时不读取角度 State，避免给每张卡片都加一个 20ms 轮询。
        snapshotFlow { if (waitingForMotion) parallaxState.value else null }.collect { a ->
            if (a == null) return@collect
            val base = baseAngles
            if (base == null) {
                baseAngles = a
                return@collect
            }
            val delta = kotlin.math.abs(a.roll - base.roll) + kotlin.math.abs(a.pitch - base.pitch)
            if (delta > 0.05f) {
                waitingForMotion = false
                parallaxWeight.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            }
        }
    }

    // 仅当评分为有效正数时才渲染角标 (0.0 / 空值视为无评分)
    val hasScore = remember(score) { (score.toFloatOrNull() ?: 0f) > 0f }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 这里不裁剪：Column 的 clip 会连带裁掉海报 3D 旋转后超出卡片宽度的近边，
            // 让透视形变退化成中心缩放。圆角由海报 Box 自身的 clip 保证。
            .clickable { onClick() }
    ) {
        // 3D 陀螺仪视差：位于 sharedBounds 之后（内层），会随海报一起被提升进 Overlay。
        // 转场期间 weight=0，Overlay 重放的就是无 3D 纯海报，与详情页 A 层完全一致。
        val parallaxModifier = Modifier.graphicsLayer {
            if (isParallaxEnabled) {
                val w = parallaxWeight.value
                val p = parallaxState.value
                rotationY = p.roll * 14f * w
                rotationX = -p.pitch * 14f * w
                cameraDistance = 14f * density
                translationX = p.roll * 5f * density * w
                translationY = -p.pitch * 5f * density * w
                scaleX = 1f + 0.04f * w
                scaleY = 1f + 0.04f * w
            } else {
                // 显式归零，避免 graphicsLayer 作用域复用时残留上一帧变换
                rotationX = 0f
                rotationY = 0f
                translationX = 0f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
        }

        // 共享元素：key 与详情页 A 层完全一致，实现「从哪进从哪回」的容器展开/收缩。
        val sharedModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .let { m ->
                if (sharedTransitionScope != null && animatedVisibilityScope != null && !movieId.isNullOrEmpty()) {
                    with(sharedTransitionScope) {
                        m.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "poster_${movieId.trim()}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> tween(280, easing = FastOutSlowInEasing) },
                            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp))
                        )
                    }
                } else m
            }
            .then(parallaxModifier)

        // 海报图片容器：强制统一 aspectRatio(0.7f)
        Box(
            modifier = sharedModifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (posterUrl.isNotEmpty()) {
                AsyncImage(
                    model = rememberRetryableImageRequest(context, loadablePosterUrl),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        prefetchScope.launch {
                            prefetchPosterTone(context, loadablePosterUrl, toneBandFraction)
                        }
                    },
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
                                val w = parallaxWeight.value
                                val p = parallaxState.value
                                translationX = p.roll * 6.dp.toPx() * w
                                translationY = p.pitch * 6.dp.toPx() * w
                            } else {
                                translationX = 0f
                                translationY = 0f
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

            // 陀螺仪 3D 动态流光反射层。节点始终存在（转场期间不增删组合树节点），
            // 只在绘制阶段按权重淡出，避免整屏卡片因结构变化在同一帧一起闪动。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (!isParallaxEnabled) return@drawBehind
                        val w = parallaxWeight.value
                        if (w <= 0.001f) return@drawBehind
                        val p = parallaxState.value
                        val roll = p.roll * w
                        val pitch = p.pitch * w
                        val highlightX = size.width * (0.5f - roll * 0.6f)
                        val highlightY = size.height * (0.5f - pitch * 0.6f)
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.35f * w),
                                    Color.Transparent
                                ),
                                center = Offset(highlightX, highlightY),
                                radius = size.width * 0.95f
                            )
                        )
                    }
            )
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

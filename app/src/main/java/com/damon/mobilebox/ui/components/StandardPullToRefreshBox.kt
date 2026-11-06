package com.damon.mobilebox.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 带物理阻尼推移的全局下拉刷新容器。
 *
 * 说明：Material 3 官方 PullToRefreshBox 只会弹出一个指示器，内容区不会跟手下移。
 * 这里自研 nestedScroll 链接：列表滚动到顶部后，把剩余的下拉位移按阻尼累积到
 * 内容层的 translationY 上，松手用弹簧回弹；超过阈值触发刷新。
 * 不依赖 M3 pulltorefresh 内部 API，因此不受 Material3 版本升级影响。
 */
@Composable
fun StandardPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 96.dp.toPx() } }
    val maxPullPx = remember(density) { with(density) { 180.dp.toPx() } }
    val indicatorOffsetPx = remember(density) { with(density) { 56.dp.toPx() } }

    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var pullJob by remember { mutableStateOf<Job?>(null) }
    var locked by remember { mutableStateOf(false) }

    val settleSpec = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )

    // 外部刷新状态驱动，需区分两种来源：
    //  · 手势下拉触发 (offset > 0)：内容停在指示器高度，指示器持续可见；
    //  · 程序化刷新 (首屏加载 / 订阅源切换，offset == 0)：内容不下移、不显示指示器，
    //    由页面自身的加载态负责反馈，避免顶部小圈 + 页面中央大圈同时出现。
    // isRefreshing 结束时必须无条件解锁：旧写法只在 offset > 0 的分支里解锁，
    // 首屏加载结束后 locked 会永远卡在 true，之后再也无法下拉。
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            pullJob?.cancel()
            if (offset.value > 0f) {
                locked = true
                offset.animateTo(indicatorOffsetPx, settleSpec)
            }
        } else {
            if (offset.value > 0f) offset.animateTo(0f, settleSpec)
            locked = false
        }
    }

    // key 必须包含捕获的可变状态，否则 connection 会一直持有首次组合的旧值
    val connection = remember(isRefreshing, locked, onRefresh, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (locked || isRefreshing || source != NestedScrollSource.UserInput || available.y <= 0f) {
                    return Offset.Zero
                }
                val current = offset.value
                // 越往下拉阻力越大，形成物理阻尼手感
                val damping = 1f / (1f + current / (thresholdPx * 2f))
                val next = (current + available.y * damping * 0.65f).coerceIn(0f, maxPullPx)
                pullJob?.cancel()
                pullJob = scope.launch { offset.snapTo(next) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value <= 0f) return Velocity.Zero
                locked = true
                pullJob?.cancel()
                if (!isRefreshing) {
                    if (offset.value >= thresholdPx) {
                        onRefresh() // 交给 LaunchedEffect(isRefreshing) 接管后续位移
                    } else {
                        offset.animateTo(0f, settleSpec)
                        locked = false
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        // 内容层：唯一跟随手指下移的部分
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = offset.value }
        ) {
            content()
        }

        // 指示器层：固定在顶部，只随进度做轻微位移与缩放，不跟内容一起平移。
        // 仅在存在真实手势位移时出现；程序化刷新（isRefreshing 但 offset == 0）不渲染。
        val progress = (offset.value / thresholdPx).coerceIn(0f, 1f)
        if (offset.value > 1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = offset.value * 0.32f
                        alpha = if (isRefreshing) 1f else progress.coerceAtLeast(0.3f)
                        val scale = 0.78f + 0.22f * progress.coerceAtLeast(if (isRefreshing) 1f else 0f)
                        scaleX = scale
                        scaleY = scale
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

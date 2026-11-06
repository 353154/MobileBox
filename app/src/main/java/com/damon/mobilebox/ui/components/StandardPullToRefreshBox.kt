package com.damon.mobilebox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 映射当前 Compose Material 3 (1.2.1) 版本下的垂直位移值
 */
@OptIn(ExperimentalMaterial3Api::class)
private val PullToRefreshState.position: Float
    get() = verticalOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()

    // 1. 监听手势触发下拉
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            onRefresh()
        }
    }

    // 2. 严格同步外部刷新状态，防止内部状态死锁
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            if (!pullToRefreshState.isRefreshing) {
                pullToRefreshState.startRefresh()
            }
        } else {
            if (pullToRefreshState.isRefreshing) {
                pullToRefreshState.endRefresh()
            }
        }
    }

    Box(
        modifier = modifier.nestedScroll(pullToRefreshState.nestedScrollConnection)
    ) {
        // 内容推移容器
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = pullToRefreshState.position
            }
        ) {
            content()
        }

        // 3. 核心修复：带有强制渐隐逻辑的刷新指示器
        PullToRefreshContainer(
            state = pullToRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    // 当刷新结束，页面开始向上回弹时，强制将其透明度和尺寸缩小至 0
                    // 彻底解决小圆圈卡在顶部的残留 Bug
                    if (!pullToRefreshState.isRefreshing) {
                        // 使用 50f 作为阈值，position 越小，越接近透明
                        val fraction = (pullToRefreshState.position / 50f).coerceIn(0f, 1f)
                        alpha = fraction
                        scaleX = fraction
                        scaleY = fraction
                    }
                },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

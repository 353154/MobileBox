package com.damon.mobilebox.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 横向滚动容器的双向边缘渐隐 (Fading Edge)。
 *
 * 设计语义：渐隐只表示「这个方向还有内容」，因此必须随滚动位置动态显隐——
 *   - 位于最左端：只显示右侧渐隐；
 *   - 位于中间：左右同时显示；
 *   - 位于最右端：只显示左侧渐隐（右侧必须淡出，否则会误导用户以为还能滑）。
 *
 * 实现要点：使用 drawWithContent 在内容之上直接绘制渐变，不新增布局节点、
 * 不拦截触摸事件，也不像常驻遮罩 Box 那样在端点处反向误导。
 */
@Composable
fun Modifier.horizontalScrollFadingEdge(
    state: LazyListState,
    edgeWidth: Dp = 32.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.background
): Modifier {
    val canScrollBackward by remember(state) {
        derivedStateOf {
            state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
        }
    }

    val canScrollForward by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            when {
                lastVisible == null -> false
                lastVisible.index < info.totalItemsCount - 1 -> true
                else -> lastVisible.offset + lastVisible.size > info.viewportEndOffset
            }
        }
    }

    val startAlpha by animateFloatAsState(
        targetValue = if (canScrollBackward) 1f else 0f,
        animationSpec = tween(160),
        label = "fadingEdgeStart"
    )
    val endAlpha by animateFloatAsState(
        targetValue = if (canScrollForward) 1f else 0f,
        animationSpec = tween(160),
        label = "fadingEdgeEnd"
    )

    val transparentStart = backgroundColor.copy(alpha = 0f)
    val softStart = backgroundColor.copy(alpha = 0.72f)

    return this.drawWithContent {
        drawContent()
        val width = edgeWidth.toPx()
        val height = size.height

        if (startAlpha > 0.01f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to backgroundColor,
                    0.45f to softStart,
                    1f to transparentStart,
                    startX = 0f,
                    endX = width
                ),
                topLeft = Offset(0f, 0f),
                size = Size(width, height),
                alpha = startAlpha
            )
        }

        if (endAlpha > 0.01f) {
            val left = size.width - width
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to transparentStart,
                    0.55f to softStart,
                    1f to backgroundColor,
                    startX = left,
                    endX = size.width
                ),
                topLeft = Offset(left, 0f),
                size = Size(width, height),
                alpha = endAlpha
            )
        }
    }
}

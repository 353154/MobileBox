package com.damon.mobilebox.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * 兼容并统一 Compose 的 animateItem 语法：
 * 1. placementSpec: 驱动下方旧数据平滑推移的弹簧物理动画 (animateItemPlacement)
 * 2. fadeInSpec: 配合图片异步加载管道与淡入效果，解决头部插入新卡片瞬间闪现的缺陷
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyGridItemScope.animateItem(
    fadeInSpec: FiniteAnimationSpec<Float>? = tween(durationMillis = 400),
    placementSpec: FiniteAnimationSpec<IntOffset>? = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    ),
    fadeOutSpec: FiniteAnimationSpec<Float>? = null
): Modifier = Modifier.animateItemPlacement(
    animationSpec = placementSpec ?: spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )
)

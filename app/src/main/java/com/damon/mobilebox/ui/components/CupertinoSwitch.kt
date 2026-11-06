package com.damon.mobilebox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * iOS 风格仿生开关 (CupertinoSwitch)
 * 51dp x 31dp 标准尺寸，纯白圆形滑块，开启时跟随全局主题色（亮蓝），去水波纹涟漪
 */
@Composable
fun CupertinoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    // 核心修改：将默认的亮绿色替换为全局主题主色调
    checkedTrackColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedTrackColor: Color = Color(0xFFE9E9EA),
    uncheckedTrackColorDark: Color = Color(0xFF39393D),
    thumbColor: Color = Color.White
) {
    val isSystemDark = isSystemInDarkTheme()
    val actualUncheckedTrackColor = if (isSystemDark) uncheckedTrackColorDark else uncheckedTrackColor
    
    // 轨迹颜色动画
    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor else actualUncheckedTrackColor,
        animationSpec = tween(durationMillis = 250),
        label = "trackColor"
    )

    // 滑块位移动画 (51 - 27 - 2 = 22dp，在 2dp 与 21dp 之间滑动)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 2.dp,
        animationSpec = tween(durationMillis = 250),
        label = "thumbOffset"
    )

    // 交互去涟漪
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .width(51.dp)
            .height(31.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // 去除安卓原生的水波纹效果
                onClick = { onCheckedChange?.invoke(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // 圆形纯白滑块
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(27.dp)
                .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

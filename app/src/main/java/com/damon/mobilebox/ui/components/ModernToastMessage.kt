package com.damon.mobilebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 现代化的全局通用极简提示弹窗 (Toast/Snackbar)
 * 已自动适配深浅色模式 (Inverse Colors)
 */
@Composable
fun ModernToastMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            // 为底部的 BottomNavigationBar 和系统导航条留出安全距离
            .padding(bottom = 24.dp, start = 32.dp, end = 32.dp)
            // MD3 弹窗标准全圆角 (药丸形状)
            .clip(RoundedCornerShape(50))
            // 核心修复：使用 InverseSurface 自动适配深浅模式，不刺眼
            .background(MaterialTheme.colorScheme.inverseSurface)
            // 优化呼吸感：加大水平 Padding，移除图标后的最佳排版
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            // 核心修复：文本使用 InverseOnSurface，确保对比度始终清晰
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1 // 强制单行显示，保持极简
        )
    }
}

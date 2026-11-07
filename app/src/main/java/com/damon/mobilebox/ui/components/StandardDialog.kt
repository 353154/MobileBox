package com.damon.mobilebox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全应用弹窗的统一外观基准。
 *
 * 容器色取 MaterialTheme.colorScheme.surface 并叠加固定 tonalElevation：浅色 / 深色、
 * 跟随壁纸的动态取色、自定义主题色都会一起生效。历史问题是 AlertDialog 用纯 surface
 * （看起来「没取色」），而自定义 Dialog 用 surface + elevation（带主题色调），两者并排
 * 明显不是同一套；圆角也有 28 / 24 / 20 dp 三种。现在所有弹窗共用这里的值。
 */
object AppDialogDefaults {
    val Shape = RoundedCornerShape(20.dp)
    val TonalElevation = 6.dp
    val ContentPadding = 20.dp
    val MaxHeight = 620.dp
}

/** 只复用统一容器外观，内部结构（例如三段式 + LazyColumn）由调用方自己控制。 */
@Composable
fun StandardDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = AppDialogDefaults.Shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = AppDialogDefaults.TonalElevation,
        modifier = modifier,
        content = content
    )
}

/**
 * 常规弹窗：统一容器 + 统一 20dp 内边距 + 12dp 元素间距。
 * 内容多时内部可滚，键盘弹起也不会被裁。
 */
@Composable
fun StandardDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = AppDialogDefaults.MaxHeight,
    scrollable: Boolean = true,
    contentPadding: Dp = AppDialogDefaults.ContentPadding,
    usePlatformDefaultWidth: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth)
    ) {
        StandardDialogSurface(
            modifier = modifier
                .fillMaxWidth()
                .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
        ) {
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

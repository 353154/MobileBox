package com.damon.mobilebox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 统一线宽的底部导航图标族 (NavGlyph)
 *
 * 背景：此前直接混用 Material `Icons.Outlined.*`，该图标族由不同年代的矢量拼装而成，
 * 光学重量并不一致（分类的四宫格实心块明显重于首页的细线轮廓，圆形头像又偏轻）。
 * 换库无法解决，因为不一致来自图标本身而非渲染参数。
 *
 * 方案：全部图标在同一套 24x24 网格上以**同一个 strokeWidth (默认 1.8dp)** 自绘，
 * 圆头圆角（StrokeCap.Round / StrokeJoin.Round），保证五枚图标的线宽在像素级完全相等；
 * 选中态另提供几何等价的 Filled 实心版本，由底部导航栏做 180ms 交叉淡入切换。
 */
enum class NavGlyph { Home, Category, Live, Settings, Profile }

private const val GRID = 24f

private fun DrawScope.px(value: Float) = value * (size.minDimension / GRID)

private fun DrawScope.pt(x: Float, y: Float) = Offset(px(x), px(y))

@Composable
fun NavGlyphIcon(
    type: NavGlyph,
    filled: Boolean,
    tint: Color,
    knockout: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 23.dp,
    strokeWidth: Dp = 1.8.dp
) {
    Canvas(modifier = modifier.size(glyphSize)) {
        val stroke = strokeWidth.toPx()
        when (type) {
            NavGlyph.Home -> drawHomeGlyph(tint, filled, stroke)
            NavGlyph.Category -> drawCategoryGlyph(tint, filled, stroke)
            NavGlyph.Live -> drawLiveGlyph(tint, filled, knockout, stroke)
            NavGlyph.Settings -> drawSettingsGlyph(tint, filled, knockout, stroke)
            NavGlyph.Profile -> drawProfileGlyph(tint, filled, stroke)
        }
    }
}

// ---------------------------------------------------------------------------
// 首页：屋顶折线 + U 形屋身
// ---------------------------------------------------------------------------
private fun DrawScope.drawHomeGlyph(tint: Color, filled: Boolean, stroke: Float) {
    if (filled) {
        // 关键：底部留出门洞缺口，否则无门的五边形在小尺寸下会被读成“上箭头/水滴”
        val body = Path().apply {
            moveTo(px(12f), px(3.4f))
            lineTo(px(2.6f), px(12.0f))
            lineTo(px(5.7f), px(12.0f))
            lineTo(px(5.7f), px(20.2f))
            lineTo(px(11.0f), px(20.2f))
            lineTo(px(11.0f), px(14.4f))
            lineTo(px(13.0f), px(14.4f))
            lineTo(px(13.0f), px(20.2f))
            lineTo(px(18.3f), px(20.2f))
            lineTo(px(18.3f), px(12.0f))
            lineTo(px(21.4f), px(12.0f))
            close()
        }
        drawPath(body, tint, style = Fill)
    } else {
        val roof = Path().apply {
            moveTo(px(3.4f), px(10.3f))
            lineTo(px(12f), px(3.3f))
            lineTo(px(20.6f), px(10.3f))
        }
        drawPath(roof, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val body = Path().apply {
            moveTo(px(5.5f), px(9.4f))
            lineTo(px(5.5f), px(18.4f))
            quadraticTo(px(5.5f), px(20.3f), px(7.4f), px(20.3f))
            lineTo(px(16.6f), px(20.3f))
            quadraticTo(px(18.5f), px(20.3f), px(18.5f), px(18.4f))
            lineTo(px(18.5f), px(9.4f))
        }
        drawPath(body, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------
// 分类：2x2 等距圆角方格 (线框 / 实心)
// ---------------------------------------------------------------------------
private fun DrawScope.drawCategoryGlyph(tint: Color, filled: Boolean, stroke: Float) {
    val side = 6.9f
    val radius = 2.0f
    val corners = listOf(
        3.4f to 3.4f,
        13.7f to 3.4f,
        3.4f to 13.7f,
        13.7f to 13.7f
    )
    corners.forEach { (x, y) ->
        if (filled) {
            drawRoundRect(
                color = tint,
                topLeft = pt(x, y),
                size = Size(px(side), px(side)),
                cornerRadius = CornerRadius(px(radius), px(radius)),
                style = Fill
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = pt(x, y),
                size = Size(px(side), px(side)),
                cornerRadius = CornerRadius(px(radius), px(radius)),
                style = Stroke(stroke)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 直播：电视机身 + 顶部天线 + 内嵌播放三角
// ---------------------------------------------------------------------------
private fun DrawScope.drawLiveGlyph(tint: Color, filled: Boolean, knockout: Color, stroke: Float) {
    val antenna = Path().apply {
        moveTo(px(8.2f), px(3.4f))
        lineTo(px(10.6f), px(6.6f))
        moveTo(px(15.8f), px(3.4f))
        lineTo(px(13.4f), px(6.6f))
    }
    drawPath(antenna, tint, style = Stroke(stroke, cap = StrokeCap.Round))

    val body = Path().apply {
        addRoundRect(
            RoundRect(
                left = px(2.4f),
                top = px(6.6f),
                right = px(21.6f),
                bottom = px(19.9f),
                cornerRadius = CornerRadius(px(2.6f), px(2.6f))
            )
        )
    }
    val play = Path().apply {
        moveTo(px(10.2f), px(9.9f))
        lineTo(px(16.2f), px(13.25f))
        lineTo(px(10.2f), px(16.6f))
        close()
    }

    if (filled) {
        drawPath(body, tint, style = Fill)
        drawPath(play, knockout, style = Fill)
    } else {
        drawPath(body, tint, style = Stroke(stroke, join = StrokeJoin.Round))
        drawPath(play, tint, style = Fill)
    }
}

// ---------------------------------------------------------------------------
// 设置：6 齿粗齿轮
// 空心/实心齿轮是天然的「轮廓 vs 整块面积」对比，选中态比三轨推子直观得多，
// 也贴合「设置=齿轮」的用户心智。之前齿轮在 24dp 下糊成一团是因为用了 8 细齿 + 小中心孔，
// 这里改成 6 个粗齿 + 大中心孔，小尺寸下依然干净。
// ---------------------------------------------------------------------------
private fun DrawScope.drawSettingsGlyph(tint: Color, filled: Boolean, knockout: Color, stroke: Float) {
    val center = pt(12f, 12f)
    val toothW = px(4.8f)
    val toothH = px(3.6f)
    val toothTopLeft = Offset(px(12f) - toothW / 2f, px(1.4f))
    val toothCorner = CornerRadius(px(1.1f), px(1.1f))
    val bodyRadius = px(8.2f)
    val holeRadius = px(3.3f)
    val toothStyle = if (filled) Fill else Stroke(stroke)

    // 1) 齿轮主体圆
    drawCircle(tint, radius = bodyRadius, center = center, style = if (filled) Fill else Stroke(stroke))

    // 2) 六个粗齿绕中心旋转复制。空心态先用底色盖住主体圆穿过齿的线，
    //    让齿压在圆上，才是干净的齿轮轮廓。
    for (i in 0 until 6) {
        withTransform({ rotate(i * 60f, pivot = center) }) {
            if (!filled) {
                drawRoundRect(
                    color = knockout,
                    topLeft = toothTopLeft,
                    size = Size(toothW, toothH),
                    cornerRadius = toothCorner,
                    style = Fill
                )
            }
            drawRoundRect(
                color = tint,
                topLeft = toothTopLeft,
                size = Size(toothW, toothH),
                cornerRadius = toothCorner,
                style = toothStyle
            )
        }
    }

    // 3) 中心：实心态挖空成大孔，空心态描成细圆环
    if (filled) {
        drawCircle(knockout, radius = holeRadius, center = center, style = Fill)
    } else {
        drawCircle(tint, radius = holeRadius, center = center, style = Stroke(stroke))
    }
}

// ---------------------------------------------------------------------------
// 我的：头部圆 + 肩部弧线
// ---------------------------------------------------------------------------
private fun DrawScope.drawProfileGlyph(tint: Color, filled: Boolean, stroke: Float) {
    val headCenter = Offset(px(12f), px(8.1f))
    val headRadius = 4.15f

    if (filled) {
        drawCircle(tint, radius = px(headRadius), center = headCenter, style = Fill)
        val shoulders = Path().apply {
            moveTo(px(4.2f), px(20.4f))
            cubicTo(px(4.2f), px(16.1f), px(7.7f), px(13.9f), px(12f), px(13.9f))
            cubicTo(px(16.3f), px(13.9f), px(19.8f), px(16.1f), px(19.8f), px(20.4f))
            lineTo(px(4.6f), px(20.3f))
            close()
        }
        drawPath(shoulders, tint, style = Fill)
    } else {
        drawCircle(tint, radius = px(headRadius), center = headCenter, style = Stroke(stroke))
        val shoulders = Path().apply {
            moveTo(px(4.3f), px(20.4f))
            cubicTo(px(4.3f), px(16.2f), px(7.8f), px(14.0f), px(12f), px(14.0f))
            cubicTo(px(16.2f), px(14.0f), px(19.7f), px(16.2f), px(19.7f), px(20.4f))
        }
        drawPath(shoulders, tint, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

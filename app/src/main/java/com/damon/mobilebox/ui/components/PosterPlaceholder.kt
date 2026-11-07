package com.damon.mobilebox.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 全局关掉 shimmer 的总开关。关掉后仍是「对角微渐变」的静态骨架，只是不再扫光。
 * 低配机或用户觉得一直闪像加载不完时，改这一行即可。
 */
private const val SkeletonShimmerEnabled = true

/**
 * 骨架色板（2026-11-08 定案）。
 *
 * 全部是极低饱和度的 Tonal Color，没有任何品牌高饱和色：
 * - 浅色：#F2F2F7（左上受光）→ #E5E6EA（右下背光），带极淡蓝调
 * - 深色：#2C2C2E → #1C1C1E，与项目 #0D1117 底色自然衔接
 * - 光泽只是一层薄白，浅色 12% / 深色 5%，不产生「高亮色块」
 */
private object SkeletonPalette {
    val LightTop = Color(0xFFF2F2F7)
    val LightBottom = Color(0xFFE5E6EA)
    val DarkTop = Color(0xFF2C2C2E)
    val DarkBottom = Color(0xFF1C1C1E)
    val LightSheen = Color.White.copy(alpha = 0.12f)
    val DarkSheen = Color.White.copy(alpha = 0.05f)

    /** 扫光周期。够慢才像「微呼吸」，快了就是加载条。 */
    const val PERIOD_MS = 2600
}

/**
 * 无海报 / 加载中 / 死链时的骨架屏。
 *
 * 三层质感，全部在 draw 阶段完成，不引入任何图片或图形：
 * 1. 对角线微渐变（左上 → 右下），色差极小，只制造光照角度；
 * 2. 一条窄的斜向光泽从左向右缓缓扫过，周期 2600ms；
 * 3. `seed` 决定每张卡的**相位偏移** —— 同一排卡不会一起闪成跑马灯，
 *    同一部片在任何时候相位固定，滚动、重组、切页都不会跳变。
 *
 * 性能：`progress` 只在 `onDrawBehind` 里读取（draw-phase read），
 * 每帧只让这张卡重绘自己那一小块，不触发重组。
 */
@Composable
fun PosterPlaceholder(
    seed: String?,
    modifier: Modifier = Modifier,
    /**
     * 可选的极淡文字水印。只有「台标骨架」会传：直播源里大量频道没有 tvg-logo，
     * 一整排纯渐变块看起来像没加载完，叠一层频道名就能区分。不是图形、不改变色板，
     * shimmer 和微渐变都在。真台标加载成功后由 ChannelLogo 把它置空。
     */
    label: String? = null,
    shimmer: Boolean = SkeletonShimmerEnabled
) {
    // 用背景亮度判断明暗，而不是 isSystemInDarkTheme()：
    // 用户在设置里手动切主题、用动态取色或自定义色时，这个判断始终跟当前色板一致。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val top = if (dark) SkeletonPalette.DarkTop else SkeletonPalette.LightTop
    val bottom = if (dark) SkeletonPalette.DarkBottom else SkeletonPalette.LightBottom
    val sheen = if (dark) SkeletonPalette.DarkSheen else SkeletonPalette.LightSheen

    val phase = remember(seed) {
        val h = (seed ?: "").hashCode() and 0x7fffffff
        (h % 1000) / 1000f
    }
    val transition = rememberInfiniteTransition(label = "posterShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SkeletonPalette.PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "posterShimmerProgress"
    )

    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val base = Brush.linearGradient(
                colors = listOf(top, bottom),
                start = Offset.Zero,
                end = Offset(w, h)
            )
            val band = w * 0.65f
            onDrawBehind {
                drawRect(base)
                if (!shimmer) return@onDrawBehind
                val p = (progress + phase) % 1f
                val x = -band + p * (w + band * 2f)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, sheen, Color.Transparent),
                        start = Offset(x - band * 0.5f, 0f),
                        end = Offset(x + band * 0.5f, h)
                    )
                )
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = if (dark) Color.White.copy(alpha = 0.20f)
                else Color(0xFF1F2328).copy(alpha = 0.20f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

/**
 * 「有海报就显示海报，没有 / 挂了就显示骨架」的统一海报图。
 *
 * 失败时不清空内容：`onError` 只把 [failed] 置真，AsyncImage 自己画不出东西，
 * 底下那层骨架就露出来了 —— 不需要等 Coil 回调再做一次重组切换。
 */
@Composable
fun PosterImage(
    url: String?,
    title: String?,
    modifier: Modifier = Modifier,
    seed: String? = url ?: title,
    contentScale: ContentScale = ContentScale.Crop
) {
    var failed by remember(url) { mutableStateOf(false) }
    val loadable = remember(url) {
        url?.toLoadableImageUrl()?.takeIf { it.isNotBlank() }
    }

    // 骨架垫底：真图加载中 / 空地址 / 死链，都不会出现灰板
    PosterPlaceholder(seed = seed, modifier = modifier)
    if (loadable == null || failed) return

    AsyncImage(
        model = loadable,
        contentDescription = title,
        contentScale = contentScale,
        onError = { failed = true },
        modifier = modifier
    )
}

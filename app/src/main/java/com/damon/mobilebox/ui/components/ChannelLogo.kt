package com.damon.mobilebox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 台标骨架上是否叠一层极淡的频道名水印。
 *
 * 这个源的绝大多数频道都没有 tvg-logo，纯渐变会让整屏变成一排一模一样的灰白块，
 * 看着像「全部加载失败」。叠 20% alpha 的频道名就能区分，且不引入图形。
 * 不想要纯渐变的话把这个开关翻成 false。
 */
private const val ShowChannelWatermark = true

/**
 * 电视频道台标（2026-11-08）。
 *
 * 直播源里的 `tvg-logo` 经常是空的或早已失效。这里不再用固定图片，而是和全 App
 * 的海报共用同一套 [PosterPlaceholder]：低饱和对角微渐变 + 极度柔和的扫光。
 * 纯色块容易显得像「死机 / 加载失败」，微渐变和光泽能说明这里是一个内容模块。
 *
 * 骨架垫底、真台标叠在上面：加载中的空窗期和加载失败的一瞬间都不会露出灰底，
 * 也不需要靠 onError 再触发一次重组来切换。相位用频道名做种子，每路台的扫光不同步。
 */
@Composable
fun ChannelLogo(
    url: String?,
    name: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    val loadable = remember(url) { url?.trim()?.takeIf { it.isNotBlank() } }

    // 骨架垫底 + 水印叠在骨架上；真台标（可能是透明 PNG）由 AsyncImage 盖在最上层。
    // 加载成功后把水印去掉：透明台标周围的骨架渐变还要留着，但文字不能从透明处透出来。
    PosterPlaceholder(
        seed = name,
        label = if (ShowChannelWatermark && !loaded) name else null,
        modifier = modifier
    )
    if (loadable == null || failed) return

    AsyncImage(
        model = loadable,
        contentDescription = name,
        contentScale = contentScale,
        onSuccess = { loaded = true },
        onError = { failed = true },
        modifier = modifier
    )
}

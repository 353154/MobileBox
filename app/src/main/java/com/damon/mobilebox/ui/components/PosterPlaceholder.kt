package com.damon.mobilebox.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.damon.mobilebox.R

/**
 * 海报占位图（2026-11-08）。
 *
 * 采集源的海报字段经常是空的，或者给个死链（`vod_pic` 为空 / 404），
 * 卡片就只剩一块灰底 —— 一整行看过去像没加载完。这里用两张电影主题的图顶上，
 * 按条目 id 散列挑一张：**同一部片永远同一张**，滚动、重组都不会跳来跳去。
 */
object PosterPlaceholderArt {
    @DrawableRes
    private val ARTS = intArrayOf(
        R.drawable.poster_placeholder_a, // 场记板 + 胶片
        R.drawable.poster_placeholder_b  // 放映机
    )

    /** 用 `id` 的散列做种子，不用 Random：避免每次重组换成另一张。 */
    @DrawableRes
    fun pick(seed: String?): Int {
        val h = (seed ?: "").hashCode() and 0x7fffffff
        return ARTS[h % ARTS.size]
    }
}

/** 纯占位图。调用方负责把它铺满 / 裁剪到海报位。 */
@Composable
fun PosterPlaceholder(
    seed: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Image(
        painter = painterResource(PosterPlaceholderArt.pick(seed)),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * 「有海报就显示海报，没有 / 挂了就显示占位图」的统一海报图。
 *
 * 失败时不清空内容：`onError` 只把 [failed] 置真，AsyncImage 自己画不出东西，
 * 底下那层占位图就露出来了 —— 不需要等 Coil 回调再做一次重组切换。
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

    if (loadable == null || failed) {
        PosterPlaceholder(seed = seed, modifier = modifier, contentScale = contentScale)
        return
    }
    // 占位图垫底：真图加载中 / 失败时都不会出现灰板
    PosterPlaceholder(seed = seed, modifier = modifier, contentScale = contentScale)
    AsyncImage(
        model = loadable,
        contentDescription = title,
        contentScale = contentScale,
        onError = { failed = true },
        modifier = modifier
    )
}

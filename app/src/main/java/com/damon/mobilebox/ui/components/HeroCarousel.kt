package com.damon.mobilebox.ui.components

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tmdb.TmdbMeta
import kotlin.math.abs

/**
 * 首页底色。
 *
 * **这个值必须同时用于**：A 层海报的底部渐变终点、B 层所在页面的背景。
 * 两处只要差一点，海报下缘就会切出一条水平硬线（用户 22:01 截图里的「断层」）。
 */
val HomeBaseColor = Color(0xFF0B0E13)

// B 层字色终点：浅色主题整屏会变白，字要变近黑；深色主题整屏压暗，字要变近白。
// 与详情页取同一组值，两页滚上去的观感才是一套东西。
private val InkPrimary = Color(0xFF111418)
private val InkSecondary = Color(0xFF5A616B)
private val InkPrimaryDark = Color(0xFFEDF1F7)
private val InkSecondaryDark = Color(0xFFA9B2C0)

/** 首页 B 层一组动态字色。 */
data class HomeTextColors(val primary: Color, val secondary: Color)

/**
 * 按遮罩进度插值出 B 层字色。
 *
 * 起手是压在海报 / 模糊图上的白字，随整屏遮罩一起过渡到主题字色 ——
 * 少了这一步，滚到白底上就是「白底白字」。
 */
fun homeTextColors(progress: Float, lightSurface: Boolean): HomeTextColors {
    val p = progress.coerceIn(0f, 1f)
    return HomeTextColors(
        primary = lerp(Color.White, if (lightSurface) InkPrimary else InkPrimaryDark, p),
        secondary = lerp(
            Color.White.copy(alpha = 0.88f),
            if (lightSurface) InkSecondary else InkSecondaryDark,
            p
        )
    )
}

/**
 * A 层之一：首页大海报。**固定不动**，不随滚动做 Y 位移、Alpha 恒为 1、也不淡出。
 *
 * 与详情页 A 层一致：滚动时只做 1.0 → 1.06 的轻微放大（Crop 裁边，绝不露底色）。
 * 图片本身不含任何文字，标题 / 信息行 / 简介 / 圆点属于 B 层，由调用方压在它上面。
 *
 * `userScrollEnabled = false`：B 层是全屏滚动覆盖层，会吃掉指针事件，这里的 Pager
 * 永远收不到手势；翻页由 B 层首块上的横向拖拽代理驱动（见 TmdbHomeContent）。
 *
 * @param zoom 0..1 的滚动进度，供放大动画使用。**通过 lambda 传入而不是 Float**：
 *   直接传 Float 会让调用方在组合阶段读滚动位置，每滚一帧整页重组一次。
 */
@Composable
fun HeroPosterPager(
    entries: List<TmdbMeta>,
    pagerState: PagerState,
    height: Dp,
    zoom: () -> Float = { 0f }
) {
    if (entries.isEmpty()) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(HomeBaseColor)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f + 0.06f * zoom().coerceIn(0f, 1f)
                    scaleX = scale
                    scaleY = scale
                }
        ) { page ->
            val entry = entries[page % entries.size]
            AsyncImage(
                model = entry.backdropUrl.ifBlank { entry.posterUrl },
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0xFF1B2028)),
                error = ColorPainter(Color(0xFF1B2028)),
                modifier = Modifier.fillMaxSize()
            )
        }

        // 顶部淡渐变：状态栏时间为白色，碰上浅色海报会糊成一片。
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent)
                    )
                )
        )

        // 底部厚渐变：文案可读性靠它。最后一档用 [HomeBaseColor]（不透明），
        // 保证模糊底图没加载出来时海报下缘也不会露出底色差。
        Box(
            Modifier
                .fillMaxWidth()
                .height(height * 0.74f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.34f),
                        0.66f to Color.Black.copy(alpha = 0.78f),
                        0.88f to Color.Black.copy(alpha = 0.93f),
                        1.00f to HomeBaseColor
                    )
                )
        )
    }
}

/**
 * A 层之二：**海报的模糊延续** —— 用户要的「标题和介绍下方不是纯黑，是海报的高斯模糊」。
 *
 * 两个关键点：
 * 1. 这一层画在**海报之上**，用竖向 alpha 遮罩显形。因为画的是**同一张图、同一个位置**，
 *    衔接处是「清晰 → 模糊」的连续过渡，而不是「图 → 另一张图 / 一块纯色」。
 * 2. **跟着 Pager 一起滑**：当前页 + 左右邻页三张叠着，各自按 `currentPageOffsetFraction`
 *    平移整屏宽。原来只画 `settledPage` 那一张，翻页时模糊图要等停稳才换，
 *    观感是「海报滑走了、背景没动」。
 *
 * 遮罩分两段：`fadeFrom → fadeTo` 把清晰图交给模糊图；`decayFrom → decayTo`
 * 再衰减到 [decayAlpha]，让「正在热映」那一带的模糊变淡（用户要求更通透）。
 */
@Composable
fun HeroBlurBackdrop(
    entries: List<TmdbMeta>,
    pagerState: PagerState,
    fadeFromPx: Float,
    fadeToPx: Float,
    decayFromPx: Float,
    decayToPx: Float,
    modifier: Modifier = Modifier,
    decayAlpha: Float = 0.42f
) {
    if (entries.isEmpty()) return
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val count = entries.size
    val current = pagerState.currentPage
    // 静止时只画一张：邻页整块在屏幕外，白渲染等于白做两次高斯模糊。
    val sliding by remember(pagerState) {
        derivedStateOf { abs(pagerState.currentPageOffsetFraction) > 0.002f }
    }
    // 平移量用整屏宽 = Pager 页宽（与 HeroHeadline 同一口径，别用容器宽）。
    val pageWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    Box(
        modifier
            // DstIn 遮罩必须画在独立图层里，否则会连下面的海报一起抠掉。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height.coerceAtLeast(1f)
                val from = (fadeFromPx / h).coerceIn(0f, 1f)
                val to = (fadeToPx / h).coerceIn(from, 1f)
                val decayFrom = (decayFromPx / h).coerceIn(to, 1f)
                val decayTo = (decayToPx / h).coerceIn(decayFrom, 1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        from to Color.Transparent,
                        to to Color.Black,
                        decayFrom to Color.Black,
                        decayTo to Color.Black.copy(alpha = decayAlpha),
                        1f to Color.Black.copy(alpha = decayAlpha * 0.88f)
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        HeroBlurImage(
            entry = entries[Math.floorMod(current, count)],
            canBlur = canBlur,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -pagerState.currentPageOffsetFraction * pageWidthPx
                }
        )
        if (sliding && count > 1) {
            val direction = if (pagerState.currentPageOffsetFraction >= 0f) 1 else -1
            HeroBlurImage(
                entry = entries[Math.floorMod(current + direction, count)],
                canBlur = canBlur,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX =
                            (-pagerState.currentPageOffsetFraction + direction) * pageWidthPx
                    }
            )
        }
    }
}

/**
 * 模糊层里的一张图。
 *
 * API 31 以下没有 RenderEffect（`blur` 是 no-op），退化成压暗的清晰图 ——
 * 至少不会在首页下面露出一张和海报一模一样、还更亮的锐利重复图。
 */
@Composable
private fun HeroBlurImage(entry: TmdbMeta, canBlur: Boolean, modifier: Modifier = Modifier) {
    AsyncImage(
        model = entry.backdropUrl.ifBlank { entry.posterUrl },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer { alpha = if (canBlur) 1f else 0.42f }
            .then(if (canBlur) Modifier.blur(40.dp) else Modifier)
    )
}

/**
 * A 层之三：整屏压暗。
 *
 * 上半段（海报区）压到 0.86，保证白色片名 / 简介可读；越过海报下缘后回落到 0.56，
 * **让模糊底图透出来**（用户明确不要「纯黑背景」）。
 * 用一整条连续渐变而不是拼接两块，避免在 heroHeight 处出现横向硬边。
 */
@Composable
fun HomeScrim(heroFraction: Float, modifier: Modifier = Modifier) {
    // 渐变按屏高取比例，关键停靠点都换算成「hero 高度的几倍」，屏幕高矮都不会错位。
    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        // 0.38 个 hero 高度以内完全不动图
                        (heroFraction * 0.38f) to Color.Transparent,
                        (heroFraction * 0.62f) to Color.Black.copy(alpha = 0.30f),
                        // 片名所在位置：压得最狠
                        (heroFraction * 0.80f) to Color.Black.copy(alpha = 0.62f),
                        // 海报下缘
                        heroFraction to Color.Black.copy(alpha = 0.86f),
                        // 横滑榜单区域：回落，露出（已衰减的）模糊纹理
                        (heroFraction * 1.30f) to Color.Black.copy(alpha = 0.50f),
                        1.00f to Color.Black.copy(alpha = 0.46f)
                    )
                )
            }
    )
}

/**
 * B 层文案块：片名 logo / 标题 + 信息行 + 两行简介 + 圆点。
 *
 * **横向跟手**：A 层换页时文案必须跟着一起滑，不能等页面停稳才「跳」成下一条。
 * 做法是「当前页」与「即将进入的那一页」两块文案叠在同一个 Box 里，各自按
 * [PagerState.currentPageOffsetFraction] 平移一个屏宽 —— 两层首尾相接，翻页时
 * 左右各露一半，观感与真正的 Pager 一致。
 *
 * 拖过一半时 `currentPage` 翻转、偏移反号，两层内容互换位置，几何上是完全连续的，
 * 不会闪。**不要退化成「用 settledPage 只渲染一条」**：那样滑动中途文案是定住的。
 *
 * @param fadeAlpha 0..1，随垂直滚动淡出。整块会在遮罩变白之前先退场，
 *   这样白色字 / 白色 logo 不会糊在白底上。（只在 draw 阶段读滚动值。）
 */
@Composable
fun HeroHeadline(
    entries: List<TmdbMeta>,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    fadeAlpha: () -> Float = { 1f }
) {
    if (entries.isEmpty()) return
    val count = entries.size
    // 只在真的滑动时才多渲染一块「邻页」：静止时它整块在屏幕外，
    // 白渲染一块的代价是白白多下一次 logo。
    val sliding by remember(pagerState) {
        derivedStateOf { abs(pagerState.currentPageOffsetFraction) > 0.002f }
    }
    val direction by remember(pagerState) {
        derivedStateOf { if (pagerState.currentPageOffsetFraction >= 0f) 1 else -1 }
    }
    val currentPage = pagerState.currentPage
    // 平移量必须用**整屏宽**（Pager 的页宽），不能用文案区的宽度：
    // 文案区左右各留了 20dp，按它算会让邻页提前 40dp 冒头，和海报错位。
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    Column(modifier = modifier.graphicsLayer { alpha = fadeAlpha().coerceIn(0f, 1f) }) {
        Box(Modifier.fillMaxWidth()) {
            HeadlineBody(
                entry = entries[Math.floorMod(currentPage, count)],
                modifier = Modifier.graphicsLayer {
                    translationX = -pagerState.currentPageOffsetFraction * screenWidthPx
                }
            )
            if (sliding) {
                // matchParentSize：让当前页决定整块高度，邻页在这块里底部对齐，
                // 否则两页文案行数不同会让整块高度来回抖（底部对齐 → 顶边上下颤）。
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    HeadlineBody(
                        entry = entries[Math.floorMod(currentPage + direction, count)],
                        modifier = Modifier.graphicsLayer {
                            // 下一张从右边进、上一张从左边进，正好接在当前页的另一侧。
                            translationX =
                                (-pagerState.currentPageOffsetFraction + direction) * screenWidthPx
                        }
                    )
                }
            }
        }
        if (count > 1) {
            Spacer(Modifier.height(16.dp))
            // 圆点在整行居中（用户 22:19 指出原来贴在左边）。标题仍左对齐。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HeroDots(pagerState = pagerState, pageCount = count)
            }
        }
    }
}

/** 一块文案（不含圆点）。两块叠着一起平移，所以自身不能画任何背景。 */
@Composable
private fun HeadlineBody(entry: TmdbMeta, modifier: Modifier = Modifier) {
    Column(modifier) {
        HeroTitle(entry)
        Spacer(Modifier.height(10.dp))
        Text(
            text = metaLine(entry),
            color = Color.White.copy(alpha = 0.86f),
            fontSize = 12.sp
        )
        if (entry.overview.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.overview,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 片名：TMDB 有透明 PNG logo 就贴图，没有才退回文本。
 *
 * **logo 未就绪时先显示文本标题**（不是空白）：查 `images.logos` 要走一次网络、
 * 拿到 URL 后图片本身还要再下一次。这段空窗原来什么都不画，用户看到的就是
 * 「片名半天不出来」——所以文本必须垫在下面，图片 onSuccess 后才接管。
 */
@Composable
private fun HeroTitle(entry: TmdbMeta) {
    var failed by remember(entry.logoUrl) { mutableStateOf(false) }
    var ready by remember(entry.logoUrl) { mutableStateOf(false) }

    if (entry.logoUrl.isBlank() || failed) {
        TitleText(entry.title)
        return
    }
    Box {
        if (!ready) TitleText(entry.title)
        AsyncImage(
            model = entry.logoUrl,
            contentDescription = entry.title,
            contentScale = ContentScale.Fit,
            // 左对齐：logo 宽度不一，居中会跳来跳去
            alignment = Alignment.CenterStart,
            onSuccess = { ready = true },
            onError = { failed = true },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.82f)
                .height(56.dp)
                .alpha(if (ready) 1f else 0f)
        )
    }
}

/** 文本片名：logo 缺失 / 加载中 / 加载失败时的兜底。 */
@Composable
private fun TitleText(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** 圆点指示器：当前页拉长成 16dp 胶囊，其余 5dp 小圆。 */
@Composable
private fun HeroDots(pagerState: PagerState, pageCount: Int) {
    if (pageCount <= 1) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val current = pagerState.currentPage % pageCount
        repeat(pageCount) { index ->
            val selected = index == current
            val w by animateDpAsState(if (selected) 16.dp else 5.dp, tween(220), label = "dot")
            Box(
                Modifier
                    .height(5.dp)
                    .width(w)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (selected) 0.95f else 0.38f))
            )
        }
    }
}

/** 「电影 · 2026 · ★ 8.9」。票数不足 10 的评分不显示，和详情页保持一致。 */
private fun metaLine(entry: TmdbMeta): String = buildList {
    add(if (entry.mediaType == "tv") "剧集" else "电影")
    if (entry.year.isNotBlank()) add(entry.year)
    if (entry.voteAverage > 0.0 && entry.voteCount >= 10) {
        add("★ ${"%.1f".format(entry.voteAverage)}")
    }
}.joinToString(" · ")

package com.damon.mobilebox.ui.main

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tmdb.HomeCache
import com.damon.mobilebox.data.tmdb.TmdbMeta
import com.damon.mobilebox.data.tmdb.TmdbRepository
import com.damon.mobilebox.ui.components.HeroBlurBackdrop
import com.damon.mobilebox.ui.components.HeroHeadline
import com.damon.mobilebox.ui.components.HeroPosterPager
import com.damon.mobilebox.ui.components.HomeBaseColor
import com.damon.mobilebox.ui.components.HomeScrim
import com.damon.mobilebox.ui.components.HomeTextColors
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.components.homeTextColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 首页一行横滑。[key] 用于快照回写时把三行对应回磁盘字段，别用标题去匹配 ——
 * 文案随时可能改，key 是稳定的。
 */
private data class HomeRow(val key: String, val title: String, val items: List<TmdbMeta>)

/** 三行按固定顺序组装，空行剔除。快照恢复与网络加载共用同一段逻辑。 */
private fun buildRows(
    hot: List<TmdbMeta>,
    airing: List<TmdbMeta>,
    top: List<TmdbMeta>
): List<HomeRow> = listOf(
    HomeRow("hot", "正在热映", hot),
    HomeRow("airing", "正在播出", airing),
    HomeRow("top", "高分佳作", top)
).filter { it.items.isNotEmpty() }

/** 轮播挂掉时的兜底：从「正在热映」里挑有大图 + 简介的条目。 */
private fun heroFallback(hot: List<TmdbMeta>): List<TmdbMeta> =
    hot.filter { it.backdropUrl.isNotBlank() && it.overview.isNotBlank() }.take(8)

/**
 * 网络结果覆盖前，把旧数据里已经查到的 logo 搬过来。
 *
 * `/trending` 与榜单接口都不带 `images`，每次网络刷新拿到的新对象 `logoUrl` 是空的。
 * 直接覆盖会让已经显示成艺术字的片名退回文本、等 `logoOf` 再查一次 —— 肉眼就是闪一下。
 * 请求本身有 `logoCache`（含负结果），但那次闪没必要。
 */
private fun List<TmdbMeta>.keepLogos(old: List<TmdbMeta>): List<TmdbMeta> {
    if (old.isEmpty() || isEmpty()) return this
    val byId = old.associateBy { "${it.mediaType}:${it.tmdbId}" }
    return map { meta ->
        if (meta.logoUrl.isNotBlank()) return@map meta
        val prev = byId["${meta.mediaType}:${meta.tmdbId}"] ?: return@map meta
        if (prev.logoUrl.isBlank()) meta
        else meta.copy(logoUrl = prev.logoUrl, logoAspect = prev.logoAspect)
    }
}

/** Hero 占屏高比例。用户 22:19 明确「海报就保持这个大小」。 */
internal const val HERO_SCREEN_FRACTION = 0.68f

/** 遮罩走完所需的滚动距离（占 hero 高度的比例），与详情页 0.65 对齐。 */
internal const val VEIL_FRACTION = 0.65f

/** 下拉刷新整体超时：任何一个请求挂住都不能让转圈停不下来。 */
private const val REFRESH_TIMEOUT_MS = 15_000L

/** 自动轮播间隔。用户手动滑过之后会重新计时（见下），不会刚松手就被切走。 */
private const val AUTO_PLAY_MS = 5_000L

/**
 * 首页（第 1 页）：纯 TMDB 内容。
 *
 * **层级模型沿用详情页（用户 22:04 定义，22:19 补充背景与遮罩）**：
 *  · A 层 = 固定不动的视觉底层，三块自下而上：
 *      1. 大海报（`HeroPosterPager`）：不位移、不淡出，滚动只放大 1.0 → 1.06；
 *      2. 海报的模糊延续（`HeroBlurBackdrop`）：同一张 backdrop 全屏高斯模糊，
 *         用竖向 alpha 遮罩从 0 渐到 1 —— **标题/简介下方不再是纯黑**；
 *      3. 整屏压暗（`HomeScrim`）：海报区压到 0.86，榜单区回落到 0.56 让模糊纹理透出来。
 *  · 遮罩层 = `colorScheme.surface`，alpha 随滚动 0 → 1（[VEIL_FRACTION] 内走完）：
 *    浅色主题就是「逐渐变白」，深色主题就是「逐渐压暗」，与详情页同一套。
 *  · B 层 = 片名 / 简介 / 圆点 / 三行横滑，独立 `verticalScroll` 覆盖层。
 *
 * 上一版把「海报 + 文案」整体放进列表随滚动淡出、露出底下另一张模糊图 ——
 * 两张图的亮度永远对不上，22:01 截图里就是一条明显断层。
 * **不要退回那个方案**：模糊层必须画在同一张海报之上、用 alpha 遮罩接管。
 *
 * B 层首块上的手势层（见下方透明 Box）负责代理横向翻页和点击：A 层的 Pager
 * 关掉了 `userScrollEnabled`，因为它永远收不到指针事件 —— 整屏滚动层在它上面。
 */
@Composable
fun TmdbHomeContent(
    onEntryClick: (TmdbMeta) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 由外层持有：底部导航栏要读它算「该透明还是该变白」。
     * 放在这里而不是内部 remember，就是为了让导航栏和遮罩共用同一个进度。
     */
    scrollState: ScrollState = rememberScrollState(),
    /** 底部导航栏高度。首页内容铺到它下面（沉浸），但最后一行必须能被滚出来。 */
    bottomInset: Dp = 0.dp
) {
    // 同进程内再次进入首页（切 Tab 回来）时快照已经在内存里 —— 首帧直接有内容，
    // 不用先闪一下骨架再等 load()。冷启动第一次 peek() 是 null，交给下面的 load()。
    val warmSnapshot = remember { HomeCache.peek() }
    var heroEntries by remember {
        val snap = warmSnapshot
        mutableStateOf(if (snap == null) emptyList() else snap.trending.ifEmpty { heroFallback(snap.hot) })
    }
    var rows by remember {
        val snap = warmSnapshot
        mutableStateOf(if (snap == null) emptyList() else buildRows(snap.hot, snap.airing, snap.top))
    }
    var firstLoad by remember { mutableStateOf(heroEntries.isEmpty() && rows.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    // 松手后的吸附动画。手指再次落下时必须先掐掉它，
    // 否则动画每帧都把 offset 写回去，用户会觉得「拖不动」。
    var snapJob by remember { mutableStateOf<Job?>(null) }
    // pager 的页数取 size*1000，起点必须落在中段才能左右都能翻；数据是异步到的，
    // 首次非空时挪一次（挪前后都停在同一张图上，不会有可见跳变）。
    var centered by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val heroHeight = (configuration.screenHeightDp.dp * HERO_SCREEN_FRACTION)
        .coerceIn(420.dp, 700.dp)
    val heroHeightPx = with(density) { heroHeight.toPx() }
    // 状态栏高度：B 层视口从状态栏下沿开始，滚上去的内容不会钻进挖孔/时间旁边。
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val statusBarTop = with(density) { statusBarTopPx.toDp() }

    val pageCount = heroEntries.size.coerceAtLeast(1) * 1000
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })

    // ---- 遮罩进度：整屏变白 / 字色 / 状态栏图标三件事共用同一份进度，保证永远同拍 ----
    val veilProgress = remember(heroHeightPx) {
        derivedStateOf {
            (scrollState.value / (heroHeightPx * VEIL_FRACTION).coerceAtLeast(1f))
                .coerceIn(0f, 1f)
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    val lightSurface = surface.luminance() > 0.5f
    // 字色量化成 24 档：直接读连续 float 会让整页每滚一帧重组一次；
    // 24 档在白→近黑之间肉眼分辨不出台阶，重组次数却只有个位数。
    val colorStep by remember { derivedStateOf { (veilProgress.value * 24f).roundToInt() } }
    val textColors = homeTextColors(colorStep / 24f, lightSurface)
    // 状态栏图标：压在暗海报上要浅色，整屏变白之后要深色。深色主题整屏只会更暗，恒浅色。
    val darkStatusIcons by remember(lightSurface) {
        derivedStateOf { !lightSurface || veilProgress.value > 0.5f }
    }

    // ---- 状态栏图标 ----
    // AppTheme 里有一个祖先 SideEffect 也在写这个开关（按主题设置）。子节点的 SideEffect
    // 晚于祖先执行，所以这里总能拿到最后一次写入权 —— 用 DisposableEffect 会被它覆盖，
    // 就是 22:18 截图里「深色图标压在暗海报上看不见」的原因。
    val view = LocalView.current
    val insetsController = (view.context as? Activity)?.window
        ?.let { WindowCompat.getInsetsController(it, view) }
    val appliedIcons = remember { intArrayOf(-1) }
    SideEffect {
        val want = if (darkStatusIcons) 1 else 0
        if (appliedIcons[0] != want) {
            appliedIcons[0] = want
            insetsController?.isAppearanceLightStatusBars = darkStatusIcons
        }
    }
    DisposableEffect(Unit) {
        val original = insetsController?.isAppearanceLightStatusBars ?: true
        onDispose {
            appliedIcons[0] = -1
            insetsController?.isAppearanceLightStatusBars = original
        }
    }

    LaunchedEffect(reloadTick) {
        val force = reloadTick > 0
        if (force) refreshing = true
        try {
            // ① 快照先行。读盘几十毫秒，比网络快一到两个数量级：
            //    冷启动进来先看到上次的海报墙，网络回来再原地替换，基本看不到骨架屏。
            //    只在当前为空时读 —— 下拉刷新不能拿旧快照把新内容盖回去。
            if (heroEntries.isEmpty()) {
                val snap = safe({ HomeCache.load() }, null)
                if (snap != null) {
                    heroEntries = snap.trending.ifEmpty { heroFallback(snap.hot) }
                    rows = buildRows(snap.hot, snap.airing, snap.top)
                    if (heroEntries.isNotEmpty() || rows.isNotEmpty()) firstLoad = false
                }
            }

            // ② 网络。四个榜单并发，但**轮播先到就先替换首屏**；
            //    原来把四个 await 一起兜住，首屏时间等于「最慢的那个请求」。
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                coroutineScope {
                    val trendingTask = async { safe({ TmdbRepository.trending(8, force) }, emptyList()) }
                    val hotTask = async { safe({ TmdbRepository.nowPlaying(14, force) }, emptyList()) }
                    val airingTask = async { safe({ TmdbRepository.onTheAir(14, force) }, emptyList()) }
                    val topTask = async { safe({ TmdbRepository.topRated(14, force) }, emptyList()) }

                    val trending = trendingTask.await()
                    if (trending.isNotEmpty()) {
                        // 保留快照里已经查到的 logo，否则网络刷新会让片名闪回文本
                        heroEntries = trending.keepLogos(heroEntries)
                        firstLoad = false
                    }
                    val hot = hotTask.await()
                    val airing = airingTask.await()
                    val top = topTask.await()

                    // 一条都没回来（离线 / 超时）：保留快照内容，别把好画面冲成空白。
                    if (hot.isEmpty() && airing.isEmpty() && top.isEmpty()) return@coroutineScope
                    // 轮播挂了（榜单还通）且手上没有 hero 时，用「正在热映」有图有条目的顶上，
                    // 否则首屏会顶着一个空的黑块。
                    if (heroEntries.isEmpty()) heroEntries = heroFallback(hot)
                    rows = buildRows(hot, airing, top)
                }
            }
        } finally {
            firstLoad = false
            refreshing = false
            // 超时 / 取消都可能跳过上面的赋值，这里补一次判定，
            // 免得落到 `else ->` 分支显示一片空白（既没有空态也没有内容）。
            failed = heroEntries.isEmpty() && rows.isEmpty()
        }

        // 透明 PNG 片名逐条补：谁先查回来就先换谁。
        // 攒成一批（原来的 withLogos）意味着首页那条要等最慢的一条，白等好几秒。
        heroEntries.forEach { meta ->
            launch {
                val updated = safe({ TmdbRepository.logoOf(meta) }, meta)
                if (updated.logoUrl != meta.logoUrl) {
                    heroEntries = heroEntries.map {
                        if (it.tmdbId == meta.tmdbId && it.mediaType == meta.mediaType) updated else it
                    }
                }
            }
        }
    }

    // ③ 快照回写：hero / 三行任一变化就存一次。连续变化（八条 logo 陆续到）
    //    由 HomeCache 内部合并成一次写盘，这里不用自己做 debounce。
    LaunchedEffect(heroEntries, rows) {
        if (heroEntries.isEmpty() && rows.isEmpty()) return@LaunchedEffect
        HomeCache.save(
            trending = heroEntries,
            hot = rows.firstOrNull { it.key == "hot" }?.items.orEmpty(),
            airing = rows.firstOrNull { it.key == "airing" }?.items.orEmpty(),
            top = rows.firstOrNull { it.key == "top" }?.items.orEmpty()
        )
    }

    LaunchedEffect(heroEntries.size) {
        if (!centered && heroEntries.size > 1) {
            centered = true
            pagerState.scrollToPage(heroEntries.size * 500)
        }
    }

    // 自动轮播：停 5 秒切下一页。
    // 两个条件缺一不可 —— `dragging` 管「手指按着」，`isScrollInProgress` 管
    // 「上一轮滑动 / 吸附动画还没停」，任一成立就重新计时。
    // 少了后者，4.5s 的定时器会正好在用户拖到一半时 animateScrollToPage，
    // 把 pager 从手指底下抢走：这就是「banner 很难滑」的一半原因。
    LaunchedEffect(pagerState, heroEntries.size) {
        if (heroEntries.size <= 1) return@LaunchedEffect
        while (true) {
            delay(AUTO_PLAY_MS)
            if (dragging || pagerState.isScrollInProgress) continue
            pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tweenPage)
        }
    }

    val settledEntry = heroEntries.getOrNull(
        if (heroEntries.isEmpty()) -1 else pagerState.settledPage % heroEntries.size
    )

    Box(modifier = modifier.fillMaxSize().background(HomeBaseColor)) {
        // ---- A 层 1：固定海报（不位移、不淡出）----
        if (heroEntries.isNotEmpty()) {
            HeroPosterPager(
                entries = heroEntries,
                pagerState = pagerState,
                height = heroHeight,
                // 滚动进度在 draw 阶段读，不在组合阶段读：否则每滚一帧整页重组一次。
                zoom = { scrollState.value / heroHeightPx }
            )
        }

        // ---- A 层 2：海报的模糊延续（标题/简介下方不再是纯黑）----
        // 0.55 → 1.0 个 hero 高度之间完成「清晰 → 模糊」的接管，往下一直保持。
        HeroBlurBackdrop(
            entries = heroEntries,
            pagerState = pagerState,
            fadeFromPx = heroHeightPx * 0.55f,
            fadeToPx = heroHeightPx,
            // 海报下缘往下 0.6 个 hero 之间，把模糊衰减到 30%：
            // 榜单区域要「通透」，不能一层厚糊糊的图压着（用户 22:53 要求再透一点）。
            decayFromPx = heroHeightPx,
            decayToPx = heroHeightPx * 1.6f,
            decayAlpha = 0.30f,
            modifier = Modifier.fillMaxSize()
        )

        // ---- A 层 3：整屏压暗（海报区压狠、榜单区回落，让模糊纹理透出来）----
        HomeScrim(
            heroFraction = configuration.screenHeightDp
                .let { heroHeight.value / it.coerceAtLeast(1) },
            modifier = Modifier.fillMaxSize()
        )

        // ---- 遮罩：A 之上、B 之下。滚到 65% hero 高度时整屏变成 surface 色 ----
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = (scrollState.value / (heroHeightPx * VEIL_FRACTION).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    drawRect(surface.copy(alpha = p))
                }
        )

        // ---- B 层：全屏滚动内容 ----
        StandardPullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!refreshing) reloadTick++
            },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                firstLoad -> HomeSkeleton(heroHeight)
                failed -> HomeEmptyState(onRetry = { reloadTick++ })
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 视口从状态栏下沿开始：内容滚到顶时止步于此，不会钻进状态栏。
                        .padding(top = statusBarTop)
                        .verticalScroll(scrollState)
                ) {
                    if (heroEntries.isNotEmpty()) {
                        Box(
                            // 高度扣掉状态栏，片名底部仍与海报底部对齐（首屏观感不变）
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((heroHeight - statusBarTop).coerceAtLeast(0.dp)),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            HeroHeadline(
                                entries = heroEntries,
                                pagerState = pagerState,
                                // 遮罩变白之前先退场：白字/白 logo 糊在白底上就是一片空白。
                                fadeAlpha = {
                                    val p = (scrollState.value /
                                        (heroHeightPx * 0.55f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    (1f - p * 1.15f).coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
                            )
                            // 手势层压在文案上（透明）：整块海报都能点开详情、都能左右滑。
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { settledEntry?.let(onEntryClick) }
                                    .heroSwipe(
                                        pagerState = pagerState,
                                        onTouchDown = {
                                            // 手指一落下就暂停自动轮播，并掐掉上一轮吸附动画：
                                            // 等拖过 touchSlop 再停，定时器正好在这个空档切页。
                                            dragging = true
                                            snapJob?.cancel()
                                            snapJob = null
                                        },
                                        onTouchUp = { dragging = false },
                                        onSnap = { target ->
                                            snapJob = scope.launch {
                                                pagerState.animateScrollToPage(
                                                    target,
                                                    animationSpec = tweenPage
                                                )
                                            }
                                        }
                                    )
                            )
                        }
                    }
                    rows.forEach { row ->
                        HomeRowSection(row = row, colors = textColors, onEntryClick = onEntryClick)
                    }
                    // 首页内容铺到导航栏下面（沉浸），所以底部留白要把导航栏高度算进去，
                    // 否则最后一行海报永远被它压住一截。
                    Spacer(Modifier.height(76.dp + bottomInset))
                }
            }
        }

        // ---- 底部渐隐：让内容在悬浮导航栏后面「化开」----
        // 必须画在 B 层**之上**（之前放在下面，所以完全没生效）。
        //
        // 「渐隐到底色」的终点必须精确落在**导航栏上沿**（= Scaffold 的 bottomBar 高度，
        // 也就是 bottomInset）。原来硬编码 104dp，而导航栏上沿在 ~120dp 处，于是玻璃背后
        // **上半截是「渐显中的内容」、下半截是纯底色** —— 那条斜接边界被半透明玻璃再透一次，
        // 就是用户 22:53 截图里的一根横条。改成跟着 inset 走，玻璃背后恒为一个平面，
        // 无论滚动到哪一屏都不会再出现接缝，也不需要给玻璃自己补色带。
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = (scrollState.value / (heroHeightPx * VEIL_FRACTION).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    val tint = lerp(HomeBaseColor, surface, p)
                    val h = size.height.coerceAtLeast(1f)
                    val barTopPx = with(density) { bottomInset.toPx() }
                    // 完全吃掉内容的点 = 导航栏上沿；渐隐从它上面 110dp 开始。
                    val solid = ((h - barTopPx) / h).coerceIn(0f, 1f)
                    val from = ((h - barTopPx - 110.dp.toPx()) / h).coerceIn(0f, 1f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            from to Color.Transparent,
                            solid to tint,
                            1f to tint
                        )
                    )
                }
        )
    }
}

private val tweenPage = androidx.compose.animation.core.tween<Float>(320)

/** 吸附阈值：拖过一页的 25% 就翻过去。原来用 0.5（半屏）才有反应，手感像「滑不动」。 */
private const val SNAP_FRACTION = 0.25f

/**
 * Hero 区域的横向翻页手势。
 *
 * 为什么不用 `detectHorizontalDragGestures`：
 *  1. 它只在「横向先撞到 touchSlop」时才成立。斜着划一下就被外层 `verticalScroll`
 *     抢走，用户手感就是「banner 很难滑」；而且它没有方向判定，没法主动把
 *     纵向手势让回去。
 *  2. 原实现抬手要 `currentPageOffsetFraction > 0.5` 才翻页 —— 等于必须拖过半屏。
 *
 * 这里自己判主轴：横向占优才消费并驱动 Pager，纵向占优一个事件都不消费、
 * 立刻退出把控制权交还 `verticalScroll`；抬手按「甩动速度 + 25% 位移」吸附。
 *
 * @param onTouchDown 手指落下（早于 touchSlop）—— 自动轮播必须在这一刻暂停。
 * @param onSnap 需要吸附到的目标页，由调用方负责 launch 动画（并保存 Job 以便下次打断）。
 */
private fun Modifier.heroSwipe(
    pagerState: PagerState,
    onTouchDown: () -> Unit,
    onTouchUp: () -> Unit,
    onSnap: (Int) -> Unit
): Modifier = pointerInput(pagerState) {
    val slop = viewConfiguration.touchSlop
    // 甩动判定阈值：400dp/s。低于它只按位移比例吸附（25% 一页）。
    val minFling = 400.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onTouchDown()
        var totalX = 0f
        var totalY = 0f
        var horizontal: Boolean? = null
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // 抬手（无论有没有超过 slop）都要立刻退出，否则会在事件流上挂死，
                // 点击也永远等不到后续事件。
                if (!change.pressed) break
                val dx = change.positionChangeIgnoreConsumed().x
                val dy = change.positionChangeIgnoreConsumed().y
                tracker.addPosition(change.uptimeMillis, change.position)

                if (horizontal == null) {
                    totalX += dx
                    totalY += dy
                    if (abs(totalX) > slop || abs(totalY) > slop) {
                        horizontal = abs(totalX) > abs(totalY)
                        // 纵向占优：一个事件都不消费，原样交还给外层滚动
                        if (horizontal == false) break
                    }
                    continue
                }
                change.consume()
                pagerState.dispatchRawDelta(-dx)
            }
        } finally {
            onTouchUp()
        }
        if (horizontal == true) {
            val velocityX = tracker.calculateVelocity().x
            val fraction = pagerState.currentPageOffsetFraction
            val target = pagerState.currentPage + when {
                velocityX < -minFling -> 1
                velocityX > minFling -> -1
                fraction > SNAP_FRACTION -> 1
                fraction < -SNAP_FRACTION -> -1
                else -> 0
            }
            onSnap(target)
        }
    }
}

/** 单个请求兜底：异常吞掉返回空表，但**必须放行 CancellationException**，
 *  否则整体超时会失效（withTimeoutOrNull 等不到子协程真正结束）。 */
private suspend fun <T> safe(block: suspend () -> T, fallback: T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        fallback
    }

/** 一行：小标题 + 横滑海报。左右 20dp 与 Hero 的文案边距对齐。 */
@Composable
private fun HomeRowSection(
    row: HomeRow,
    colors: HomeTextColors,
    onEntryClick: (TmdbMeta) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(
            text = row.title,
            // 跟着整屏遮罩走：首屏压在模糊图上用白字，滚到白底上变近黑，不会白底白字。
            color = colors.primary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                count = row.items.size,
                key = { index -> "${row.items[index].mediaType}:${row.items[index].tmdbId}" }
            ) { index ->
                PosterTile(
                    entry = row.items[index],
                    colors = colors,
                    onClick = onEntryClick
                )
            }
        }
    }
}

/** 海报卡：2:3 竖版 + 单行标题。参考图里没有标题，但中文名比海报上的英文名好认，留着更实用。 */
@Composable
private fun PosterTile(
    entry: TmdbMeta,
    colors: HomeTextColors,
    onClick: (TmdbMeta) -> Unit
) {
    val width = 112.dp
    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick(entry) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1B2028))
        ) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.title,
            color = colors.primary.copy(alpha = 0.92f),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 首屏骨架：一块大图 + 两行占位，避免首帧直接黑屏。 */
@Composable
private fun HomeSkeleton(heroHeight: Dp) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(heroHeight).background(Color(0xFF141A22)))
        repeat(2) {
            Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
                Box(
                    Modifier
                        .padding(start = 20.dp)
                        .width(88.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(6) {
                        Box(
                            Modifier
                                .width(112.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                        )
                    }
                }
            }
        }
    }
}

/** TMDB 全挂时的空态：只留一句说明和重试，不做假数据兜底。 */
@Composable
private fun HomeEmptyState(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "内容加载失败",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TMDB 暂时没有返回数据，检查网络后重试",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f))
            ) {
                Text(text = "重试", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

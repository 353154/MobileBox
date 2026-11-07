package com.damon.mobilebox.ui.detail

import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.watch.FavoriteItem
import com.damon.mobilebox.data.watch.WatchStore
import com.damon.mobilebox.ui.components.AmbientBackdrop
import com.damon.mobilebox.ui.components.toLoadableImageUrl
import com.damon.mobilebox.ui.components.rememberRetryableImageRequest
import com.damon.mobilebox.ui.components.detailHeroBandBottomFraction
import com.damon.mobilebox.ui.components.rememberPosterTone
import com.damon.mobilebox.ui.player.MobileVideoPlayer
import com.damon.mobilebox.ui.player.PipController
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute
import com.damon.mobilebox.ui.player.displayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HeroScrim = Color(0xFF080B12)
// B 层统一字色：整屏会随滚动变白，深色文字才可读；不跟随 MaterialTheme 的灰阶/主题蓝
private val BTextPrimary = Color(0xFF111418)
private val BTextSecondary = Color(0xFF5A616B)
// 深色主题下整屏遮罩是把海报压暗（surface 近黑），字色终点必须反过来变成浅色，
// 否则滚上去就是「黑底黑字」。
private val BTextPrimaryDark = Color(0xFFEDF1F7)
private val BTextSecondaryDark = Color(0xFFA9B2C0)
/** B 层动态字色：首屏压在深色海报上为白，随整屏变白平滑过渡到近黑 */
data class BTextColors(val primary: Color, val secondary: Color)
// 用 compositionLocalOf 而不是 staticCompositionLocalOf：滚动时字色每帧都在变，
// static 版本会让整段 content lambda 全量重组，非 static 只重组真正读取它的节点。
val LocalBTextColors = compositionLocalOf { BTextColors(BTextPrimary, BTextSecondary) }

/**
 * 原地播放的一次观看会话。
 * 放普通类而不是 mutableStateOf：这两个字段只在播放器首帧回调与
 * DisposableEffect.onDispose 里读写，不需要触发重组；在 onDispose 里写 State
 * 还可能踩到「disposal 期间写状态」。与 GestureSession 同一套路。
 */
private class PlaySession {
    var recordId: String? = null
    var startRealtime: Long = 0L
    // 播放器每 500ms 上报的最新位置 / 总时长，只在 dispose 时一次性落盘。
    // 放普通类而不是 State：每半秒改一次 State 会把整页拖进无意义重组。
    var positionMs: Long = 0L
    var durationMs: Long = 0L
}

/**
 * 沉浸式影视详情页（整页重写）。
 *
 * 层级模型（用户规范）：
 *  · A 层：只含海报本体与叠在其上的动态黑色 Scrim，别无其它内容。固定在底层，
 *    绝不随滚动做 Y 位移；Alpha 恒为 1f，不淡入淡出；上滑时只做 1.0 -> 1.06 的轻微放大
 *    （Crop 裁边，不会露底色）。变白由整屏白遮罩承担，海报本身不加黑 Scrim。
 *  · B 层：片名、年份、分类、评分、简介、选集等全部属于此层，随滚动一起上移。
 *    独立的全屏 verticalScroll 覆盖层，声明在 A 之后因此绘制在 A 之上；首块是 height(heroHeight)
 *    的透明 Box，文字底部对齐（scroll=0 时视觉上仍落在海报下缘）。B 层首块完全透明：
 *    文字不配任何暗底，绝不使用铺满首块或文字块的黑阶，否则矩形边界会切出水平硬线。
 *    进入时做一次性交错入场（淡入 + 自下而上位移，见 entranceMotion）；入场结束后
 *    Alpha 恒为 1f，之后只有滚动引起的 Y 轴位移，不随滚动变淡、也不做逐元素渐显。
 *
 * 组件：全部 Material 3 原生（TopAppBar / Button / IconButton / FilterChip / AssistChip /
 * TabRow / Surface），无自定义玻璃控件。整屏白遮罩 alpha 随滚动 0->1 均匀增长
 * （交叉淡入，复现参考视频 3.0s 深色 -> 4.2s 浅白）；无面板边界、空间渐变、暗底或羽化层。
 * 数据：演职员来自真实 actor/director；剧照数据源暂缺，用 surfaceVariant 占位，后续可接刮削。
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    initialTitle: String = "",
    posterUrl: String = "",
    videoData: VideoDetailData? = null,
    /** 从观看历史进入时的续播位置（毫秒），0 表示正常浏览态。 */
    resumePositionMs: Long = 0L,
    /** 从观看历史进入时要自动起播的集数；>0 才会触发自动播放。 */
    resumeEpisodeId: Int = 0,
    onPlayEpisode: (VideoEpisode) -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val heroHeight = (configuration.screenHeightDp.dp * 0.82f).coerceIn(420.dp, 640.dp)
    val heroHeightPx = with(density) { heroHeight.toPx() }

    // ---- 顶部安全区（ROADMAP Step 2.3）----
    // B 层是独立的全屏滚动视口。内容滚到顶时，正文会一直画到 y=0，钻进状态栏与挖孔区域。
    // 修法不是给内容加 padding（那会让首屏片名整体下移、脱离海报底部），
    // 而是把「视口本身」下移到状态栏下沿，再让首块高度减去同一数值 ——
    // 片名底部仍落在 heroHeight 处，首屏视觉零变化；而滚动止步于状态栏下沿。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 首帧直接透传真实数据，杜绝片名闪烁
    val finalData = remember(movieId, videoData, posterUrl, initialTitle) {
        val base = videoData ?: TvBoxRepository.selectedDetail?.takeIf { it.id.trim() == movieId.trim() }
        ?: TvBoxRepository.realVodList.value.find { it.id.trim() == movieId.trim() }
        ?: TvBoxRepository.categoryVodList.value.find { it.id.trim() == movieId.trim() }
        if (base != null) {
            if (base.title.isBlank() && initialTitle.isNotBlank()) base.copy(title = initialTitle) else base
        } else {
            VideoDetailData.createFromMovie(
                id = movieId,
                title = initialTitle.ifBlank { "影片详情" },
                subtitle = "高清原画",
                rating = "9.0",
                posterUrl = posterUrl
            )
        }
    }
    val effectivePosterUrl = remember(posterUrl, finalData.posterUrl) {
        if (posterUrl.isNotEmpty()) posterUrl else finalData.posterUrl
    }
    val loadablePosterUrl = effectivePosterUrl.toLoadableImageUrl()
    LaunchedEffect(loadablePosterUrl) {
        android.util.Log.d("MB-DETAIL", "poster=$loadablePosterUrl")
    }

    // ---- 跨站线路聚合 ----
    // 「播放源线路」原本只来自当前站点的 vod_play_url。这里按片名并发查其它可搜索站点，
    // 把同名影片的线路追加到 routes 末尾（每条线路自带 siteName，chip 统一显示站名前缀）。
    // 一条线路解码失败时可以直接换另一家站，不用退出去重新搜。
    // 异步追加不阻塞首屏：先展示当前站线路，查到再补。
    var mergedData by remember { mutableStateOf(finalData) }
    LaunchedEffect(finalData) {
        mergedData = finalData
        if (finalData.routes.isEmpty() || finalData.title.isBlank()) return@LaunchedEffect
        val extras = TvBoxRepository.searchCrossSiteRoutes(
            keyword = finalData.title,
            excludeSiteKey = TvBoxRepository.selectedSite.value?.key
        )
        if (extras.isEmpty()) return@LaunchedEffect
        val extraRoutes = extras.flatMap { (site, detail) ->
            detail.routes.map { route ->
                // 只标来源站点，不再把站名拼进 routeName —— 显示格式统一由 displayName() 决定，
                // 否则当前站的线路（FEIFAN/FFM3U8）没有前缀，跨站线路却有，用户看不懂归属。
                route.copy(
                    routeId = "${site.key}_${route.routeId}",
                    siteName = site.name
                )
            }
        }
        if (extraRoutes.isNotEmpty()) {
            mergedData = finalData.copy(routes = finalData.routes + extraRoutes)
            android.util.Log.d("MB-XSITE", "跨站聚合：+${extraRoutes.size} 条线路 / ${extras.size} 个站点")
        }
    }

    val scrollState = rememberScrollState()

    // ==========================================================
    // 原地播放（Anchor-Reset & Spatial-Adaptive In-Place Playback）
    // ----------------------------------------------------------
    // 三相位共用两个进度值，全部只在 layout / draw / graphicsLayer 的 lambda 里读取，
    // 动画期间不触发任何重组（详情页一次重组上百 ms，用 animateDpAsState 会直接卡死）。
    //   Phase 1  morph 未启动：animate() 驱动 scrollState 归零，视口回到视觉原点
    //   Phase 2  morph 0->1：A 层容器「整屏 -> 16:9」，B 层首块同进度收缩，
    //                        信息层因此整体上移补位（两者必须同源，否则片名与视频下沿错开）
    //   Phase 3  posterFade 0->1：容器定型后海报让位，画面换成播放器的加载态
    // ==========================================================
    val morph = remember { Animatable(0f) }
    // 海报让位进度：1f = 已让给播放器（露出黑底 + 加载态）。
    // 刻意不等首帧就淡出 —— 播放器挂上就显示 buffering 菊花，画面立刻进入「正在加载」，
    // 而不是让一张被裁切的海报在那儿顶着冒充视频。
    val posterFade = remember { Animatable(0f) }
    var playTarget by remember { mutableStateOf<VideoEpisode?>(null) }
    var morphBusy by remember { mutableStateOf(false) }
    val playbackScope = rememberCoroutineScope()
    // 观看会话：首帧落地才开，换集 / 换源 / 退出播放 / 页面销毁时结算时长。
    val playSession = remember { PlaySession() }
    // 续播位置只在「历史列表自动起播」时用一次；用户手动点任何播放入口都清零，
    // 否则换集后播放器会带着上一次的 seek 位置重新组合。
    var pendingResumeMs by remember { mutableStateOf(resumePositionMs) }

    // ---- 播放源自动接力 ----
    // attemptAnchor = 本轮起始线路下标；attemptCount = 本轮已尝试的条数（含当前）。
    // 失败就按 anchor + count 往下轮转，直到每条线路都试过才出终态失败提示。
    // 状态放详情页：只有这里持有 mergedData.routes（含异步追加的跨站线路）。
    var attemptAnchor by remember { mutableIntStateOf(0) }
    var attemptCount by remember { mutableIntStateOf(0) }
    var fatalPlaybackError by remember { mutableStateOf<String?>(null) }
    var loadingSourceHint by remember { mutableStateOf<String?>(null) }
    // 手动重试同一个源时 streamUrl 不变，靠它让播放器强制重新 prepare
    var reloadToken by remember { mutableIntStateOf(0) }
    // 氛围底只在「播放态 / 容器还在回弹」时组合：浏览态完全不组合，
    // 不给详情页首帧增加组合开销（首帧削峰是这里最敏感的性能点）。
    // 退出时 playTarget 立刻变 null，靠 morph>0 把这层留到 280ms 回弹跑完再撤，避免突然变黑。
    val ambientVisible by remember {
        derivedStateOf { playTarget != null || morph.value > 0.01f }
    }

    val statusBarPx = with(density) { statusBarTop.toPx() }
    // 播放态容器：标准 16:9。竖屏下即「上下不再有黑边」的电影比例
    val playingHeightPx = with(density) { (configuration.screenWidthDp.dp * 9f / 16f).toPx() }
    // 暗场底色：从「信息浏览态」过渡到「沉浸观影态」的全局压暗终点
    val playerBackdrop = Color(0xFF0A0E17)

    /** 进入原地播放：先归位视口，再收缩容器；已在播放则只换源 */
    // 注意：这两个函数必须声明在 selectedEpisodeId 之后 —— Kotlin 局部函数不允许前向引用
    // 后面声明的局部变量，放前面编译直接报 unresolved reference。

    // 整屏白遮罩进度：同一份进度同时驱动白遮罩、B 层字色与状态栏图标，
    // 保证三者永远同拍，不会出现「背景已经白了、字还是白的」这类错位。
    val veilProgress by remember(heroHeightPx) {
        derivedStateOf {
            (scrollState.value / (heroHeightPx * 0.65f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }

    // ---- 海报明暗采样：首屏文字直接压在海报上，浅色海报必须换深色字 ----
    // 海报以 ContentScale.Crop 铺满整屏且只裁左右，所以图片纵向比例 == 屏幕纵向比例，
    // 片名所在的屏幕位置可以直接当作图片位置来采样。
    // 条带下沿取 hero 块高度（片名贴其底部对齐），向上覆盖片名 + 元信息两行。
    // 条带下沿与 hero 高度同源：列表卡片预热字色采样时必须算出完全相同的值，
    // 否则缓存键对不上，预热就白做了（见 PosterTone.detailHeroBandBottomFraction）。
    val heroFraction = remember(configuration.screenHeightDp) {
        detailHeroBandBottomFraction(configuration.screenHeightDp.toFloat())
    }
    val posterTone = rememberPosterTone(loadablePosterUrl, bandBottomFraction = heroFraction)

    // 遮罩色就是 colorScheme.surface：浅色主题下「变白」、深色主题下「压暗」。
    // 字色终点必须跟着遮罩走，否则深色模式下会滚成黑底黑字。
    val screenSurface = MaterialTheme.colorScheme.surface
    val lightVeil = screenSurface.luminance() > 0.5f
    val veilPrimary = if (lightVeil) BTextPrimary else BTextPrimaryDark
    val veilSecondary = if (lightVeil) BTextSecondary else BTextSecondaryDark

    // 条带偏亮时起手就用深色字；否则维持白字，随白遮罩一起过渡到近黑
    val heroTextStart = if (posterTone.prefersDarkText) BTextPrimary else Color.White
    val heroSubTextStart =
        if (posterTone.prefersDarkText) BTextSecondary else Color.White.copy(alpha = 0.92f)

    // ---- 交错式入场编排（Staggered Entrance）----
    // 海报由 sharedBounds 在 280ms 内从列表位置飞入放大；B 层文字不能等海报定格才出现，
    // 也不能与海报同速起步（会显得整页一起"贴"上来）。这里让文字在海报位移完成约 70%
    // （200ms）时接力：淡入 + 自下而上 34dp 位移 + easeOutCubic 收尾，按视觉顺序逐组铺开。
    // 进度只在 graphicsLayer 的绘制阶段读取（传 lambda 而不是值），滚动/动画都不会触发重组。
    val entrance = remember { Animatable(0f) }

    // 绘制层的硬兜底：入场动画只是「视觉编排」，不能让内容的最终可读性依赖协程是否跑完。
    // 历史故障：动画时钟被并发打断 / 子协程随父协程一起被取消时，entrance.value 会停在中间值，
    // 后半段组（order 大）算出的 alpha 极低 —— 深色字叠在白遮罩上看着像「字变白了」，
    // alpha 到 0 的组则像「内容显示到一半就没了」。scrollState.value 在 graphicsLayer 的
    // 绘制阶段读取，不触发重组；只要用户真的滚过，一律落定到终值，与动画状态彻底解耦。
    val entranceClock: () -> Float = {
        if (scrollState.value > 0) EntranceTotalMs else entrance.value
    }

    LaunchedEffect(Unit) {
        // 以毫秒为单位的编排时钟：线性推进，缓动在各组切片内单独做，避免双重缓动糊掉层次
        entrance.animateTo(
            targetValue = EntranceTotalMs,
            animationSpec = tween(
                durationMillis = EntranceTotalMs.toInt(),
                delayMillis = EntranceStartDelayMs.toInt(),
                easing = LinearEasing
            )
        )
    }
    // 滚动打断必须独立成 effect：以前它是主动画 LaunchedEffect 内的 launch 子协程，
    // 主协程 animateTo 结束 / 被取消时会连带把子协程取消，打断反而没生效。
    // 判据仍是位移量而不是 isScrollInProgress（页面刚组合时后者会瞬间为 true）。
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value }.first { it > 0 }
        entrance.snapTo(EntranceTotalMs)
    }
    // ---- 屏外内容延迟组合（首帧削峰）----
    // 详情页首帧一次性组合全部 8 组内容（4 个 LazyRow、十余个 Chip、按钮与图标），
    // 叠加共享元素转场 Overlay 的首次创建，单个组合/测量帧远超一个 vsync：
    // 实测冷启首次进详情页最慢一帧 150ms，转场中间帧根本没画出来，直接跳到终点。
    // 首帧只保留首屏可见的前 3 组；后 5 组（演职人员/分类/预告片/剧照/操作行）
    // 在入场动画全程都位于屏幕之外，等入场跑完再组合 —— 停顿落在画面静止的一帧上。
    // 注意：不要把这些组再拆成"每帧一组"去摊 —— 每加一组整帧就涨到 30~50ms，
    // 转场会从"一帧跳到终点"退化成 30fps 的多次跳跃（实测更差）。
    var offscreenReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 入场跑完后再多等一拍：此时画面已经静止，组合停顿肉眼看不到
        snapshotFlow { entrance.value }.first { it >= EntranceTotalMs - 0.5f }
        delay(300)
        offscreenReady = true
    }
    // 用户一上手滚动就立即补齐，保证任何时刻都不会缺内容
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value }.first { it > 0 }
        offscreenReady = true
    }
    // 绝对时间兜底：入场动画若因后台冻结 / 被并发取消而没跑到终值，
    // 屏外 5 组也不能永久停在「未组合」。等满整段编排 + 一拍后无条件补齐。
    LaunchedEffect(Unit) {
        delay(EntranceStartDelayMs.toLong() + EntranceTotalMs.toLong() + 400L)
        offscreenReady = true
    }

    // 状态栏图标：深色海报上用浅色图标，背景转白后切成深色图标。
    // 海报顶部本身就偏亮时，起手就直接用深色图标，否则白图标在浅色海报上看不见。
    // 采样结果是外部值，必须作为 remember 的 key，否则 derivedStateOf 的 lambda
    // 会一直捕获首次组合时的旧值（analyzed=false），浅色海报永远不触发。
    val view = LocalView.current
    val insetsController = (view.context as? Activity)?.window
        ?.let { WindowCompat.getInsetsController(it, view) }
    val useDarkStatusIcons by remember(posterTone, lightVeil, playTarget) {
        derivedStateOf {
            // 播放态整页被压到 #0A0E17 暗场，必须用浅色图标，
            // 否则深色图标压在暗底上完全看不见（与 B 层字色同理）
            if (playTarget != null) {
                false
            } else if (lightVeil) {
                posterTone.prefersDarkSystemBar || veilProgress > 0.5f
            } else {
                // 深色主题：遮罩越滚越暗，滚上去后必须换回浅色图标
                posterTone.prefersDarkSystemBar && veilProgress < 0.5f
            }
        }
    }
    DisposableEffect(Unit) {
        val original = insetsController?.isAppearanceLightStatusBars ?: true
        insetsController?.isAppearanceLightStatusBars = false
        onDispose { insetsController?.isAppearanceLightStatusBars = original }
    }
    LaunchedEffect(useDarkStatusIcons) {
        insetsController?.isAppearanceLightStatusBars = useDarkStatusIcons
    }

    var selectedRouteIndex by remember { mutableIntStateOf(0) }
    val currentRoute = mergedData.routes.getOrNull(selectedRouteIndex) ?: mergedData.routes.first()
    // 刻意不以 selectedRouteIndex 作 remember key：自动换源是在组合之外的回调里写这个状态，
    // 带 key 的话下一次重组会重建 State、把刚写进去的「同一集」覆盖回第一集。
    // 切线路统一由 onSelect / startPlayback / onSourceFailed 显式重置。
    var selectedEpisodeId by remember {
        mutableIntStateOf(currentRoute.episodes.firstOrNull()?.episodeId ?: 1)
    }
    // 追剧改为真持久化：退出页面、切 Tab、重启都保留，两个播放页共用同一份收藏表。
    val favorites by WatchStore.favorites.collectAsState()
    val isFavorite = favorites.any { it.videoId == movieId }

    val routeFallbackSiteName = remember { TvBoxRepository.selectedSite.value?.name.orEmpty() }
    /** 与播放源 chip 用同一个取名字函数，保证「正在尝试 X」里的 X 就是 chip 上那行字 */
    fun routeLabel(index: Int): String =
        mergedData.routes.getOrNull(index)
            ?.let { routeDisplayName(it, routeFallbackSiteName) }
            ?: "播放源"

    /**
     * 当前播放源失败：自动切到下一条还没试过的线路，尽量保持同一集。
     * 同一站的集数编号与跨站线路未必一致，按 episodeId → 集名 → 第一集依次兜底。
     * 所有线路都试完才置终态失败提示。
     */
    fun onSourceFailed() {
        val routes = mergedData.routes
        val currentId = playTarget?.episodeId
        val currentName = playTarget?.name
        var next = attemptCount + 1
        while (next < routes.size) {
            val index = (attemptAnchor + next) % routes.size
            val ep = routes[index].episodes.firstOrNull { it.episodeId == currentId }
                ?: routes[index].episodes.firstOrNull { it.name == currentName }
                ?: routes[index].episodes.firstOrNull()
            if (ep != null) {
                attemptCount = next
                selectedRouteIndex = index
                selectedEpisodeId = ep.episodeId
                playTarget = ep
                reloadToken++
                loadingSourceHint = "正在尝试 ${routeLabel(index)}"
                android.util.Log.d("MB-PLAY", "自动换源 ${next + 1}/${routes.size} -> ${routeLabel(index)}")
                return
            }
            next++
        }
        attemptCount = routes.size
        fatalPlaybackError = "播放失败，请更换播放源。"
        loadingSourceHint = null
    }

    /** 进入原地播放：先归位视口，再收缩容器；已在播放则只换源 */
    fun startPlayback(ep: VideoEpisode, keepResume: Boolean = false) {
        if (!keepResume) pendingResumeMs = 0L
        selectedEpisodeId = ep.episodeId
        // 任何手动播放动作（立即播放 / 选集 / 播放源 chip）都开启新一轮尝试。
        // 用户手动指定源后，之前失败的计数不该继续累计。
        attemptAnchor = selectedRouteIndex
        attemptCount = 0
        fatalPlaybackError = null
        loadingSourceHint = "正在尝试 ${routeLabel(selectedRouteIndex)}"
        reloadToken++
        if (morphBusy) return
        if (playTarget != null) {
            // 播放中直接换集/换线路：几何量不动，播放器内部会自己重载源
            playTarget = ep
            return
        }
        morphBusy = true
        playbackScope.launch {
            // 关键：滚动归位 与 容器收缩 必须同进度并行跑完。
            // 串行（先滚回顶部、再收缩）会让内容先回到「刚进详情页」的位置，再往上补位，
            // 中间那个中间态是多余的 —— 用户看到的就是「先一瞬回到初始位置，再往上过度」。
            // 并行之后内容从当前 offset 单调移动到「贴住播放器下沿」，方向由当前偏移决定：
            // 滚得太上（内容已跑到播放器区）就往下移，还没滚上来就靠首块收缩往上移。
            launch {
                scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
                // 兜底：首块变矮时 scrollState 的 maxValue 会缩小、可能把动画打断在中途
                if (scrollState.value != 0) scrollState.scrollTo(0)
            }
            morph.animateTo(1f, tween(320, easing = FastOutSlowInEasing))

            // 动画全程不挂播放器：ExoPlayer.Builder 创建与 PlayerView 首次 inflate 是
            // 100~300ms 的主线程重活，塞进动画窗口一定掉帧。等容器定型（此时画面是静止的
            // 16:9 海报）再挂，这段一次性开销落在静止帧上，肉眼几乎看不出来。
            playTarget = ep
            // 挂上播放器后把海报让开：播放器自己会显示 loading 菊花，
            // 不需要等首帧（等首帧的这段时间海报裁切块看起来像「播错了」）。
            // 420ms + LinearOutSlowIn：起步快速离开亮部、尾段拖长，观感像灯光缓缓熄灭。
            // 之前 140ms 线性归零会有「啪」一下的硬切感。
            posterFade.animateTo(1f, tween(420, easing = LinearOutSlowInEasing))
            morphBusy = false
        }
    }

    /** 退出原地播放：逆过程。必须先等容器恢复再允许 pop，否则共享转场起止 bounds 对不上 */
    fun exitPlayback() {
        if (morphBusy || playTarget == null) return
        morphBusy = true
        playbackScope.launch {
            // 退出时先把海报盖回视频，再卸载播放器——顺序反过来会先露一帧黑屏。
            // 时长与进入对称收窄一档：进来是「灭灯」，出去要跟得上返回手势的节奏。
            posterFade.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
            playTarget = null
            fatalPlaybackError = null
            loadingSourceHint = null
            attemptCount = 0
            // 等播放器卸载（release）这一帧过去，再回弹容器
            withFrameNanos { }
            morph.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            morphBusy = false
        }
    }

    // 从观看历史进入：转场收尾后自动起播到记录的那一集，并把上次位置交给播放器。
    // LaunchedEffect(Unit) 只跑一次 —— 跨站线路异步追加导致 mergedData 变化不会二次起播；
    // delay 之后再读 mergedData 会拿到最新值，不必把 routes.size 放进 key。
    LaunchedEffect(Unit) {
        if (resumeEpisodeId <= 0) return@LaunchedEffect
        delay(360) // 共享元素转场 280ms，等它收尾再动容器，避免两段动画抢帧
        val routes = mergedData.routes
        if (routes.isEmpty()) return@LaunchedEffect
        var matchIndex = -1
        var matchEpisode: VideoEpisode? = null
        routes.forEachIndexed { index, route ->
            val ep = route.episodes.firstOrNull { it.episodeId == resumeEpisodeId }
            if (ep != null && matchEpisode == null) {
                matchIndex = index
                matchEpisode = ep
            }
        }
        val target = matchEpisode
        if (target != null && matchIndex >= 0) {
            selectedRouteIndex = matchIndex
            selectedEpisodeId = target.episodeId
            startPlayback(target, keepResume = true)
        } else {
            // 线路里已经没有这一集（换源 / 站点改了集数）：退回第一集从头播，
            // 绝不把 A 集的位置套到 B 集上。
            pendingResumeMs = 0L
            routes.firstOrNull()?.episodes?.firstOrNull()?.let { startPlayback(it) }
        }
    }

    // 观看会话结算：key 用 playTarget。换集 / 换源（ep 对象变化）与退出播放（变 null）
    // 都会先结算上一段；页面被 NavHost 移出时同样会 dispose，不会漏记。
    // 计时用 elapsedRealtime，不受用户改系统时间影响。
    DisposableEffect(playTarget) {
        onDispose {
            val id = playSession.recordId
            val start = playSession.startRealtime
            if (id != null && start > 0L) {
                WatchStore.finishSession(
                    id,
                    SystemClock.elapsedRealtime() - start,
                    playSession.positionMs,
                    playSession.durationMs
                )
            }
            playSession.recordId = null
            playSession.startRealtime = 0L
            playSession.positionMs = 0L
            playSession.durationMs = 0L
        }
    }

    BackHandler {
        // 播放中先退出原地播放（含 280ms 逆过程），不直接退页面
        if (playTarget != null) exitPlayback() else onBack()
    }

    with(sharedTransitionScope) {
        with(animatedVisibilityScope) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 全局暗场：播放态把整页底色从「主题 surface」压到深色。
                    // 用 drawBehind 读 morph 进度，动画期间只重绘不重组。
                    .drawBehind {
                        val p = morph.value
                        if (p > 0f) {
                            drawRect(lerp(screenSurface, playerBackdrop, p.coerceIn(0f, 1f)))
                        }
                    }
            ) {
                // 播放态氛围底：视频画框下方原本是一整块纯黑，这里改成从海报 / Bing 壁纸取色铺满。
                // 图源逻辑与播放器首帧前的加载态共用 [AmbientBackdrop]（海报优先、失败回落壁纸）。
                // 关键约束：这层必须严格避开 16:9 画框。SurfaceView 的 surface 在窗口下方打洞，
                // 任何画在画框上的 Compose 内容（哪怕半透明）都会把视频挡住，所以这里用 layout
                // 把高度裁成「屏高 - 状态栏 - 视频高」并贴底对齐，只覆盖下方内容区。
                if (ambientVisible) {
                    AmbientBackdrop(
                        imageUrl = loadablePosterUrl,
                        dimAlpha = 0.40f,
                        wallpaperActive = false,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                val cut = (statusBarPx + playingHeightPx).roundToInt()
                                val h = (constraints.maxHeight - cut).coerceAtLeast(0)
                                val placeable = measurable.measure(
                                    Constraints.fixed(constraints.maxWidth, h)
                                )
                                layout(constraints.maxWidth, h) { placeable.place(0, 0) }
                            }
                            // 顶部 120dp 用整页背景色不透明起步，往下才渐渐透出海报色彩。
                            // 画框现在是同一个 playerBackdrop 纯色，两边在视频下沿严丝合缝，
                            // 不会出现「上面一张模糊图、下面一张模糊图」的接缝。
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(playerBackdrop, Color.Transparent),
                                        endY = 120.dp.toPx().coerceAtMost(size.height)
                                    )
                                )
                            }
                            // 只在 lambda 里读 morph：收缩 / 回弹全程零重组
                            .graphicsLayer { alpha = morph.value }
                    )
                }

                // 1. 层级 A：海报固定底层（零 translationY），不随手势上移
                //    原地播放时容器高度被 morph 压缩到 16:9，并整体下移一个状态栏高度——
                //    用 layout 而非 animateDpAsState：测量阶段读进度，收缩全程零重组。
                    Box(
                        modifier = Modifier
                            // 播放态把播放层压到 B 层内容之上：否则上滑时详情内容会滚进视频区域，
                            // 和播放器/失败提示叠在一起，看起来像一块浮层压在页面上（用户 14:45 反馈）。
                            // 提升后内容滚到视频下沿就被视频挡住，视觉上是「滑进视频后面」。
                            .zIndex(if (playTarget != null) 1f else 0f)
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                val p = morph.value.coerceIn(0f, 1f)
                                val pip = PipController.isInPip
                                // 横屏全屏播放：详情页原地播放不能再按竖屏 16:9 画框收缩，
                                // 否则 playingHeightPx 是按横屏屏宽算出来的、比横屏屏高还大，
                                // 播放层上下都会被裁（顶栏只剩半截、底部时间戳贴边）。
                                // 横屏一律铺满整个窗口，只留视频画面。
                                val fullscreenPlayback = isLandscape && playTarget != null
                                // 常态高度必须取父约束，不能取 configuration.screenHeightDp：
                                // 后者是「可用内容高度」，比窗口矮掉状态栏/导航栏那一段，
                                // 用它会让 A 层底部短一截、露出白条（2026-11-07 14:24 用户截图）。
                                // 取 maxHeight 才与原来的 fillMaxSize 完全等价，首屏零变化。
                                val fullH = constraints.maxHeight.toFloat()
                                // 画中画：整窗缩小，视频必须铺满小窗，否则下方会露出信息层
                                val top = if (pip || fullscreenPlayback) 0f else statusBarPx * p
                                val h = if (pip || fullscreenPlayback) fullH else fullH + (playingHeightPx - fullH) * p
                                val placeable = measurable.measure(
                                    Constraints.fixed(
                                        constraints.maxWidth,
                                        h.roundToInt().coerceAtLeast(1)
                                    )
                                )
                                val totalH = (top + h).roundToInt().coerceAtLeast(1)
                                layout(constraints.maxWidth, totalH) {
                                    placeable.place(0, top.roundToInt())
                                }
                            }
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "poster_${movieId.trim()}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> tween(280, easing = FastOutSlowInEasing) },
                                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(0.dp))
                            )
                            .clip(RoundedCornerShape(0.dp))
                            .graphicsLayer {
                                val p = (scrollState.value / heroHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                val scale = 1f + p * 0.06f
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
                        // 图片加载前的深色占位底（随共享元素一起参与转场）
                        Box(modifier = Modifier.fillMaxSize().background(HeroScrim))

                        // 原地播放的视频层：垫在海报之下。
                        // SurfaceView 的 surface 位于窗口下方、Compose 内容绘制在其上（现有控制条
                        // 就是这样叠着显示的），所以上层海报可以直接盖住播放器做交叉淡入，
                        // 不需要把渲染介质换成 TextureView——那会动到播放链路，风险高一个量级。
                        playTarget?.let { ep ->
                            MobileVideoPlayer(
                                streamUrl = ep.streamUrl,
                                title = "${finalData.title} · ${ep.name}",
                                modifier = Modifier.fillMaxSize(),
                                // 加载态底色就是整页背景色：不再单独铺一张模糊海报，
                                // 画框与下方氛围底是同一种颜色连续下来的（用户 15:24）
                                backdrop = playerBackdrop,
                                showAmbientBackdrop = false,
                                onBackClick = { exitPlayback() },
                                // 只有真正出画面才算一次观看：加载失败与自动换源期间不落历史。
                                // 同一会话内 onRenderedFirstFrame 可能多次触发，用 recordId 幂等。
                                onFirstFrameRendered = {
                                    if (playSession.recordId == null) {
                                        val id = WatchStore.recordPlayback(
                                            videoId = movieId,
                                            title = finalData.title,
                                            posterUrl = effectivePosterUrl,
                                            episodeId = ep.episodeId,
                                            episodeName = ep.name,
                                            sourceName = routeLabel(selectedRouteIndex),
                                            isSeries = mergedData.routes.any { it.episodes.size > 1 }
                                        )
                                        playSession.recordId = id
                                        playSession.startRealtime = SystemClock.elapsedRealtime()
                                    }
                                },
                                // 失败不弹播放器自己的错误卡片：交给详情页按顺序自动换播放源，
                                // 换源期间用真实线路名提示，全部失败才出终态图标 + 文案。
                                onSourceFailed = { onSourceFailed() },
                                loadingHint = loadingSourceHint,
                                fatalError = fatalPlaybackError,
                                reloadToken = reloadToken,
                                // 只写内存，不重组、不落盘；拖动进度条后的新位置会在
                                // 下一次 500ms 轮询里覆盖这里，退出时以最后值为准。
                                onProgressChanged = { position, total ->
                                    playSession.positionMs = position
                                    playSession.durationMs = total
                                },
                                // 历史列表续播：只有自动起播那一次非 0，播放器内部只消费一次。
                                initialPositionMs = pendingResumeMs
                            )
                        }

                        AsyncImage(
                            model = rememberRetryableImageRequest(context, loadablePosterUrl),
                            contentDescription = finalData.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                // 海报只负责「收缩过程」的视觉连续性，容器一定型就交给播放器加载态。
                                // 但不能 140ms 直接归零 —— 那是「彩色海报 → 一块纯黑」的亮度跳变。
                                // 分两段：前 35% 基本保持（只随收缩轻微推近），之后用 smoothstep
                                // 拖长尾段，配合底色同色，整体是「画面随灯光一起暗下去」而不是被切掉。
                                .graphicsLayer {
                                    val f = posterFade.value.coerceIn(0f, 1f)
                                    val t = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                    alpha = 1f - (t * t * (3f - 2f * t))
                                    // 轻微推近，和收缩阶段 Crop 窗口变小的方向一致，避免两段观感打架
                                    val s = 1f + f * 0.04f
                                    scaleX = s
                                    scaleY = s
                                }
                        )
                    }


                // 2. 整屏遮罩：参考视频 3.0s(深色) -> 4.2s(浅白) 是整屏均匀交叉淡入，
                //    不是白色面板从下往上盖，也不是空间上的透明→白渐变。
                //    全屏纯 surface 色矩形 alpha 随滚动 0->1 均匀增长：无面板边界、无渐变起始段、无灰带。
                //    深色主题下 surface 近黑，视觉上就是「整屏压暗」。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val p = (scrollState.value /
                                (heroHeightPx * 0.65f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            // 播放态把遮罩一起撤掉：否则浅色主题下视频下方仍是白底，割裂成两半
                            drawRect(screenSurface.copy(alpha = p * (1f - morph.value)))
                        }
                )

                // 3. 层级 B：独立覆盖滚动层
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 画中画 / 横屏全屏：整窗缩小时只留画面。B 层绘制在 A 层之上，
                        // 不透明的话会盖住小窗；横屏时它还会把片名、蓝色按钮等详情内容
                        // 挤到屏幕边缘露出半截。这两种情况都直接隐藏。
                        .graphicsLayer {
                            alpha = if (PipController.isInPip || (isLandscape && playTarget != null)) 0f else 1f
                        }
                        // 视口下移到状态栏下沿：内容滚到顶时止步于此，不会钻进状态栏/挖孔
                        .padding(top = statusBarTop)
                        .verticalScroll(scrollState)
                ) {
                    // 字色：起点由海报亮度决定（浅色海报直接起手深色字），
                    // 终点跟随主题——浅色主题过渡到近黑，深色主题过渡到浅色，滚上去都保持可读。
                    // 但播放态例外：整页会被强制压到 #0A0E17 暗场，此时再按主题取近黑就是黑底黑字
                    // （用户 14:45 实测截图「继续往上滑，下方的颜色变得不可读」）。
                    // 所以播放态直接锁死浅色系、不参与滚动插值 —— 进入播放后字色恒定，不再漂移。
                    val playingNow = playTarget != null
                    val heroTextColor = if (playingNow) BTextPrimaryDark
                    else lerp(heroTextStart, veilPrimary, veilProgress)
                    val heroSubTextColor = if (playingNow) BTextSecondaryDark
                    else lerp(heroSubTextStart, veilSecondary, veilProgress)
                    val bColors = BTextColors(heroTextColor, heroSubTextColor)
                    CompositionLocalProvider(LocalBTextColors provides bColors) {

                    // B 层首块：与 A 层等高的透明容器，片名/元信息底部对齐，随滚动上移
                    // 高度扣掉视口已下移的状态栏高度，片名底部仍与海报底部对齐（首屏不变）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 与 A 层共用同一份 morph 进度：首块高度从 heroHeight 收到视频高度，
                            // 下方的简介/播放资源/选集因此整体上移补位。两条高度必须同源，
                            // 否则片名会与视频下沿错开半个状态栏（实测最容易出的错位就这一处）。
                            .layout { measurable, constraints ->
                                val p = morph.value.coerceIn(0f, 1f)
                                // layout 的 receiver 是 MeasureScope，本身实现了 Density，
                                // 可以直接 toPx()
                                val browserH = (heroHeight - statusBarTop).toPx().coerceAtLeast(0f)
                                val h = browserH + (playingHeightPx - browserH) * p
                                val ph = h.roundToInt().coerceAtLeast(0)
                                val placeable = measurable.measure(
                                    Constraints.fixed(constraints.maxWidth, ph)
                                )
                                layout(constraints.maxWidth, ph) { placeable.place(0, 0) }
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                // 播放态片名淡出：视频控制条里已有标题，白字压在画面上反而难读
                                .graphicsLayer { alpha = 1f - morph.value }
                                .entranceMotion(entranceClock, order = 0, rise = 30.dp)
                                .padding(horizontal = 20.dp, vertical = 32.dp)
                        ) {
                            Text(
                                text = finalData.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = heroTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            HeroMetaRow(
                                data = finalData,
                                route = currentRoute,
                                textColor = heroTextColor,
                                subTextColor = heroSubTextColor
                            )
                        }
                    }

                    // B 层内容面板完全透明：整屏变白由白遮罩负责
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                        ) {
                            // 播放态：视频下方紧接「选集 → 播放源」，其余详情照常保留在后面。
                            // 只把「立即播放」按钮去掉（已经在播了），并把选集/播放源提到最前——
                            // 用户 14:45 的原话是「播放器下面紧跟着就是选集还有选择播放源」，
                            // 是要求这两块上提，不是要求删掉其它内容。
                            if (playingNow) {
                                // 单集（电影）也要有：只显示一个「正片」chip，不能让用户以为选集消失了
                                if (currentRoute.episodes.isNotEmpty()) {
                                    SectionTitle("选集")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    EpisodeChips(
                                        episodes = currentRoute.episodes,
                                        selectedId = selectedEpisodeId,
                                        onClick = { ep ->
                                            startPlayback(ep)
                                            onPlayEpisode(ep)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(22.dp))
                                }
                                // 单线路也要显示：用户要能看出「当前用的是哪个站点/哪条源」，
                                // 原来 size > 1 会让只有一条线路的影片整块（连标题）消失。
                                if (mergedData.routes.isNotEmpty()) {
                                    SectionTitle("播放源")
                                    Spacer(modifier = Modifier.height(12.dp))
                                    RouteChips(
                                        routes = mergedData.routes,
                                        selectedIndex = selectedRouteIndex,
                                        onSelect = { index ->
                                            selectedRouteIndex = index
                                            // 切线路后集数显式归到新线路第 1 集：不同站的集数编号
                                            // 可能完全不同，沿用旧 episodeId 会落在错误的位置。
                                            // 播放中还要立刻换流，否则选集跳了画面还是旧线路的。
                                            val firstEp = mergedData.routes.getOrNull(index)
                                                ?.episodes?.firstOrNull()
                                            selectedEpisodeId = firstEp?.episodeId ?: 1
                                            if (playTarget != null && firstEp != null) {
                                                startPlayback(firstEp)
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                // 简介照旧保留，只是排在选集/播放源之后
                                Text(
                                    text = finalData.description.ifBlank { TestDescription },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalBTextColors.current.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {

                            // 1. 简介（居中，2 行）
                            Text(
                                text = finalData.description.ifBlank { TestDescription },
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalBTextColors.current.primary,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 1)
                            )
                            Spacer(modifier = Modifier.height(28.dp))

                            // 2. 播放资源：M3 原生按钮 + 选集 / 线路（入场第 3 组）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 2)
                            ) {
                                SectionTitle("播放资源")
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val firstEp = currentRoute.episodes.firstOrNull()
                                        if (firstEp != null) {
                                            startPlayback(firstEp)
                                            onPlayEpisode(firstEp)
                                        }
                                    },
                                    shape = RoundedCornerShape(26.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        // 容器是固定的浅灰，与主题无关；文字必须固定用近黑，
                                        // 不能跟着 B 层动态字色走（深色主题下会变成白字压浅灰）。
                                        containerColor = Color(0xFFE4E7EE),
                                        contentColor = BTextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = BTextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "立即播放 · ${
                                            currentRoute.episodes.firstOrNull()?.let { formatEpisodeName(it.name) }
                                                ?: "第 1 集"
                                        }",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = BTextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                EpisodeChips(
                                    episodes = currentRoute.episodes,
                                    selectedId = selectedEpisodeId,
                                    onClick = { ep ->
                                        startPlayback(ep)
                                        onPlayEpisode(ep)
                                    }
                                )
                                // 与播放态一致：单线路也展示当前播放源，不要让它无故消失
                                if (mergedData.routes.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    RouteChips(
                                        routes = mergedData.routes,
                                        selectedIndex = selectedRouteIndex,
                                        onSelect = { index ->
                                            selectedRouteIndex = index
                                            // 切线路后集数显式归到新线路第 1 集：不同站的集数编号
                                            // 可能完全不同，沿用旧 episodeId 会落在错误的位置。
                                            // 播放中还要立刻换流，否则选集跳了画面还是旧线路的。
                                            val firstEp = mergedData.routes.getOrNull(index)
                                                ?.episodes?.firstOrNull()
                                            selectedEpisodeId = firstEp?.episodeId ?: 1
                                            if (playTarget != null && firstEp != null) {
                                                startPlayback(firstEp)
                                            }
                                        }
                                    )
                                }
                            }
                            } // 浏览态专属内容到这里结束；以下 5 组播放态同样保留

                            // ---- 以下 5 组位于首屏之外，等入场跑完再组合（见 offscreenReady）----
                            if (offscreenReady) {

                            // 3. 演职人员（真实数据优先，不足用测试数据补齐，入场第 4 组）
                            Spacer(modifier = Modifier.height(28.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 3)
                            ) {
                                SectionTitle("演职人员")
                                Spacer(modifier = Modifier.height(12.dp))
                                CastRow(data = finalData)
                            }

                            // 4. 分类（测试数据标签，M3 AssistChip 白底黑字，入场第 5 组）
                            Spacer(modifier = Modifier.height(28.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 4)
                            ) {
                                SectionTitle("分类")
                                Spacer(modifier = Modifier.height(12.dp))
                                TagChips(tags = finalData.tags)
                            }

                            Spacer(modifier = Modifier.height(28.dp))

                            // 5. 预告片（测试数据占位卡，入场第 6 组）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 5)
                            ) {
                                SectionTitle("预告片")
                                Spacer(modifier = Modifier.height(12.dp))
                                TrailerRow()
                            }

                            // 6. 剧照（测试数据占位网格，入场第 7 组）
                            Spacer(modifier = Modifier.height(28.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 6)
                            ) {
                                SectionTitle("剧照")
                                Spacer(modifier = Modifier.height(12.dp))
                                StillsPlaceholder()
                            }

                            // 7. 操作项：M3 原生 IconButton，黑色图标与文字，无悬浮层（入场第 8 组）
                            Spacer(modifier = Modifier.height(28.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion(entranceClock, order = 7),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                DetailAction(
                                    icon = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    label = if (isFavorite) "已追剧" else "追剧",
                                    active = isFavorite,
                                    onClick = {
                                        WatchStore.toggleFavorite(
                                            FavoriteItem(
                                                videoId = movieId,
                                                title = finalData.title,
                                                posterUrl = effectivePosterUrl
                                            )
                                        )
                                        // 这里读的是点击前的状态：true 表示本次操作是取消。
                                        onShowToast(if (isFavorite) "已取消追剧" else "已加入追剧清单")
                                    }
                                )
                                DetailAction(Icons.Default.Download, "离线缓存") { onShowToast("已加入后台高速下载任务") }
                                DetailAction(Icons.Default.Cast, "DLNA投屏") { onShowToast("正在搜索局域网电视设备...") }
                                DetailAction(Icons.Default.Share, "分享") { onShowToast("分享链接已复制至剪贴板") }
                            }
                            }

                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                    }
                }

                // 4. 顶部不再放可视化返回按钮：Android 有系统返回手势/返回键，
                // 返回统一由上面的 BackHandler 处理（播放器打开时先关播放器）。
                // 少一层 M3 TopAppBar 也顺手减掉首次组合与一层离屏绘制。

                // 5. 播放层已并入 A 层（原地播放）：不再弹出独立全屏播放页。
                //    PlayerDetailScreen 仍保留文件与横屏分支，但详情页不再调用它。
            }
        }
    }
}

// ---------- 测试数据占位（后续接入刮削后替换，勿当真实数据使用） ----------
private const val TestDescription = "本片讲述一座城市在病毒爆发后陷入失控，幸存者必须在封锁区中寻找出路。剧情紧凑、节奏明快，是今年最受关注的惊悚作品之一。"
private val TestCast = listOf(
    "扎克·克雷格" to "导演",
    "奥斯汀·艾布拉姆斯" to "Bryan",
    "保罗·沃尔特·豪瑟" to "Paul",
    "卡莉·瑞斯" to "Maxine",
    "扎克·切利" to "Carl"
)
private val TestTags = listOf("恐怖", "科幻", "冒险")
// 演职人员无照片时的缺省头像底色：低饱和莫兰迪色系，按姓名 hash 稳定分配
private val MorandiPalette = listOf(
    Color(0xFF9FA8A3),
    Color(0xFFA9A2B5),
    Color(0xFFB6A79B),
    Color(0xFF9FAFBE),
    Color(0xFFBEA9A9),
    Color(0xFFAEB29A),
    Color(0xFFA3B8B5),
    Color(0xFFB5AEA0)
)
private fun avatarColor(name: String): Color {
    val size = MorandiPalette.size
    val idx = (name.hashCode() % size + size) % size
    return MorandiPalette[idx]
}
private val TestTrailers = listOf("Official Trailer", "Official Teaser", "幕后花絮")
/** Hero 元信息：年份 / 分类 / 集数 / 评分，颜色随白遮罩进度由白变黑 */
@Composable
private fun HeroMetaRow(
    data: VideoDetailData,
    route: VideoRoute,
    textColor: Color,
    subTextColor: Color
) {
    val cleanYear = remember(data.year) {
        val y = data.year.filter { it.isDigit() }.take(4).toIntOrNull()
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (y != null && y in 1900..(currentYear + 1)) y.toString() else ""
    }
    val rating = remember(data.rating) {
        val v = data.rating.trim()
        if ((v.toDoubleOrNull() ?: 0.0) > 0.0) v else null
    }
    val subtitle = remember(data.subtitle, route.episodes) {
        if (data.subtitle.isNotBlank() && data.subtitle != "更新至最新") data.subtitle
        else if (route.episodes.size > 1) "共 ${route.episodes.size} 集" else "正片"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOfNotNull(
            cleanYear.takeIf { it.isNotEmpty() },
            data.category.takeIf { it.isNotBlank() && it != "影视" },
            subtitle
        ).forEachIndexed { index, item ->
            if (index > 0) Text("·", color = subTextColor, style = MaterialTheme.typography.bodySmall)
            Text(item, color = textColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (rating != null) {
            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF5A623), modifier = Modifier.size(14.dp))
            Text(rating, color = textColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 统一分区标题：近黑、16sp、左对齐 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = LocalBTextColors.current.primary
    )
}

/** M3 原生操作项：黑色图标与文字 */
@Composable
private fun DetailAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = LocalBTextColors.current.primary
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) LocalBTextColors.current.primary else LocalBTextColors.current.secondary
        )
    }
}

/** 预告片横向卡片：测试数据占位，带居中播放三角 */
@Composable
private fun TrailerRow() {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(TestTrailers) { title ->
            Column(modifier = Modifier.width(220.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A2F3A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(title, color = LocalBTextColors.current.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text("测试数据占位，后续接入刮削",
        style = MaterialTheme.typography.labelSmall, color = LocalBTextColors.current.secondary)
}

/** 剧照网格：测试数据占位 */
@Composable
private fun StillsPlaceholder() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Color(0xFFDDE1E9), Color(0xFFCFD5E0), Color(0xFFE4E7EE)).forEach { tint ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint)
            )
        }
    }
}

/** 演职人员：真实姓名优先，不足用测试数据补齐；圆形头像 + 黑字姓名 + 深灰角色 */
@Composable
private fun CastRow(data: VideoDetailData) {
    val members = remember(data.director, data.actor) {
        val real = buildList {
            data.director.split(',', '/', '，', '、', ' ')
                .map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it to "导演") }
            data.actor.split(',', '/', '，', '、', ' ')
                .map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it to "演员") }
        }
        (real + TestCast).distinctBy { it.first }.take(5)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(members) { (name, role) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
                Surface(
                    color = avatarColor(name),
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = name.take(1),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            // 头像底是固定的浅莫兰迪色，字必须固定近黑，不能跟 B 层动态字色走
                            color = BTextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalBTextColors.current.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalBTextColors.current.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 中性胶囊：全部取 B 层文字色系，不引入任何主题色。
 *
 * 原来用 M3 FilterChip 的默认选中色（secondaryContainer，那抹粉紫）和 AssistChip 的白底，
 * 在一整页黑白灰里非常扎眼 —— 用户 14:45 要求「用演员名字那种取色方案」。
 * 未选中：透明底 + 极淡描边 + 次字色；选中：主字色 10% 底 + 主字色描边 + 主字色加粗。
 */
@Composable
private fun NeutralChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalBTextColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) c.primary.copy(alpha = 0.45f) else c.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = if (selected) c.primary else c.secondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** 选集：中性胶囊，选中只靠描边与字重区分，不铺彩色底 */
@Composable
private fun EpisodeChips(episodes: List<VideoEpisode>, selectedId: Int, onClick: (VideoEpisode) -> Unit) {
    if (episodes.isEmpty()) {
        Text("暂无选集", style = MaterialTheme.typography.bodyMedium, color = LocalBTextColors.current.secondary)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes) { ep ->
            NeutralChip(
                text = formatEpisodeName(ep.name),
                selected = ep.episodeId == selectedId,
                onClick = { onClick(ep) }
            )
        }
    }
}

/**
 * 播放源真实名称：chip 与「正在尝试 X」提示共用这一个函数，逐字一致。
 * 占位数据/旧缓存没带站点名时，用当前激活站点补前缀（否则只会显示 FEIFAN 这种裸标识）。
 */
private fun routeDisplayName(route: VideoRoute, fallbackSiteName: String): String {
    val labeled = if (route.siteName.isBlank() && fallbackSiteName.isNotBlank()) {
        route.copy(siteName = fallbackSiteName)
    } else route
    return labeled.displayName()
}

/** 播放源（线路）：横向滚动，跨站聚合后常有 5+ 条，普通 Row 会把超屏的 chip 裁掉 */
@Composable
private fun RouteChips(routes: List<VideoRoute>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val fallbackSiteName = remember { TvBoxRepository.selectedSite.value?.name.orEmpty() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(routes) { index, route ->
            NeutralChip(
                text = routeDisplayName(route, fallbackSiteName),
                selected = selectedIndex == index,
                onClick = { onSelect(index) }
            )
        }
    }
}

/** 分类标签：与选集/线路同一套中性取色 */
@Composable
private fun TagChips(tags: List<String>) {
    val clean = remember(tags) {
        (tags.filter { it.isNotBlank() } + TestTags).distinct().take(5)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        clean.forEach { tag ->
            NeutralChip(text = tag, selected = false, onClick = {})
        }
    }
}

/** 智能清洗剧集名（20261030先导片 → 10-30 先导片） */
private fun formatEpisodeName(rawName: String): String {
    val trimmed = rawName.trim()
    val match = Regex("""(\d{4})?(\d{2})(\d{2})(.+)""").find(trimmed)
    return if (match != null) {
        "${match.groupValues[2]}-${match.groupValues[3]} ${match.groupValues[4].trim()}"
    } else {
        trimmed
    }
}

// ---------- 交错式入场（Staggered Entrance） ----------
// 组数即 B 层视觉顺序：片名区、简介、播放资源、演职人员、分类、预告片、剧照、操作行。
private const val EntranceGroupCount = 8
/** 共享元素海报转场 280ms，在它跑到约 72% 时文字接力，形成视觉接续而不是各做各的 */
private const val EntranceStartDelayMs = 200f
/** 单组淡入 + 上浮的时长 */
private const val EntranceRiseDurationMs = 320f
/** 相邻两组的启动间隔：首屏可见的四组之间层次最明显 */
private const val EntranceStaggerMs = 40f
/** 整段编排总时长（不含起始延迟）= 320 + 40*7 = 600ms */
private val EntranceTotalMs = EntranceRiseDurationMs + EntranceStaggerMs * (EntranceGroupCount - 1)

/**
 * 某组在编排时钟 [elapsedMs]（毫秒，从入场开始计）下的进度 0→1。
 * 组内用 easeOutCubic 收尾：起步跟手、末段减速落定，比线性更有物理惯性。
 */
private fun groupProgress(elapsedMs: Float, order: Int): Float {
    val start = order * EntranceStaggerMs
    val linear = ((elapsedMs - start) / EntranceRiseDurationMs).coerceIn(0f, 1f)
    val inv = 1f - linear
    return 1f - inv * inv * inv
}

/**
 * 入场动势：透明度 0→1，同时自下而上位移 [rise]，与海报的放大飞入形成空间接续。
 * 时钟用 lambda 而非值传入，在 graphicsLayer 的绘制阶段读取 —— 动画每帧只重绘，不触发重组。
 */
private fun Modifier.entranceMotion(elapsed: () -> Float, order: Int, rise: Dp = 34.dp): Modifier =
    this.graphicsLayer {
        val p = groupProgress(elapsed(), order)
        alpha = p
        translationY = (1f - p) * rise.toPx()
    }

package com.damon.mobilebox.ui.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.toLoadableImageUrl
import com.damon.mobilebox.ui.components.rememberRetryableImageRequest
import com.damon.mobilebox.ui.components.detailHeroBandBottomFraction
import com.damon.mobilebox.ui.components.rememberPosterTone
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    onPlayEpisode: (VideoEpisode) -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val heroHeight = (configuration.screenHeightDp.dp * 0.82f).coerceIn(420.dp, 640.dp)
    val heroHeightPx = with(density) { heroHeight.toPx() }

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

    val scrollState = rememberScrollState()

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
    LaunchedEffect(Unit) {
        // 用户一上手滑动就立刻收尾，避免出现"边滚边飘"的错位。
        // 判据必须是位移量而不是 isScrollInProgress：页面刚组合时后者会瞬间为 true，
        // 会把整段入场动画一次性 snap 到终值（实测表现为文字 1 帧全亮，等于没做）。
        launch {
            snapshotFlow { scrollState.value }.first { it > 0 }
            entrance.snapTo(EntranceTotalMs)
        }
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

    // 状态栏图标：深色海报上用浅色图标，背景转白后切成深色图标。
    // 海报顶部本身就偏亮时，起手就直接用深色图标，否则白图标在浅色海报上看不见。
    // 采样结果是外部值，必须作为 remember 的 key，否则 derivedStateOf 的 lambda
    // 会一直捕获首次组合时的旧值（analyzed=false），浅色海报永远不触发。
    val view = LocalView.current
    val insetsController = (view.context as? Activity)?.window
        ?.let { WindowCompat.getInsetsController(it, view) }
    val useDarkStatusIcons by remember(posterTone, lightVeil) {
        derivedStateOf {
            if (lightVeil) {
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
    val currentRoute = finalData.routes.getOrNull(selectedRouteIndex) ?: finalData.routes.first()
    var selectedEpisodeId by remember { mutableIntStateOf(currentRoute.episodes.firstOrNull()?.episodeId ?: 1) }
    var currentPlayingEpisode by remember { mutableStateOf<VideoEpisode?>(null) }
    var isFavorite by remember { mutableStateOf(false) }

    BackHandler {
        if (currentPlayingEpisode != null) currentPlayingEpisode = null else onBack()
    }

    with(sharedTransitionScope) {
        with(animatedVisibilityScope) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                // 1. 层级 A：海报固定底层（零 translationY），不随手势上移
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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
                        AsyncImage(
                            model = rememberRetryableImageRequest(context, loadablePosterUrl),
                            contentDescription = finalData.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
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
                            drawRect(screenSurface.copy(alpha = p))
                        }
                )

                // 3. 层级 B：独立覆盖滚动层
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // 字色：起点由海报亮度决定（浅色海报直接起手深色字），
                    // 终点跟随主题——浅色主题过渡到近黑，深色主题过渡到浅色，滚上去都保持可读。
                    val heroTextColor = lerp(heroTextStart, veilPrimary, veilProgress)
                    val heroSubTextColor = lerp(heroSubTextStart, veilSecondary, veilProgress)
                    val bColors = BTextColors(heroTextColor, heroSubTextColor)
                    CompositionLocalProvider(LocalBTextColors provides bColors) {

                    // B 层首块：与 A 层等高的透明容器，片名/元信息底部对齐，随滚动上移
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heroHeight)
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .entranceMotion({ entrance.value }, order = 0, rise = 30.dp)
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
                                    .entranceMotion({ entrance.value }, order = 1)
                            )
                            Spacer(modifier = Modifier.height(28.dp))

                            // 2. 播放资源：M3 原生按钮 + 选集 / 线路（入场第 3 组）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion({ entrance.value }, order = 2)
                            ) {
                                SectionTitle("播放资源")
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val firstEp = currentRoute.episodes.firstOrNull()
                                        if (firstEp != null) {
                                            currentPlayingEpisode = firstEp
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
                                        selectedEpisodeId = ep.episodeId
                                        currentPlayingEpisode = ep
                                        onPlayEpisode(ep)
                                    }
                                )
                                if (finalData.routes.size > 1) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        finalData.routes.forEachIndexed { index, route ->
                                            FilterChip(
                                                selected = selectedRouteIndex == index,
                                                onClick = { selectedRouteIndex = index },
                                                label = {
                                                    Text("${route.routeName} ${route.tag}", color = LocalBTextColors.current.primary)
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    labelColor = LocalBTextColors.current.primary,
                                                    selectedLabelColor = LocalBTextColors.current.primary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            // ---- 以下 5 组位于首屏之外，等入场跑完再组合（见 offscreenReady）----
                            if (offscreenReady) {

                            // 3. 演职人员（真实数据优先，不足用测试数据补齐，入场第 4 组）
                            Spacer(modifier = Modifier.height(28.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .entranceMotion({ entrance.value }, order = 3)
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
                                    .entranceMotion({ entrance.value }, order = 4)
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
                                    .entranceMotion({ entrance.value }, order = 5)
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
                                    .entranceMotion({ entrance.value }, order = 6)
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
                                    .entranceMotion({ entrance.value }, order = 7),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                DetailAction(
                                    icon = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    label = if (isFavorite) "已追剧" else "追剧",
                                    active = isFavorite,
                                    onClick = {
                                        isFavorite = !isFavorite
                                        onShowToast(if (isFavorite) "已加入追剧清单" else "已取消追剧")
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

                // 5. 播放器层
                currentPlayingEpisode?.let { ep ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .zIndex(10f)
                    ) {
                        PlayerDetailScreen(
                            videoData = finalData,
                            initialEpisode = ep,
                            initialRouteIndex = selectedRouteIndex,
                            onBackClick = { currentPlayingEpisode = null },
                            onShowToast = onShowToast
                        )
                    }
                }
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

/** 选集：M3 FilterChip，文字统一黑色 */
@Composable
private fun EpisodeChips(episodes: List<VideoEpisode>, selectedId: Int, onClick: (VideoEpisode) -> Unit) {
    if (episodes.isEmpty()) {
        Text("暂无选集", style = MaterialTheme.typography.bodyMedium, color = LocalBTextColors.current.secondary)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes) { ep ->
            FilterChip(
                selected = ep.episodeId == selectedId,
                onClick = { onClick(ep) },
                label = { Text(formatEpisodeName(ep.name), color = LocalBTextColors.current.primary) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = LocalBTextColors.current.primary,
                    selectedLabelColor = LocalBTextColors.current.primary
                )
            )
        }
    }
}

/** 分类标签：真实 tags 优先，不足补测试数据；AssistChip 白底黑字 */
@Composable
private fun TagChips(tags: List<String>) {
    val clean = remember(tags) {
        (tags.filter { it.isNotBlank() } + TestTags).distinct().take(5)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        clean.forEach { tag ->
            AssistChip(
                onClick = {},
                label = { Text(tag, color = LocalBTextColors.current.primary) },
                colors = AssistChipDefaults.assistChipColors(labelColor = LocalBTextColors.current.primary)
            )
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

package com.damon.mobilebox.ui.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.toLoadableImageUrl
import com.damon.mobilebox.ui.components.rememberRetryableImageRequest
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute

private val HeroScrim = Color(0xFF080B12)
// B 层统一字色：整屏会随滚动变白，深色文字才可读；不跟随 MaterialTheme 的灰阶/主题蓝
private val BTextPrimary = Color(0xFF111418)
private val BTextSecondary = Color(0xFF5A616B)
/** B 层动态字色：首屏压在深色海报上为白，随整屏变白平滑过渡到近黑 */
data class BTextColors(val primary: Color, val secondary: Color)
val LocalBTextColors = staticCompositionLocalOf { BTextColors(BTextPrimary, BTextSecondary) }

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
 *    Alpha 恒为 1f，内部元素全部不透明，只发生 Y 轴位移，不变淡、不做逐元素渐显。
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

    // 状态栏图标随整屏白遮罩反转：深色海报上用浅色图标，背景转白后切成深色图标。
    // derivedStateOf 返回布尔，只有跨过阈值才重组一次，滚动不会每帧重组。
    val view = LocalView.current
    val insetsController = (view.context as? Activity)?.window
        ?.let { WindowCompat.getInsetsController(it, view) }
    val useDarkStatusIcons by remember {
        derivedStateOf {
            (scrollState.value / (heroHeightPx * 0.65f).coerceAtLeast(1f))
                .coerceIn(0f, 1f) > 0.5f
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


                // 2. 整屏变白层：参考视频 3.0s(深色) -> 4.2s(浅白) 是整屏均匀交叉淡入，
                //    不是白色面板从下往上盖，也不是空间上的透明→白渐变。
                //    全屏纯白矩形 alpha 随滚动 0->1 均匀增长：无面板边界、无渐变起始段、无灰带。
                val screenSurface = MaterialTheme.colorScheme.surface
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
                    // 字色随整屏白遮罩进度由白渐变到黑：首屏压在深色海报上可读，
                    // 滚到浅色内容区也不会出现「白底白字」。
                    val veilProgress by remember {
                        derivedStateOf {
                            (scrollState.value / (heroHeightPx * 0.65f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        }
                    }
                    val heroTextColor = lerp(Color.White, BTextPrimary, veilProgress)
                    val heroSubTextColor = lerp(Color.White.copy(alpha = 0.9f), BTextSecondary, veilProgress)
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
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(28.dp))

                            // 2. 播放资源：M3 原生按钮 + 选集 / 线路
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
                                    containerColor = Color(0xFFE4E7EE),
                                    contentColor = LocalBTextColors.current.primary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = LocalBTextColors.current.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "立即播放 · ${
                                        currentRoute.episodes.firstOrNull()?.let { formatEpisodeName(it.name) } ?: "第 1 集"
                                    }",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalBTextColors.current.primary
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

                            Spacer(modifier = Modifier.height(28.dp))

                            // 3. 演职人员（真实数据优先，不足用测试数据补齐）
                            SectionTitle("演职人员")
                            Spacer(modifier = Modifier.height(12.dp))
                            CastRow(data = finalData)

                            Spacer(modifier = Modifier.height(28.dp))

                            // 4. 分类（测试数据标签，M3 AssistChip 白底黑字）
                            SectionTitle("分类")
                            Spacer(modifier = Modifier.height(12.dp))
                            TagChips(tags = finalData.tags)

                            Spacer(modifier = Modifier.height(28.dp))

                            // 5. 预告片（测试数据占位卡）
                            SectionTitle("预告片")
                            Spacer(modifier = Modifier.height(12.dp))
                            TrailerRow()

                            Spacer(modifier = Modifier.height(28.dp))

                            // 6. 剧照（测试数据占位网格）
                            SectionTitle("剧照")
                            Spacer(modifier = Modifier.height(12.dp))
                            StillsPlaceholder()

                            Spacer(modifier = Modifier.height(28.dp))

                            // 7. 操作项：M3 原生 IconButton，黑色图标与文字，无悬浮层
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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

                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                    }
                }

                // 4. 顶部栏：只保留 M3 原生返回按钮。
                // 片名属于 B 层、随滚动一起移动，顶栏再补一份会出现同屏两个片名。
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (currentPlayingEpisode != null) currentPlayingEpisode = null else onBack()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = Color.White
                    ),
                    modifier = Modifier.align(Alignment.TopCenter)
                )

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
                            color = LocalBTextColors.current.primary
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

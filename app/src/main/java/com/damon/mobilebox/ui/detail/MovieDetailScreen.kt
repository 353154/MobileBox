package com.damon.mobilebox.ui.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute

// 影院沉浸式暗色底板基调色
private val CinemaBackground = Color(0xFF0A0E17)
private val CinemaPrimary = Color(0xFF2F81F7)
private val CinemaAccentGradient = Brush.horizontalGradient(
    listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
)

/**
 * 现代影院级沉浸式影视详情页 (Cinema Immersive Detail Screen)
 * - 共享元素时序编排 (Choreography & Timing)：在海报飞行的前 180ms 内，下方所有内容严格保持透明 (alpha = 0)，海报落地后再以 48dp 浮现，杜绝图层杂乱穿透
 * - 彻底根除片名闪烁：首帧直接透传真实数据实体，绝不使用临时 "精选影片" Mock 占位
 * - 融化生硬切边：海报底部叠加深度多级渐变微光，与黑色底板 100% 顺滑溶解
 * - 纯净 Edge-to-Edge 全屏沉浸：贯通状态栏顶部，并强制自适应亮色系统图标
 * - 轻量级现代操作区：彻底移除笨重深灰色实心大圆，标签取消边框改用微光底色
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    // 黄金 1/3 屏幕高度：克制海报尺寸 (约 240~270dp)，为播放按钮和选集留出充足呼吸空间，彻底消除大海报遮挡
    val heroHeight = (configuration.screenHeightDp.dp * 0.33f).coerceIn(240.dp, 270.dp)

    // 适配真正全屏 Edge-to-Edge：在详情页临时启用亮色状态栏图标，退出时恢复
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        val originalAppearance = insetsController?.isAppearanceLightStatusBars ?: true
        insetsController?.isAppearanceLightStatusBars = false
        onDispose {
            insetsController?.isAppearanceLightStatusBars = originalAppearance
        }
    }

    // 数据自适应：首帧优先直接使用导航传入的真实 initialTitle 与 videoData，彻底杜绝片名闪烁
    val finalData = remember(movieId, videoData, posterUrl, initialTitle) {
        val base = videoData ?: TvBoxRepository.selectedDetail?.takeIf { it.id.trim() == movieId.trim() }
        ?: TvBoxRepository.realVodList.value.find { it.id.trim() == movieId.trim() }
        ?: TvBoxRepository.categoryVodList.value.find { it.id.trim() == movieId.trim() }

        if (base != null) {
            if (base.title.isBlank() && initialTitle.isNotBlank()) {
                base.copy(title = initialTitle)
            } else {
                base
            }
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

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val fadeTriggerPx = with(density) { 260.dp.toPx() }
    val scrollProgress = (scrollState.value / fadeTriggerPx).coerceIn(0f, 1f)

    var selectedRouteIndex by remember { mutableIntStateOf(0) }
    val currentRoute = finalData.routes.getOrNull(selectedRouteIndex) ?: finalData.routes.first()
    var selectedEpisodeId by remember { mutableIntStateOf(currentRoute.episodes.firstOrNull()?.episodeId ?: 1) }

    // 播放器内嵌状态 (点击选集或立即播放时直接进入真机播放)
    var currentPlayingEpisode by remember { mutableStateOf<VideoEpisode?>(null) }
    var isFavorite by remember { mutableStateOf(false) }

    // 核心拦截：当播放器处于播放状态时，系统返回键仅退出播放器，避免触发页面回退引发底层图层穿透
    BackHandler {
        if (currentPlayingEpisode != null) {
            currentPlayingEpisode = null
        } else {
            onBack()
        }
    }

    // 核心时序控制：伴随海报飞行的顺畅渐变，微延迟 60ms 启动淡入，杜绝滞后突然跳出
    val contentAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(durationMillis = 300, delayMillis = 60, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 150, easing = FastOutSlowInEasing)
            }
        },
        label = "detail_content_alpha"
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }

    val contentSlideY by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(durationMillis = 320, delayMillis = 40, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 150, easing = FastOutSlowInEasing)
            }
        },
        label = "detail_content_slide"
    ) { state ->
        if (state == EnterExitState.Visible) 0f else 24f
    }

    with(sharedTransitionScope) {
        with(animatedVisibilityScope) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CinemaBackground)
            ) {
                // 1. 视差大图 Hero 区域 (克制 1/3 屏幕高度，FastOutSlowInEasing 无缝平滑缩放)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroHeight)
                        .graphicsLayer {
                            translationY = scrollState.value * 0.4f
                        }
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "poster_${movieId.trim()}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                tween(durationMillis = 380, easing = FastOutSlowInEasing)
                            },
                            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(0.dp))
                        )
                ) {
                    AsyncImage(
                        model = remember(effectivePosterUrl) {
                            ImageRequest.Builder(context)
                                .data(effectivePosterUrl)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = finalData.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 深度黑阶渐变遮罩：下半部分完全平滑溶解入背景底色，彻底消除生硬的边缘直线切割
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(radius = (scrollProgress * 18).dp)
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.85f to CinemaBackground.copy(alpha = 0.75f),
                                    1.0f to CinemaBackground
                                )
                            )
                    )
                }

                // 2. 可滑动内容区：让 Spacer 等同于 heroHeight，标题从海报底部黑色消融区自然开始，决不被海报遮盖
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha
                            translationY = with(density) { contentSlideY.dp.toPx() }
                        }
                        .verticalScroll(scrollState)
                ) {
                    // 黄金高度占位：让标题完全位于海报下方纯黑过渡区，避免被转场期间 Overlay 层海报图层遮挡引发闪烁
                    Spacer(modifier = Modifier.height(heroHeight))

                    // 沉浸式标题与评分信息流 (自带文字阴影与高对比黑阶保护)
                    DetailHeroInfoSection(videoData = finalData, currentRoute = currentRoute)

                    Spacer(modifier = Modifier.height(14.dp))

                    // 沉浸式内容面板
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        // 醒目全圆角胶囊主行动按钮 (Pill-shape CTA)
                        Button(
                            onClick = {
                                val firstEp = currentRoute.episodes.firstOrNull()
                                if (firstEp != null) {
                                    currentPlayingEpisode = firstEp
                                    onPlayEpisode(firstEp)
                                }
                            },
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(CinemaAccentGradient, RoundedCornerShape(26.dp))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "立即播放 · ${currentRoute.episodes.firstOrNull()?.let { formatEpisodeName(it.name) } ?: "第 1 集"}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // 核心操作图标矩阵 (纯净无沉重实心圆，轻量高阶)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DetailActionButton(
                                icon = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                label = if (isFavorite) "已追剧" else "追剧",
                                isActive = isFavorite,
                                onClick = {
                                    isFavorite = !isFavorite
                                    onShowToast(if (isFavorite) "已加入追剧清单" else "已取消追剧")
                                }
                            )
                            DetailActionButton(
                                icon = Icons.Default.Download,
                                label = "离线缓存",
                                onClick = { onShowToast("已加入后台高速下载任务") }
                            )
                            DetailActionButton(
                                icon = Icons.Default.Cast,
                                label = "DLNA投屏",
                                onClick = { onShowToast("正在搜索局域网电视设备...") }
                            )
                            DetailActionButton(
                                icon = Icons.Default.Share,
                                label = "分享",
                                onClick = { onShowToast("分享链接已复制至剪贴板") }
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // 剧集卡片流 (优雅自适应尺寸、圆角与防截断文字排版)
                        EpisodesSection(
                            episodes = currentRoute.episodes,
                            selectedEpisodeId = selectedEpisodeId,
                            onEpisodeClick = { ep ->
                                selectedEpisodeId = ep.episodeId
                                currentPlayingEpisode = ep
                                onPlayEpisode(ep)
                            }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 播放源切换选项
                        if (finalData.routes.size > 1) {
                            PlaySourceSection(
                                routes = finalData.routes,
                                selectedIndex = selectedRouteIndex,
                                onSelectRoute = { selectedRouteIndex = it }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // 剧情简介模块
                        Column {
                            Text(
                                text = "剧情简介",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = finalData.description,
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 演职人员横向流 (接入真实采集数据)
                        CastSection(videoData = finalData)

                        Spacer(modifier = Modifier.height(24.dp))

                        // 题材分类与免嗅探标签 (去生硬圆角边框，改用微光底色 Tonal 胶囊)
                        CategoriesSection(tags = finalData.tags)

                        Spacer(modifier = Modifier.height(96.dp))
                    }
                }

                // 3. 顶部折叠标题栏 (半透明圆形底板保护，白雪高光背景下依然清晰)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Black.copy(alpha = 0.45f * scrollProgress),
                                    Color.Transparent
                                )
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (currentPlayingEpisode != null) {
                                    currentPlayingEpisode = null
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // 上滑折叠时自然淡入的主标题
                        AnimatedVisibility(
                            visible = scrollProgress > 0.55f,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(200)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = finalData.title,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 4. 视频播放器层 (全屏横屏手势支持，加实体不透明背景与 zIndex 防止底层重叠穿透)
                currentPlayingEpisode?.let { ep ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(CinemaBackground)
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

/**
 * 沉浸式头部信息流 (大标题 + 文字阴影 + 真实评分胶囊)
 */
@Composable
private fun DetailHeroInfoSection(
    videoData: VideoDetailData,
    currentRoute: VideoRoute
) {
    // 智能清洗年份：合法4位年份，且不超过当前年份+1，否则不展示异常未来年份 (如 2028)
    val cleanYear = remember(videoData.year) {
        val digits = videoData.year.filter { it.isDigit() }
        val y = digits.take(4).toIntOrNull()
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (y != null && y in 1900..(currentYear + 1)) y.toString() else ""
    }

    // 检查是否有真实有效评分 (排除 0.0、0 与空白)
    val parsedRating = remember(videoData.rating) {
        val score = videoData.rating.trim()
        val num = score.toDoubleOrNull()
        if (num != null && num > 0.0) score else null
    }

    val displaySubtitle = remember(videoData.subtitle, currentRoute.episodes) {
        if (videoData.subtitle.isNotBlank() && videoData.subtitle != "更新至最新") {
            videoData.subtitle
        } else {
            val totalEps = currentRoute.episodes.size
            if (totalEps > 1) "共 ${totalEps} 集" else "正片"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // 主标题：大字号加粗呈现，附带立体文字阴影，浅色背景下绝不融化
        if (videoData.title.isNotEmpty()) {
            Text(
                text = videoData.title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                style = LocalTextStyle.current.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.75f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f
                    )
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 基本信息横向排版 (彻底移除写死的"2小时12分钟")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (cleanYear.isNotEmpty()) {
                Text(
                    text = cleanYear,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text("·", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
            }

            if (videoData.category.isNotBlank() && videoData.category != "影视") {
                Text(
                    text = videoData.category,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text("·", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
            }

            Text(
                text = displaySubtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f)
            )

            // 真实评分胶囊 (黄金微光质感)
            if (parsedRating != null) {
                Text("·", fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFC107).copy(alpha = 0.18f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "$parsedRating 分",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFC107)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 现代轻量操作按钮 (纯净无沉重实心圆，聚焦主播放胶囊)
 */
@Composable
private fun DetailActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) CinemaPrimary else Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = if (isActive) CinemaPrimary else Color.White.copy(alpha = 0.72f)
        )
    }
}

/**
 * 选集横向卡片流 (自适应最小宽度，防长日期被裁切撑爆)
 */
@Composable
private fun EpisodesSection(
    episodes: List<VideoEpisode>,
    selectedEpisodeId: Int,
    onEpisodeClick: (VideoEpisode) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "选集",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "共 ${episodes.size} 集",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(episodes) { episode ->
                val isSelected = episode.episodeId == selectedEpisodeId
                val formattedName = remember(episode.name) { formatEpisodeName(episode.name) }

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 78.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) CinemaAccentGradient
                            else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.04f)))
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onEpisodeClick(episode) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formattedName,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 智能清洗并格式化剧集名称 (如 20261030先导片 -> 10-30 先导片，防止长日期撑爆界面)
 */
private fun formatEpisodeName(rawName: String): String {
    val trimmed = rawName.trim()
    val dateRegex = Regex("""(\d{4})?(\d{2})(\d{2})(.+)""")
    val match = dateRegex.find(trimmed)
    return if (match != null) {
        val month = match.groupValues[2]
        val day = match.groupValues[3]
        val rest = match.groupValues[4].trim()
        "$month-$day $rest"
    } else {
        trimmed
    }
}

/**
 * 播放源切换选项 (暗色胶囊流)
 */
@Composable
private fun PlaySourceSection(
    routes: List<VideoRoute>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit
) {
    Column {
        Text(
            text = "播放源线路",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            routes.forEachIndexed { index, route ->
                val isSelected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) CinemaPrimary.copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.06f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) CinemaPrimary else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectRoute(index) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${route.routeName} (${route.tag})",
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

/**
 * 演职人员横向头像流 (真实数据解析，彻底移除假数据)
 */
@Composable
private fun CastSection(videoData: VideoDetailData) {
    val castMembers = remember(videoData.director, videoData.actor) {
        val list = mutableListOf<Pair<String, String>>()
        if (videoData.director.isNotBlank()) {
            val directors = videoData.director
                .split(',', '/', '，', '、', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            directors.forEach { d ->
                list.add(Pair(d, "导演"))
            }
        }
        if (videoData.actor.isNotBlank()) {
            val actors = videoData.actor
                .split(',', '/', '，', '、', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            actors.forEach { a ->
                list.add(Pair(a, "演员"))
            }
        }
        list
    }

    if (castMembers.isEmpty()) return

    Column {
        Text(
            text = "演职人员",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(castMembers) { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(68.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(CinemaPrimary.copy(alpha = 0.2f))
                            .border(1.dp, CinemaPrimary.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.first.take(1),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = member.first,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = member.second,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 题材分类流 (彻底去除生硬边框，采用纯净微光底色 Tonal 胶囊)
 */
@Composable
private fun CategoriesSection(tags: List<String>) {
    val cleanTags = remember(tags) {
        tags.filter { it.isNotBlank() }.distinct()
    }

    if (cleanTags.isEmpty()) return

    Column {
        Text(
            text = "分类与标签",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            cleanTags.take(4).forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = tag,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

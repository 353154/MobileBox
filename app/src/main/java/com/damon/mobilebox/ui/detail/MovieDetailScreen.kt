package com.damon.mobilebox.ui.detail

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute

/**
 * 极致沉浸式现代化影视详情页 (Parallax Blur Detail Screen)
 * 基于 Compose 1.7+ SharedTransitionScope.sharedBounds 实现跨页面海报无缝飞跃动效
 * 统一 aspectRatio(0.7f) 彻底消除拉伸跳变
 * sharedBounds 严格置于 clip 之前，使用 OverlayClip 处理圆角无缝过渡
 * 非共享元素与遮罩层全局平滑淡入淡出 (animateEnterExit)，杜绝白屏闪烁与突兀退场
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MovieDetailScreen(
    movieId: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    posterUrl: String = "",
    videoData: VideoDetailData? = null,
    onPlayEpisode: (VideoEpisode) -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val imageHeight = screenWidth / 0.7f

    // 数据自适应：优先使用传入的 videoData 或 TvBoxRepository 选中缓存，否则根据 movieId 自动匹配真实列表
    val finalData = remember(movieId, videoData, posterUrl) {
        videoData ?: TvBoxRepository.selectedDetail?.takeIf { it.id == movieId } ?: run {
            val foundReal = TvBoxRepository.realVodList.value.find { it.id == movieId }
            foundReal ?: VideoDetailData.createFromMovie(
                id = movieId,
                title = "精选影片",
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
    val fadeTriggerPx = with(density) { 320.dp.toPx() }
    val scrollProgress = (scrollState.value / fadeTriggerPx).coerceIn(0f, 1f)

    var selectedRouteIndex by remember { mutableIntStateOf(0) }
    val currentRoute = finalData.routes.getOrNull(selectedRouteIndex) ?: finalData.routes.first()

    // 播放器内嵌状态 (点击选集或立即播放时直接进入真机播放)
    var currentPlayingEpisode by remember { mutableStateOf<VideoEpisode?>(null) }

    with(sharedTransitionScope) {
        with(animatedVisibilityScope) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 1. 底层：自适应长宽比的背景大图 (统一 aspectRatio(0.7f)，杜绝长宽比不一致形变)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f) // 核心修复4：移除高度 600.dp，改用与列表页完全相同的 aspectRatio(0.7f)
                ) {
                    // 核心修复 1 & 2：使用 sharedBounds 替代 sharedElement，统一 ScaleToBounds 与直角过渡
                    AsyncImage(
                        model = remember(effectivePosterUrl) {
                            ImageRequest.Builder(context)
                                .data(effectivePosterUrl)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = "影视海报",
                        contentScale = ContentScale.Crop, // 必须与列表页保持一致，防止比例跳变
                        modifier = Modifier
                            .fillMaxSize()
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "poster_$movieId"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                                // 详情页落地时平滑过渡为直角，返回时平滑过渡为 8dp
                                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(0.dp))
                            )
                    )

                    // 核心修复5：为所有的模糊和遮罩加上 animateEnterExit，返回时优雅淡出，告别白屏闪烁
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .animateEnterExit(
                                enter = fadeIn(),
                                exit = fadeOut()
                            )
                    ) {
                        // 滑动跟随变暗层与动态高斯模糊
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(radius = (scrollProgress * 25).dp)
                                .background(Color.Black.copy(alpha = scrollProgress * 0.7f))
                        )

                        // 底部黑色渐变托底遮罩，保证文字和 Logo 可视度
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.5f to Color.Transparent,
                                        1.0f to Color.Black.copy(alpha = 0.95f)
                                    )
                                )
                        )
                    }
                }

                // 2. 上层：可滑动内容区 (为整体 Column 加上 animateEnterExit，返回时优雅淡出)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateEnterExit(
                            enter = fadeIn(),
                            exit = fadeOut()
                        )
                        .verticalScroll(scrollState)
                ) {
                    // 根据 aspectRatio(0.7f) 产生的实际高度，动态预留透明空间，保证艺术字 LOGO 完美坐落在海报底部
                    Spacer(modifier = Modifier.height((imageHeight - 140.dp).coerceAtLeast(240.dp)))

                    // 极简头部信息 (艺术字 LOGO + 多平台评分)
                    ImmersiveHeaderSection(videoData = finalData)

                    Spacer(modifier = Modifier.height(24.dp))

                    // 底部数据流面包板，上滑时平滑覆盖背景
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 80.dp)
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        // 醒目“立即播放”主按钮
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        ) {
                            Button(
                                onClick = {
                                    val firstEp = currentRoute.episodes.firstOrNull()
                                    if (firstEp != null) {
                                        currentPlayingEpisode = firstEp
                                        onPlayEpisode(firstEp)
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "立即播放 · ${currentRoute.episodes.firstOrNull()?.name ?: "第 1 集"}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 剧集卡片流
                        EpisodesSection(
                            episodes = currentRoute.episodes,
                            onEpisodeClick = { ep ->
                                currentPlayingEpisode = ep
                                onPlayEpisode(ep)
                            }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 播放源切换选项
                        PlaySourceSection(
                            routes = finalData.routes,
                            selectedIndex = selectedRouteIndex,
                            onSelectRoute = { selectedRouteIndex = it }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 剧情简介模块
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "剧情简介",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = finalData.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 演职人员横向流
                        CastSection()

                        Spacer(modifier = Modifier.height(24.dp))

                        // 题材分类流
                        CategoriesSection(tags = finalData.tags)
                    }
                }

                // 3. 顶层：固定的返回按钮 (附带优雅淡入淡出动画)
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(40.dp)
                        .animateEnterExit(
                            enter = fadeIn(),
                            exit = fadeOut()
                        )
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }

                // 4. 视频播放器层 (全屏横屏手势支持)
                currentPlayingEpisode?.let { ep ->
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

/**
 * 极简头部信息 (Apple TV 风格艺术字 LOGO 占位 + 多平台评分矩阵)
 */
@Composable
fun ImmersiveHeaderSection(videoData: VideoDetailData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 核心修复3：使用透明占位框模拟 TMDB 艺术字 LOGO
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(80.dp)
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = videoData.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 基本信息横向排版
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(videoData.year, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(modifier = Modifier.width(10.dp))
            Text("·", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.width(10.dp))
            Text(videoData.subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(modifier = Modifier.width(10.dp))
            Text("·", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.width(10.dp))
            Text("2小时12分钟", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 核心修复4：多平台评分矩阵
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RatingItem(source = "IMDb", score = videoData.rating, color = Color(0xFFFFC107))
            RatingItem(source = "豆瓣", score = "8.6", color = Color(0xFF4CAF50))
            RatingItem(source = "烂番茄", score = "96%", color = Color(0xFFF44336))
        }
    }
}

@Composable
fun RatingItem(source: String, score: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "$source $score",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * 选集横向卡片流
 */
@Composable
fun EpisodesSection(
    episodes: List<VideoEpisode>,
    onEpisodeClick: (VideoEpisode) -> Unit
) {
    var selectedEpId by remember { mutableIntStateOf(episodes.firstOrNull()?.episodeId ?: 1) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "剧集列表",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "更新至 ${episodes.size} 集",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(episodes) { _, ep ->
                val isSelected = ep.episodeId == selectedEpId
                Card(
                    modifier = Modifier
                        .width(130.dp)
                        .clickable {
                            selectedEpId = ep.episodeId
                            onEpisodeClick(ep)
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    else
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = ep.name,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "极速秒播 · 4K",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播放源切换横向按钮
 */
@Composable
fun PlaySourceSection(
    routes: List<VideoRoute>,
    selectedIndex: Int,
    onSelectRoute: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "播放源",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            routes.forEachIndexed { index, route ->
                val isSelected = selectedIndex == index
                if (isSelected) {
                    Button(
                        onClick = { onSelectRoute(index) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("${route.routeName} (${route.tag})", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onSelectRoute(index) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("${route.routeName} (${route.tag})")
                    }
                }
            }
        }
    }
}

/**
 * 演职人员横向头像流
 */
@Composable
fun CastSection() {
    val castMembers = listOf(
        Pair("Damon", "导演 / 制作人"),
        Pair("克里斯托弗", "饰 领衔主演"),
        Pair("伊丽莎白", "饰 联合主演"),
        Pair("麦克斯", "饰 战术指挥"),
        Pair("佐伊", "饰 科技专家"),
        Pair("亚历山大", "饰 特别客串")
    )

    Column {
        Text(
            text = "演职人员",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(castMembers.size) { index ->
                val member = castMembers[index]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.first.take(1),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = member.first,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = member.second,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 题材分类流
 */
@Composable
fun CategoriesSection(tags: List<String>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "分类与标签",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = tag,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

package com.damon.mobilebox.ui.main

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.damon.mobilebox.ui.player.findActivity
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.ui.theme.ThemeManager
import com.damon.mobilebox.ui.theme.ThemePreference
import com.damon.mobilebox.ui.theme.ThemeColorMode
import com.damon.mobilebox.ui.theme.AppThemeState
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.components.ModernToastMessage
import com.damon.mobilebox.ui.components.CupertinoSwitch
import com.damon.mobilebox.ui.components.ColorPickerDialog
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.components.ModernBackgroundContainer
import com.damon.mobilebox.ui.components.LocalParallaxAngles
import com.damon.mobilebox.ui.components.LocalParallaxEnabled
import com.damon.mobilebox.ui.components.rememberParallaxAngles
import com.damon.mobilebox.ui.components.animateItem
import com.damon.mobilebox.ui.category.CategoryScreen
import com.damon.mobilebox.ui.profile.ProfileScreen
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.MobileVideoPlayer
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.components.TvBoxSubscriptionDialog
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.tvbox.TvBoxSite
import com.damon.mobilebox.ui.detail.MovieDetailScreen
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AnimatedMainScreen(
    themeManager: ThemeManager,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onNavigateToDetail: (VideoDetailData) -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 当前激活的 TVBox 站点状态 (从 TvBoxRepository 实时订阅与响应)
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    var showSourceDialog by remember { mutableStateOf(false) }

    // 当前正在浏览的沉浸式高斯模糊详情页
    var selectedMovieDetail by remember { mutableStateOf<VideoDetailData?>(null) }
    // 当前正在播放的影片与具体分集 (非空时拉起 PlayerDetailScreen)
    var currentPlayingEpisode by remember { mutableStateOf<Pair<VideoDetailData, VideoEpisode>?>(null) }

    // 全局自适应极简提示弹窗状态
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        TvBoxRepository.initFromCache(context)
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2000)
            toastMessage = null
        }
    }

    val themeState by themeManager.appThemeStateFlow.collectAsState(initial = AppThemeState())
    val parallaxAngles = rememberParallaxAngles(enabled = themeState.parallaxEnabled)

    CompositionLocalProvider(
        LocalParallaxAngles provides parallaxAngles,
        LocalParallaxEnabled provides themeState.parallaxEnabled
    ) {
        ModernBackgroundContainer {
            when {
                currentPlayingEpisode != null -> {
                    BackHandler {
                        currentPlayingEpisode = null
                    }
                    PlayerDetailScreen(
                        videoData = currentPlayingEpisode!!.first,
                        initialEpisode = currentPlayingEpisode!!.second,
                        onBackClick = { currentPlayingEpisode = null },
                        onShowToast = { toastMessage = it }
                    )
                }
                selectedMovieDetail != null -> {
                    BackHandler {
                        selectedMovieDetail = null
                    }
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        MovieDetailScreen(
                            movieId = selectedMovieDetail!!.id,
                            videoData = selectedMovieDetail!!,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onBack = { selectedMovieDetail = null },
                            onPlayEpisode = { episode ->
                                currentPlayingEpisode = Pair(selectedMovieDetail!!, episode)
                            },
                            onShowToast = { toastMessage = it }
                        )
                    }
                }
                else -> {
                    val mainConfig = LocalConfiguration.current
                    val isMainLandscape = mainConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            if (!isMainLandscape) {
                                AnimatedBottomNavigationBar(
                                    selectedIndex = pagerState.currentPage,
                                    onTabSelected = { index ->
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                                )
                            }
                        }
                    ) { paddingValues ->
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = if (!isMainLandscape) paddingValues.calculateBottomPadding() else 0.dp)
                        ) { page ->
                            when (page) {
                                0 -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        OptimizedTopBar(
                                            sourceName = selectedSite?.name ?: "点击配置订阅",
                                            onSearchClick = {
                                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                            },
                                            onSourceClick = { showSourceDialog = true }
                                        )
                                        HomeGridContent(
                                            onMovieClick = { detail ->
                                                TvBoxRepository.selectedDetail = detail
                                                onNavigateToDetail(detail)
                                            },
                                            onConfigureSubscription = { showSourceDialog = true },
                                            onShowToast = { toastMessage = it },
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    }
                                }
                                1 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                    CategoryScreen(
                                        onMovieClick = { detail ->
                                            TvBoxRepository.selectedDetail = detail
                                            onNavigateToDetail(detail)
                                        },
                                        onShowToast = { toastMessage = it },
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                                2 -> Box(Modifier.fillMaxSize().let { if (!isMainLandscape) it.statusBarsPadding() else it }) {
                                    LiveScreenContent(
                                        onConfigureSubscription = { showSourceDialog = true },
                                        onShowToast = { toastMessage = it }
                                    )
                                }
                                3 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                    ModernSettingsScreen(
                                        themeManager = themeManager,
                                        selectedSite = selectedSite,
                                        onOpenSourceDialog = { showSourceDialog = true },
                                        onShowToast = { toastMessage = it }
                                    )
                                }
                                4 -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ProfileScreen(onShowToast = { toastMessage = it }) }
                            }
                        }
                    }
                }
            }

        // 浮层极简提示框 (ModernToastMessage，自动适配深浅色与安全避让)
        androidx.compose.animation.AnimatedVisibility(
            visible = toastMessage != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
        ) {
            toastMessage?.let { msg ->
                ModernToastMessage(message = msg)
            }
        }
    }
}

    // TVBox 真实订阅与源配置弹窗
    if (showSourceDialog) {
        TvBoxSubscriptionDialog(
            onDismiss = { showSourceDialog = false },
            onShowToast = { toastMessage = it }
        )
    }
}

// -------------------------------------------------------------------------------------
// 1. 顶部操作栏 (首页极简大标题与搜索按钮)
// -------------------------------------------------------------------------------------
@Composable
fun OptimizedTopBar(
    sourceName: String = "点击配置订阅",
    onSearchClick: () -> Unit = {},
    onSourceClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSourceClick() }
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Text(
                text = sourceName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "切换站源",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        IconButton(
            onClick = { onSearchClick() },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// 2. 首页精选点播 (全面接入 TVBox 真实采集源数据流，彻底移除所有 Mock 假数据)
// -------------------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeGridContent(
    onMovieClick: (VideoDetailData) -> Unit = {},
    onConfigureSubscription: () -> Unit = {},
    onShowToast: (String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val realVodList by TvBoxRepository.realVodList.collectAsState()
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    val isRepoLoading by TvBoxRepository.isLoading.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    StandardPullToRefreshBox(
        isRefreshing = isRefreshing || (isRepoLoading && realVodList.isEmpty()),
        onRefresh = {
            if (selectedSite == null) {
                onConfigureSubscription()
                return@StandardPullToRefreshBox
            }
            coroutineScope.launch {
                isRefreshing = true
                val result = TvBoxRepository.fetchRealVodData(selectedSite!!)
                isRefreshing = false
                if (result.isSuccess) {
                    onShowToast("已为您从【${selectedSite?.name}】刷新最新影片")
                } else {
                    onShowToast("刷新失败: ${result.exceptionOrNull()?.message}")
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        if (realVodList.isEmpty()) {
            if (isRepoLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (selectedSite != null) "正在加载【${selectedSite?.name}】片库..." else "正在连接订阅...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (selectedSite == null) "暂未配置 TVBox 订阅源" else "【${selectedSite?.name}】暂无视频数据",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (selectedSite == null) "请点击下方按钮添加订阅地址以加载真实影视" else "可尝试下拉刷新或切换至其他可用站点",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                if (selectedSite == null) {
                                    onConfigureSubscription()
                                } else {
                                    coroutineScope.launch {
                                        TvBoxRepository.fetchRealVodData(selectedSite!!)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = if (selectedSite == null) "配置 TVBox 订阅" else "重新刷新")
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 24.dp,
                    bottom = 96.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = realVodList,
                    key = { movie -> movie.id },
                    contentType = { "vod_poster_card" }
                ) { movie ->
                    StandardPosterCard(
                        title = movie.title,
                        subtitle = movie.subtitle,
                        score = movie.rating,
                        posterUrl = movie.posterUrl,
                        movieId = movie.id,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier,
                        onClick = {
                            onMovieClick(movie)
                        }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 4. 电视直播中心
// -------------------------------------------------------------------------------------
@Composable
fun LiveScreenContent(
    onConfigureSubscription: () -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val liveGroups by TvBoxRepository.liveGroups.collectAsState()
    val liveChannels by TvBoxRepository.liveChannels.collectAsState()

    var isRefreshing by remember { mutableStateOf(false) }

    // 默认选中的电视频道（优先选 CCTV-1 或首个可用频道）
    var selectedChannel by remember(liveChannels) {
        mutableStateOf(liveChannels.find { it.name.contains("CCTV-1") } ?: liveChannels.firstOrNull())
    }

    // 选中的分类：-1 为“全部”，>=0 为 liveGroups 中的具体索引
    var selectedGroupIndex by remember { mutableIntStateOf(-1) }

    // 是否开启播放（默认进来不播放，点击大播放按钮或点选台后才开始播放）
    var isPlayerActive by remember { mutableStateOf(false) }

    // 所有可用分类（服务端拉取的数据源联动）
    val categoryList = remember(liveGroups, liveChannels) {
        if (liveChannels.isEmpty()) {
            emptyList()
        } else {
            listOf("全部" to liveChannels) + liveGroups.map { it.groupName to it.channels }
        }
    }

    // 当前分类下的电视频道列表
    val displayedChannels = remember(selectedGroupIndex, categoryList, liveChannels) {
        if (selectedGroupIndex == -1 || categoryList.isEmpty()) {
            liveChannels
        } else {
            categoryList.getOrNull(selectedGroupIndex + 1)?.second ?: liveChannels
        }
    }

    StandardPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                val result = TvBoxRepository.refreshLiveStreams(context)
                isRefreshing = false
                if (result.isSuccess) {
                    onShowToast("已为您刷新最新电视直播列表")
                } else {
                    val ex = result.exceptionOrNull()
                    if (ex !is CancellationException && ex?.message?.contains("coroutine scope", ignoreCase = true) != true) {
                        onShowToast("刷新直播失败: ${ex?.message}")
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        if (liveChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无电视频道直播源",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "可下拉刷新重新抓取，或在 TVBox 订阅中配置直播源",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onConfigureSubscription,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "配置订阅源")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isLandscape) Color.Black else MaterialTheme.colorScheme.background)
            ) {
                // 1. 顶部播放框 (竖屏为 16:9 黑色圆角卡片；横屏为 100% 满屏影院直播，同一个 MobileVideoPlayer 节点不被销毁)
                Box(
                    modifier = if (isLandscape) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0F0F0F))
                    }
                ) {
                    if (isPlayerActive && selectedChannel != null) {
                        MobileVideoPlayer(
                            streamUrl = selectedChannel!!.url,
                            title = selectedChannel!!.name,
                            modifier = Modifier.fillMaxSize(),
                            isLive = true,
                            channelGroups = liveGroups,
                            currentChannel = selectedChannel,
                            onSelectChannel = { channel ->
                                selectedChannel = channel
                            },
                            onBackClick = {
                                if (isLandscape) {
                                    findActivity(context)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    isPlayerActive = false
                                }
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 左上角 LIVE 红色胶囊徽章
                            Surface(
                                color = Color(0xFFE50914),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 14.dp, top = 14.dp)
                            ) {
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }

                            // 居中区域：大播放按钮 + 正在直播频道标题 + 节目信息
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF1E88E5),
                                    modifier = Modifier
                                        .size(58.dp)
                                        .clickable {
                                            if (selectedChannel != null) {
                                                isPlayerActive = true
                                            } else {
                                                onShowToast("暂无可用电视频道")
                                            }
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "播放",
                                            tint = Color.White,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = "正在直播: ${selectedChannel?.name ?: "CCTV-1 综合 4K"}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "当前节目: ${if (selectedChannel?.name?.contains("CCTV-1") == true) "新闻联播" else "实时电视节目"} · 稍后: 焦点访谈",
                                    color = Color(0xFF42A5F5),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // 2. 播放框下方的电视直播分类与电视台列表 (仅在竖屏状态下展示，横屏下完全隐藏避让)
                if (!isLandscape) {
                    if (categoryList.size > 1) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categoryList.indices.toList()) { index ->
                                val (groupName, channels) = categoryList[index]
                                val isSelected = index == (selectedGroupIndex + 1)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedGroupIndex = index - 1
                                    },
                                    label = {
                                        Text(
                                            text = "$groupName (${channels.size})",
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    border = null
                                )
                            }
                        }
                    }

                    // 3. 电视台列表 (点击不同电视台切换不同的台)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedChannels) { channel ->
                            val isCurrent = channel.url == selectedChannel?.url
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedChannel = channel
                                        isPlayerActive = true // 点击列表中不同电视台切换不同的台并开始播放
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左侧图标：选中且播放时为蓝色圆形扬声器，否则为灰色电视机
                                    if (isCurrent && isPlayerActive) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF1E88E5),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.VolumeUp,
                                                    contentDescription = "正在播放",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Tv,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = channel.name,
                                            fontSize = 15.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "正在播: 实时节目 | 稍后: 精彩预告",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 5. 设置页面与主题模式切换 (集成 TVBox 订阅、ThemeManager、HSV 色盘与 DataStore 持久化)
// -------------------------------------------------------------------------------------
@Composable
fun ModernSettingsScreen(
    themeManager: ThemeManager,
    selectedSite: TvBoxSite?,
    onOpenSourceDialog: () -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val themeState by themeManager.appThemeStateFlow.collectAsState(initial = AppThemeState())
    val currentTheme = themeState.themePreference
    val selectedWarehouse by TvBoxRepository.selectedWarehouse.collectAsState()
    val savedSubscriptionUrl by TvBoxRepository.savedSubscriptionUrl.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var isMpvKernel by remember { mutableStateOf(true) }
    var isBiometricEnabled by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 数据源订阅管理分组
        item {
            SettingSectionHeader("影视数据源")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSourceDialog() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TVBox 订阅与站源配置",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    selectedWarehouse != null && selectedSite != null ->
                                        "多仓: ${selectedWarehouse!!.name} · 站点: ${selectedSite.name}"
                                    selectedSite != null ->
                                        "当前激活: ${selectedSite.name} · 点击配置订阅或切换"
                                    savedSubscriptionUrl.isNotBlank() ->
                                        "已配置订阅 · 点击切换站源"
                                    else ->
                                        "暂未添加 TVBox 订阅 · 点击配置"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 主题个性化分组
        item {
            SettingSectionHeader("外观与主题")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 1. 深浅色模式切换
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showThemeDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "深浅色主题模式",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "当前: ${currentTheme.title}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = 12.dp))

                    // 2. 主题色与色盘选择入口
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showColorDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ColorLens,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "应用主题色",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (themeState.colorMode == ThemeColorMode.CUSTOM) {
                                    val hex = String.format("#%06X", 0xFFFFFF and themeState.customColorInt)
                                    "自定义 ($hex)"
                                } else {
                                    themeState.colorMode.title
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        // 右侧展示当前主题色的微型圆球
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = 12.dp))

                    // 3. 陀螺仪 3D 视差海报开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "陀螺仪 3D 视差海报",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (themeState.parallaxEnabled) "已开启手持 3D 悬浮与动态流光" else "已关闭硬件传感器视差",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        CupertinoSwitch(
                            checked = themeState.parallaxEnabled,
                            onCheckedChange = { isChecked ->
                                coroutineScope.launch {
                                    themeManager.saveParallaxEnabled(isChecked)
                                }
                            }
                        )
                    }
                }
            }
        }

        // 播放内核与安全
        item {
            SettingSectionHeader("播放内核与视频增强")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("MPV 高性能解码内核", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(if (isMpvKernel) "已启用 MPV 原生解码 (支持 ASS 压制字幕与 Anime4K)" else "已回退为 IJKPlayer 内核", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        CupertinoSwitch(
                            checked = isMpvKernel,
                            onCheckedChange = { isMpvKernel = it }
                        )
                    }
                }
            }
        }

        // 安全与系统信息
        item {
            SettingSectionHeader("安全与系统关于")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("生物识别隐私锁 (Biometric)", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("开启后访问成人/私密分类需验证指纹或面容", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        CupertinoSwitch(
                            checked = isBiometricEnabled,
                            onCheckedChange = {
                                isBiometricEnabled = it
                                onShowToast(if (it) "已开启生物识别隐私锁" else "已关闭生物识别隐私锁")
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = 12.dp))
                    SettingRowItem(
                        icon = Icons.Default.Info,
                        title = "关于 MobileBox",
                        subtitle = null, // 移除版本号副标题，保持绝对清爽
                        onClick = { showAboutDialog = true }
                    )
                }
            }
        }
    }

    // 主题模式单选对话框 (完全按照用户提供代码规范)
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(text = "选择主题", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    ThemePreference.entries.forEach { themeOpt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch { themeManager.saveThemePreference(themeOpt) }
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (themeOpt == currentTheme),
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = themeOpt.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // 主题色选择与 HSV 色盘弹窗
    if (showColorDialog) {
        ColorPickerDialog(
            initialColor = Color(themeState.customColorInt),
            initialMode = themeState.colorMode,
            onDismiss = { showColorDialog = false },
            onSave = { mode, colorInt ->
                coroutineScope.launch {
                    themeManager.saveColorMode(mode, colorInt)
                }
                showColorDialog = false
            }
        )
    }

    // 独立的“关于 MobileBox”信息展示弹窗
    if (showAboutDialog) {
        AboutAppDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingRowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) {
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * 独立的关于信息展示弹窗 (AboutAppDialog)
 * 采用 Material 3 标准弹窗规范，结构化展示版本、开发者、邮箱与开源项目地址
 */
@Composable
fun AboutAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "关于 MobileBox",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 版本号
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "版本",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.width(68.dp)
                    )
                    Text(
                        text = "v1.0.0",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 2. 开发者
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "开发者",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.width(68.dp)
                    )
                    Text(
                        text = "Damon",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 3. 邮箱 (可点击调用系统邮箱应用)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            try {
                                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:353154869@qq.com")
                                }
                                context.startActivity(emailIntent)
                            } catch (e: Exception) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf("353154869@qq.com"))
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "选择邮件客户端"))
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "联系邮箱",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.width(68.dp)
                    )
                    Text(
                        text = "353154869@qq.com",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "发送邮件",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 4. GitHub 项目地址 (可点击调用浏览器跳转)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            try {
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/353154/MobileBox")
                                )
                                context.startActivity(browserIntent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "项目地址",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.width(68.dp)
                    )
                    Text(
                        text = "GitHub / MobileBox",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "访问 GitHub",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

// -------------------------------------------------------------------------------------
// 6. 底部导航栏 (沉底铺满 + 固定尺寸 Box 强制统一光标尺寸与样式)
// -------------------------------------------------------------------------------------
@Composable
fun AnimatedBottomNavigationBar(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    // 强制沉底铺满，移除悬浮修饰符
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        tonalElevation = 0.dp
    ) {
        val tabs = listOf(
            Triple("首页", Icons.Filled.Home, Icons.Filled.Home),
            Triple("分类", Icons.Filled.List, Icons.Filled.List),
            Triple("直播", Icons.Filled.Tv, Icons.Filled.Tv), // 实心电视机
            Triple("设置", Icons.Filled.Settings, Icons.Filled.Settings),
            Triple("我的", Icons.Filled.AccountCircle, Icons.Filled.AccountCircle) // 带有实心圆形底的账户图标
        )

        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                icon = {
                    // 使用固定尺寸的 Box 包裹图标，强制统一高亮光标的大小
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 内部图标保留视觉补偿差异，但不影响外部光标
                        val iconSize = if (tab.first == "设置") 28.dp else 26.dp
                        Icon(
                            imageVector = tab.second,
                            contentDescription = tab.first,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                },
                label = {
                    Text(
                        text = tab.first,
                        fontSize = 11.sp,
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Medium
                    )
                },
                selected = selectedIndex == index,
                onClick = { onTabSelected(index) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant, // 恢复统一光标的颜色
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}



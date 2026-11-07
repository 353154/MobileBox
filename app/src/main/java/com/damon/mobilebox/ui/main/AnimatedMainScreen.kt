package com.damon.mobilebox.ui.main

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.LiveTv
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.damon.mobilebox.ui.player.findActivity
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.ui.theme.ThemeManager
import com.damon.mobilebox.ui.theme.ThemePreference
import com.damon.mobilebox.ui.theme.ThemeColorMode
import com.damon.mobilebox.ui.theme.AppThemeState
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.components.NavGlyph
import com.damon.mobilebox.ui.components.NavGlyphIcon
import com.damon.mobilebox.ui.components.ModernToastMessage
import com.damon.mobilebox.ui.components.CupertinoSwitch
import com.damon.mobilebox.ui.components.ColorPickerDialog
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.components.ModernBackgroundContainer
import com.damon.mobilebox.ui.components.LocalParallaxAngles
import com.damon.mobilebox.ui.components.LocalParallaxEnabled
import com.damon.mobilebox.ui.components.rememberParallaxAngles
import com.damon.mobilebox.ui.components.animateItem
import com.damon.mobilebox.ui.components.horizontalScrollFadingEdge
import com.damon.mobilebox.ui.components.LiveStreamPreview
import com.damon.mobilebox.ui.components.BingWallpaperBackdrop
import com.damon.mobilebox.ui.components.BingWallpaperStore
import com.damon.mobilebox.ui.components.GlassControlButton
import com.damon.mobilebox.ui.category.CategoryScreen
import com.damon.mobilebox.ui.profile.ProfileScreen
import com.damon.mobilebox.ui.profile.WatchListType
import com.damon.mobilebox.ui.player.PlayerDetailScreen
import com.damon.mobilebox.ui.player.MobileVideoPlayer
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.components.TvBoxSubscriptionDialog
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.tvbox.EpgPrograms
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
    onNavigateToDetail: (VideoDetailData) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchList: (WatchListType) -> Unit = {}
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
        // 客户端启动即后台拉取 Bing 壁纸并更新本地缓存（只有成功才替换）
        BingWallpaperStore.prefetch(context)
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2000)
            toastMessage = null
        }
    }

    val themeState by themeManager.appThemeStateFlow.collectAsState()
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
                                            // 直接定帧跳转，禁止水平滚动动画。
                                            // animateScrollToPage 会让 Pager 物理滚过中间所有页面：
                                            // 跨 Tab 点击时会串场闪过无关页面、两页同时露出一条竖向接缝，
                                            // 且 currentPage 沿途逐格变化导致底部胶囊“跑马灯”式跳变。
                                            // 本 Pager 已关闭用户滑动，底部导航语义下应为瞬时切换。
                                            pagerState.scrollToPage(index)
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
                                            onSearchClick = onNavigateToSearch,
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
                                4 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                    ProfileScreen(onOpenList = onNavigateToWatchList)
                                }
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
        // 仓库首屏加载由内容区自己的「正在加载片库」态呈现，不映射成下拉刷新态，
        // 否则容器指示器会与页面中央的加载圈同时出现（双进度圈）。
        isRefreshing = isRefreshing,
        onRefresh = {
            if (isRepoLoading) return@StandardPullToRefreshBox
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

    // 是否开启播放：唯一入口是中间的大播放按钮，选台不会触发播放
    var isPlayerActive by remember { mutableStateOf(false) }

    val epgPrograms by TvBoxRepository.epgPrograms.collectAsState()
    // 模板就绪是异步的：作为查询触发条件之一，避免 initFromCache 完成前组合导致的漏查
    val epgTemplate by TvBoxRepository.epgTemplate.collectAsState()
    // 节目按时间推进，每分钟重算一次「正在播 / 稍后」，无需重新拉取 EPG
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }
    // 顶部当前选中的频道也触发一次节目单查询（列表可能尚未滚到它）
    LaunchedEffect(selectedChannel?.name, epgTemplate) {
        selectedChannel?.let { TvBoxRepository.ensureChannelEpg(it) }
    }

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
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F0F0F))
                    }
                ) {
                    // 静音模糊实时预览：进入页面 / 换台即预拉当前频道。
                    // 声明在正式播放器之前（下层），点击播放后正式画面立即盖上来，
                    // 不会出现「点了按钮还被模糊层挡 400ms」的迟滞感。
                    LiveBlurPreview(
                        visible = !isPlayerActive,
                        streamUrl = selectedChannel?.url,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 上层：只有点击大播放按钮才挂载正式播放器
                    if (isPlayerActive && selectedChannel != null) {
                        MobileVideoPlayer(
                            streamUrl = selectedChannel!!.url,
                            title = selectedChannel!!.name,
                            modifier = Modifier.fillMaxSize(),
                            isLive = true,
                            // 同一频道的其余线路：起播超时 / 卡顿超时 / 报错时自动切换
                            liveStreamUrls = selectedChannel!!.urls,
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
                    }

                    // 预览态覆盖信息（播放态交给播放器自己的控制层）
                    if (!isPlayerActive) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 毛玻璃蒙版：明显加深让预览层更「实」，再叠一层极淡主题蓝，
                            // 避免大半径模糊把画面洗成灰白半透明
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0B1220).copy(alpha = 0.38f))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1E88E5).copy(alpha = 0.08f))
                            )
                            // 内嵌 LIVE 胶囊徽章 (内嵌于播放器内部留足 16dp 呼吸边距，杜绝切角贴片感)
                            Surface(
                                color = Color(0xFFE50914),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(22.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color.White, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "LIVE",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            // 播放按钮独立居中：与播放器控制层的暂停按钮共用同一中心点。
                            // 旧实现把按钮和文字放在同一个居中 Column 里，按钮被下方文字顶偏了。
                            GlassControlButton(
                                onClick = {
                                    if (selectedChannel != null) {
                                        isPlayerActive = true
                                    } else {
                                        onShowToast("暂无可用电视频道")
                                    }
                                },
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "播放",
                                modifier = Modifier.align(Alignment.Center)
                            )

                            // 标题与节目信息下移到按钮下方：用 offset 平移，不参与按钮的居中计算
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = 72.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "正在直播: ${selectedChannel?.name ?: "CCTV-1 综合 4K"}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                val headerEpg = remember(epgPrograms, selectedChannel, nowTick) {
                                    selectedChannel?.let { EpgPrograms.lookup(epgPrograms, it, nowTick) }
                                }
                                if (headerEpg != null) {
                                    Text(
                                        text = EpgPrograms.formatHeader(headerEpg),
                                        color = Color(0xFF42A5F5),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 播放框下方的电视直播分类与电视台列表 (仅在竖屏状态下展示，横屏下完全隐藏避让)
                if (!isLandscape) {
                    if (categoryList.size > 1) {
                        val liveCategoryState = rememberLazyListState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            LazyRow(
                                state = liveCategoryState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .horizontalScrollFadingEdge(liveCategoryState, 40.dp),
                                contentPadding = PaddingValues(start = 16.dp, end = 32.dp, top = 6.dp, bottom = 6.dp),
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
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        border = null
                                    )
                                }
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
                            // 只查询真正可见的频道，结果由 Repository 按天缓存
                            LaunchedEffect(channel.name, epgTemplate) {
                                TvBoxRepository.ensureChannelEpg(channel)
                            }
                            val isCurrent = channel.url == selectedChannel?.url
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 选台只切换模糊预览源，正式播放唯一入口是中间的大播放按钮
                                        selectedChannel = channel
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = BorderStroke(
                                    0.5.dp,
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
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
                                        val channelEpg = remember(epgPrograms, channel, nowTick) {
                                            EpgPrograms.lookup(epgPrograms, channel, nowTick)
                                        }
                                        if (channelEpg != null) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = EpgPrograms.formatList(channelEpg),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
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
    val themeState by themeManager.appThemeStateFlow.collectAsState()
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
    // 分组标题此前为 14.sp，反而小于列表项标题的 15.sp，层级是倒挂的，
    // 因此提升到 16.sp Bold 并使用最高对比度的 onSurface，拉开区块层级。
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
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
// 6. 底部导航栏 (沉底铺满 + 无选中背景框，选中态仅由图标空心→实心表达)
// -------------------------------------------------------------------------------------
@Composable
fun AnimatedBottomNavigationBar(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    val tabs = remember {
        listOf(
            "首页" to NavGlyph.Home,
            "分类" to NavGlyph.Category,
            "直播" to NavGlyph.Live,
            "设置" to NavGlyph.Settings,
            "我的" to NavGlyph.Profile
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
    // 选中态不再绘制任何胶囊/描边背景：视觉变化只落在图标本身（空心 → 实心）

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部分割细线：让导航栏与内容区在浅色底上有明确边界
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = selectedIndex == index

                // 图标与文字颜色：选中态使用主题色并在 240ms 内平滑过渡
                val glyphTint by animateColorAsState(
                    targetValue = if (isSelected) primary else idleTint,
                    animationSpec = tween(240),
                    label = "navGlyphTint"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 固定 56 x 30 占位保证五个 Tab 图标光学等大；不绘制任何背景框
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 线框态 → 实心态交叉淡入，强化“当前所在页”的视觉锚点
                        Crossfade(
                            targetState = isSelected,
                            animationSpec = tween(180),
                            label = "navGlyphState"
                        ) { filled ->
                            NavGlyphIcon(
                                type = tab.second,
                                filled = filled,
                                tint = glyphTint,
                                // 实心电视机的播放三角挖空色 = 导航栏底色
                                knockout = MaterialTheme.colorScheme.background,
                                strokeWidth = if (filled) 1.8.dp else 2.1.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = tab.first,
                        color = glyphTint,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
    }
}

/**
 * 直播模糊预览层（独立顶层 Composable）。
 * 放在这里而不是 LiveScreenContent 内，是为了避开外层 ColumnScope 的隐式接收者 ——
 * 在 BoxScope + ColumnScope 叠加的作用域里，AnimatedVisibility 会被解析成
 * ColumnScope 的扩展版本而编译失败。
 *
 * 三层结构：底层 Bing 静态壁纸（本地缓存，0 延迟防黑屏）→ 中层静音模糊直播流
 * （首帧解码后才淡入，之前 alpha 为 0，不遮挡壁纸）→ 上层文字/按钮。
 */
@Composable
private fun LiveBlurPreview(
    visible: Boolean,
    streamUrl: String?,
    modifier: Modifier = Modifier
) {
    // 换台时重置，重新等待新流首帧
    var frameReady by remember(streamUrl) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (frameReady) 1f else 0f,
        animationSpec = tween(320),
        label = "livePreviewFade"
    )

    Box(modifier = modifier) {
        BingWallpaperBackdrop(
            active = !frameReady,
            modifier = Modifier
                .fillMaxSize()
                .alpha(1f - videoAlpha)
        )

        AnimatedVisibility(
            visible = visible && !streamUrl.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            streamUrl?.let { url ->
                LiveStreamPreview(
                    streamUrl = url,
                    onFirstFrame = { frameReady = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(videoAlpha)
                )
            }
        }
    }
}

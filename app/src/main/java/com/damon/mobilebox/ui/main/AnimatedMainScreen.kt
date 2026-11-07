package com.damon.mobilebox.ui.main

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
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
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.damon.mobilebox.ui.player.findActivity
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.watch.PlaybackSettings
import com.damon.mobilebox.data.watch.WebDavConfig
import com.damon.mobilebox.ui.theme.ThemeManager
import com.damon.mobilebox.ui.theme.ThemePreference
import com.damon.mobilebox.ui.theme.ThemeColorMode
import com.damon.mobilebox.ui.theme.AppThemeState
import com.damon.mobilebox.ui.components.HomeBaseColor
import com.damon.mobilebox.ui.components.NavGlyph
import com.damon.mobilebox.ui.components.NavGlyphIcon
import com.damon.mobilebox.ui.components.ModernToastMessage
import com.damon.mobilebox.ui.components.CupertinoSwitch
import com.damon.mobilebox.ui.components.ColorPickerDialog
import com.damon.mobilebox.ui.components.StandardDialog
import com.damon.mobilebox.ui.components.WebDavSyncDialog
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
import com.damon.mobilebox.data.watch.WatchStore
import com.damon.mobilebox.ui.detail.MovieDetailScreen
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.damon.mobilebox.data.tmdb.TmdbMeta

/** 直播的一次观看会话：首帧落地才开，换台 / 关闭播放时结算墙上时长。 */
private class LiveWatchSession {
    var recordId: String? = null
    var startRealtime: Long = 0L
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AnimatedMainScreen(
    themeManager: ThemeManager,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onNavigateToDetail: (VideoDetailData) -> Unit = {},
    onNavigateToTmdbDetail: (TmdbMeta) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchList: (WatchListType) -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 首页滚动状态提升到这里（而不是留在 TmdbHomeContent 内部）：
    // 底部导航栏要靠它判断「现在是不是压在海报上」。顺带的好处是切 Tab 再回来，
    // 首页的滚动位置还在。
    val homeScrollState = rememberScrollState()
    val homeHeroHeightPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenHeightDp.dp * HERO_SCREEN_FRACTION)
            .coerceIn(420.dp, 700.dp)
            .toPx()
    }

    // 从详情页 / 观影列表返回时，home 会被 NavHost 重新组合，`rememberPagerState` 自带的
    // saveable 恢复并不总能命中，实测会落回第 0 页（首页）。这里以上层保存的 Tab 为准
    // 显式对齐一次，保证「在哪个 Tab 进详情，就回到哪个 Tab」。
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != initialPage) pagerState.scrollToPage(initialPage)
    }
    // 反向同步：用户点底部导航切 Tab 时，把当前页写给上层保存。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    // 当前激活的 TVBox 站点状态 (从 TvBoxRepository 实时订阅与响应)
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    var showSourceDialog by remember { mutableStateOf(false) }

    // 首页（第 1 页）现在是纯 TMDB 内容：轮播 / 榜单的加载与下拉刷新全部由
    // TmdbHomeContent 自己持有，这里不保存任何首页状态，也不再渲染顶部栏。
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
                                    },
                                    // 首页的滚动状态由这里持有：底部导航栏要读它决定
                                    // 「透明压在海报上」还是「白底」。
                                    homeScrollState = homeScrollState,
                                    heroHeightPx = homeHeroHeightPx
                                )
                            }
                        }
                    ) { paddingValues ->
                        val bottomBarInset =
                            if (!isMainLandscape) paddingValues.calculateBottomPadding() else 0.dp
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = false,
                            // 不再统一 padding(bottom)：首页必须铺到导航栏底下才有沉浸感
                            // （导航栏透明时透出的就是海报下方的模糊延续）。
                            // 其它页在下面的 else 分支里自己让出这段高度，观感与之前一致。
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            if (page == 0) {
                                // 首页纯 TMDB：沉浸式大图 + 正在热映 / 正在播出 / 高分佳作横滑。
                                // 不再有顶部栏（数据源名、搜索按钮），也不再混入采集源海报网格。
                                Box(modifier = Modifier.fillMaxSize()) {
                                    TmdbHomeContent(
                                        onEntryClick = { meta -> onNavigateToTmdbDetail(meta) },
                                        scrollState = homeScrollState,
                                        bottomInset = bottomBarInset
                                    )
                                }
                            } else {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = bottomBarInset)
                                ) {
                                    when (page) {
                                        1 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                            CategoryScreen(
                                                onMovieClick = { detail ->
                                                    TvBoxRepository.selectedDetail = detail
                                                    onNavigateToDetail(detail)
                                                },
                                                onShowToast = { toastMessage = it },
                                                onSearchClick = onNavigateToSearch,
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
                                                onOpenSourceDialog = { showSourceDialog = true }
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

    // 直播观看会话：频道名变化即换台，重建会话对象；关闭播放时由播放器所在 if 块移除来结算。
    // 直播没有「集数 / 播放进度」，只记墙上时长，供统计卡片里的直播时长使用。
    val activeChannelName = selectedChannel?.name.orEmpty()
    val liveSession = remember(activeChannelName) { LiveWatchSession() }

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
                        // 换台（key 变化）或关闭播放（整个 if 块被移除）都会走到 onDispose，
                        // 把这一段观看时长补进历史；首帧没出来就不会有 recordId，不记失败会话。
                        DisposableEffect(activeChannelName) {
                            onDispose {
                                val id = liveSession.recordId
                                val start = liveSession.startRealtime
                                if (id != null && start > 0L) {
                                    WatchStore.finishSession(
                                        id,
                                        SystemClock.elapsedRealtime() - start,
                                        0L,
                                        0L
                                    )
                                }
                                liveSession.recordId = null
                                liveSession.startRealtime = 0L
                            }
                        }
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
                            onFirstFrameRendered = {
                                if (liveSession.recordId == null) {
                                    liveSession.recordId = WatchStore.recordPlayback(
                                        videoId = "live_$activeChannelName",
                                        title = activeChannelName.ifBlank { "电视直播" },
                                        posterUrl = selectedChannel?.logo.orEmpty(),
                                        episodeId = 0,
                                        episodeName = "直播",
                                        sourceName = "电视直播",
                                        isSeries = false,
                                        isLive = true
                                    )
                                    liveSession.startRealtime = SystemClock.elapsedRealtime()
                                }
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
                            // 判定当前台必须用「分组 + 频道名」，不能用 url：
                            // 直播源里不同频道共用同一条主线路很常见（CGTN 法语 / 西语 / 阿语三台
                            // 的主线路完全一致，只有备用线路不同），按 url 比对会一次点亮三行（用户 18:51 反馈）。
                            val isCurrent = selectedChannel?.let {
                                it.name == channel.name && it.group == channel.group
                            } == true
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
    onOpenSourceDialog: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val themeState by themeManager.appThemeStateFlow.collectAsState()
    // 播放行为开关：共享 StateFlow，切 Tab 不会先渲染一帧默认值
    val playPrefs by PlaybackSettings.prefsFlow.collectAsState()
    // WebDAV 配置：共享 StateFlow，切 Tab 不会先渲染一帧空配置
    val webDavState by WebDavConfig.stateFlow.collectAsState()
    val currentTheme = themeState.themePreference
    val selectedWarehouse by TvBoxRepository.selectedWarehouse.collectAsState()
    val savedSubscriptionUrl by TvBoxRepository.savedSubscriptionUrl.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }

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
                        SettingLeadingIcon(Icons.Default.Storage)
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
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
                        SettingLeadingIcon(Icons.Default.DarkMode)
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
                        SettingLeadingIcon(Icons.Default.ColorLens)
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
                                .size(18.dp)
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
                        SettingLeadingIcon(Icons.Default.ScreenRotation)
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

        // 播放行为（画中画 / 后台播放）
        item {
            SettingSectionHeader("播放设置")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 解码内核固定为 Media3 ExoPlayer，不是可配置项，故不在设置页占位。
                    // 双核 IJK / MPV 属 Step 3.1，用户已明确跳过。

                    // 画中画：关掉后按 Home 不再缩成小窗，是否继续出声交给「后台播放」开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingLeadingIcon(Icons.Default.PictureInPictureAlt)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "画中画小窗",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (playPrefs.pipEnabled) "已开启 · 按 Home 自动缩成悬浮小窗继续播放"
                                else "已关闭 · 按 Home 直接退到后台",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        CupertinoSwitch(
                            checked = playPrefs.pipEnabled,
                            onCheckedChange = { PlaybackSettings.setPipEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = 12.dp))

                    // 后台播放：关 = 切后台暂停、回前台恢复；开 = 不干预，锁屏继续出声
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingLeadingIcon(Icons.Default.Headphones)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "后台播放",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (playPrefs.backgroundPlayEnabled) "已开启 · 切后台或锁屏继续播放声音"
                                else "已关闭 · 切后台自动暂停，返回后继续",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        CupertinoSwitch(
                            checked = playPrefs.backgroundPlayEnabled,
                            onCheckedChange = { PlaybackSettings.setBackgroundPlayEnabled(it) }
                        )
                    }
                }
            }
        }

        // 云端同步（WebDAV）
        item {
            SettingSectionHeader("云端同步")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRowItem(
                        icon = Icons.Default.Cloud,
                        title = "WebDAV 云端同步",
                        subtitle = if (webDavState.isConfigured) {
                            "已配置 · ${webDavState.baseUrl.trim().trimEnd('/')}"
                        } else {
                            "未配置 · 点击填写服务地址与应用密码"
                        },
                        onClick = { showWebDavDialog = true }
                    )
                }
            }
        }

        // 系统信息
        item {
            SettingSectionHeader("系统关于")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
        StandardDialog(onDismissRequest = { showThemeDialog = false }) {
            Text(
                "选择主题",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
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
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
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

    // WebDAV 云端同步：配置与上传 / 下载 / 双向同步
    if (showWebDavDialog) {
        WebDavSyncDialog(onDismiss = { showWebDavDialog = false })
    }
}

/**
 * 设置项左侧图标的统一容器：固定 24dp 占位、22dp 绘制、统一 primary 着色。
 * Material 各图标内部留白不同，用统一容器保证每行文字起始位置与图标视觉尺寸一致。
 */
@Composable
private fun SettingLeadingIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
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
        SettingLeadingIcon(icon)
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

    StandardDialog(onDismissRequest = onDismiss) {
        Text(
            "关于 MobileBox",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
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
        // TMDB 条款硬性要求：使用了 API 就必须展示这句声明，且不得暗示官方背书。
        Text(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss) {
                Text("确定", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 6. 底部导航栏（悬浮玻璃板：圆角矩形浮在页面之上，不再铺满屏宽到底）
// -------------------------------------------------------------------------------------
/** 玻璃板高度（不含底部安全区）。 */
private val NavBarHeight = 62.dp

/**
 * 是否绘制玻璃面板（填充 + 折射 + 外缘高光）。
 *
 * **当前为 false：导航栏只有图标和文字，没有托底圆角矩形。**（用户 23:36 确认这样最好看）
 *
 * 为什么做成开关而不是删掉：这块圆的实现是对的（先 clip 再画、折射用竖直渐变），
 * 只是半透明面板在浅色页面上会近白、在深色海报上又近底色，两页观感对不上 ——
 * 面板本身成了「多余的色块」。留着开关，以后想要托底直接翻 true。
 */
private const val NavBarGlassPanel = false
/** 玻璃板与屏幕左右边的留白：留白 + 圆角才读得出「悬浮」。 */
internal val NavBarSideMargin = 16.dp
/** 玻璃板与底部安全区之间的留白。 */
internal val NavBarBottomGap = 10.dp
private val NavBarCorner = 28.dp
private val NavBarShape = RoundedCornerShape(NavBarCorner)

@Composable
fun AnimatedBottomNavigationBar(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    /** 首页滚动状态；非首页传 null，导航栏按主题底色渲染。 */
    homeScrollState: ScrollState? = null,
    /** 首页海报高度（px），用来换算「滚过多少就该从透明变成实底」。 */
    heroHeightPx: Float = 0f
) {
    val tabs = remember {
        listOf(
            "首页" to NavGlyph.Home,
            "分类" to NavGlyph.Category,
            "直播" to NavGlyph.Live,
            "设置" to NavGlyph.Settings,
            "我的" to NavGlyph.Profile
        )
    }

    // 刚进首页时海报铺到屏幕底部，此时玻璃板要用深色（白字），滚过遮罩半程后再换成
    // 浅色玻璃（深字）。二值判断 + 颜色动画，不做逐帧插值：每滚一帧读 scrollState
    // 会让整个导航栏每帧重组。
    val immersive by remember(homeScrollState, selectedIndex, heroHeightPx) {
        derivedStateOf {
            homeScrollState != null &&
                selectedIndex == 0 &&
                heroHeightPx > 0f &&
                homeScrollState.value < heroHeightPx * VEIL_FRACTION * 0.5f
        }
    }

    val scheme = MaterialTheme.colorScheme
    val lightSurface = scheme.surface.luminance() > 0.5f

    // 玻璃基色。**压得很低**：液态玻璃要能看见背后，0.7 以上就退化成一块实底塑料。
    // 压在暗海报上还可以更低（0.22）：背后内容是「已渐隐到底色」的平面，不需要面板挡。
    val panelTint by animateColorAsState(
        targetValue = if (immersive) HomeBaseColor.copy(alpha = 0.22f)
        else scheme.surface.copy(alpha = 0.38f),
        animationSpec = tween(320),
        label = "navGlassPanel"
    )
    // 折射/高光的强度：暗态压在海报上时收一点，浅底上给足，否则边界糊在背景里。
    val rimAlpha by animateFloatAsState(
        targetValue = if (immersive) 0.34f else if (lightSurface) 0.62f else 0.30f,
        animationSpec = tween(320),
        label = "navGlassRim"
    )

    val primary = scheme.primary
    val idleTint = scheme.onSurfaceVariant.copy(alpha = 0.92f)
    // 实心图标的挖空色（电视机的播放三角）= 面板实际可见色。
    val knockout by animateColorAsState(
        targetValue = if (immersive) HomeBaseColor else scheme.surface,
        animationSpec = tween(320),
        label = "navKnockout"
    )

    // 悬浮定位：左右留边 + 底部让出系统手势条再留一点空隙，面板才「浮」起来。
    // 这段内边距会算进 Scaffold 的 bottomBar 高度，因此各页 contentPadding 自动跟着变。
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NavBarSideMargin,
                end = NavBarSideMargin,
                bottom = navBottomInset + NavBarBottomGap
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarHeight)
                // **先裁成圆角矩形，再画所有像素**。顺序是关键：
                // 上一版是 shadow → drawWithCache → clip，绘制发生在裁切之前，
                // 整行宽度的绘制区域（含 18dp 阴影图层）会盖在页面之上 ——
                // 分类页截图里「铺满屏宽的白色层」就是这块。裁切前置后，任何一笔
                // 都不可能超出这块圆角矩形，问题在结构上被消掉，不靠调参。
                .clip(NavBarShape)
                .drawBehind {
                    if (!NavBarGlassPanel) return@drawBehind
                    val cornerPx = NavBarCorner.toPx()
                    val hairPx = 1.dp.toPx()
                    val refrPx = 9.dp.toPx()
                    val w = size.width
                    val h = size.height

                    // 上淡下实：玻璃有厚度，光穿下来会衰减。三档之间的幅度压得很小，
                    // 否则半透明面板会在中间那道停靠点上透出一条横向色阶。
                    val fill = Brush.verticalGradient(
                        0f to panelTint.copy(alpha = panelTint.alpha * 0.62f),
                        0.46f to panelTint.copy(alpha = panelTint.alpha * 0.92f),
                        1f to panelTint.copy(
                            alpha = (panelTint.alpha * 1.12f).coerceAtMost(1f)
                        )
                    )
                    // 边缘光：左上最亮、右下最弱 —— 光源固定在上方，与 iOS 液态玻璃一致。
                    val rim = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = rimAlpha),
                            Color.White.copy(alpha = rimAlpha * 0.22f),
                            Color.White.copy(alpha = rimAlpha * 0.04f),
                            Color.White.copy(alpha = rimAlpha * 0.42f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                    // 折射带：贴着上沿的一条白光，向下快速消失，模拟「光从玻璃上边缘折进来」。
                    // 纯白等宽描边是一圈发光的塑料边，竖直渐变才像折射。
                    val refraction = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = rimAlpha * 0.30f),
                        0.22f to Color.White.copy(alpha = rimAlpha * 0.10f),
                        0.55f to Color.White.copy(alpha = 0f),
                        1f to Color.White.copy(alpha = 0f)
                    )

                    drawRect(fill)
                    // 内折射带：内缩 2dp 起画，宽度 9dp，只在上半部有亮度。
                    val inset = refrPx * 0.22f
                    drawRoundRect(
                        brush = refraction,
                        topLeft = Offset(inset, inset),
                        size = Size(w - inset * 2f, h - inset * 2f),
                        cornerRadius = CornerRadius(cornerPx - inset, cornerPx - inset),
                        style = Stroke(width = refrPx)
                    )
                    // 外缘 1dp 高光：把玻璃从背景里切出来。液态玻璃靠这条边立住，
                    // 不靠投影 —— 投影在浅色页面上只会糊成一团灰。
                    drawRoundRect(
                        brush = rim,
                        topLeft = Offset(hairPx / 2f, hairPx / 2f),
                        size = Size(w - hairPx, h - hairPx),
                        cornerRadius = CornerRadius(cornerPx - hairPx / 2f, cornerPx - hairPx / 2f),
                        style = Stroke(width = hairPx)
                    )
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = selectedIndex == index

                // 图标与文字颜色：沉浸态整体走白（压在海报上），否则用主题色，
                // 都在 240ms 内平滑过渡。
                val targetTint = when {
                    immersive && isSelected -> Color.White
                    immersive -> Color.White.copy(alpha = 0.72f)
                    isSelected -> primary
                    else -> idleTint
                }
                val glyphTint by animateColorAsState(
                    targetValue = targetTint,
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
                                knockout = knockout,
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

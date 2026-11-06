package com.damon.mobilebox.ui.main

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimatedMainScreen(themeManager: ThemeManager) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 当前选中的数据源状态 (默认: 暴风片库)
    var currentSource by remember { mutableStateOf(MockDataProvider.dataSources.first()) }
    var showSourceDialog by remember { mutableStateOf(false) }

    // 全局自适应极简提示弹窗状态
    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        toastMessage = "3D 视差海报与 24 部精选大片已就绪"
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
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    AnimatedBottomNavigationBar(
                        selectedIndex = pagerState.currentPage,
                        onTabSelected = { index ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )
                }
            ) { paddingValues ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                ) { page ->
                    when (page) {
                        0 -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                OptimizedTopBar(
                                    sourceName = currentSource.name,
                                    onSearchClick = {
                                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                    }
                                )
                                HomeGridContent(currentSourceId = currentSource.id, onShowToast = { toastMessage = it })
                            }
                        }
                        1 -> Box(Modifier.fillMaxSize().statusBarsPadding()) { CategoryScreen(onShowToast = { toastMessage = it }) }
                        2 -> Box(Modifier.fillMaxSize().statusBarsPadding()) { LiveScreenContent(onShowToast = { toastMessage = it }) }
                        3 -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            ModernSettingsScreen(
                                themeManager = themeManager,
                                currentSource = currentSource,
                                onOpenSourceDialog = { showSourceDialog = true },
                                onShowToast = { toastMessage = it }
                            )
                        }
                        4 -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ProfileScreen(onShowToast = { toastMessage = it }) }
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

    // 切换数据源弹窗 (Material 3 对话框)
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = {
                Text(
                    text = "切换订阅数据源",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "重客户端直连架构：纯文本解析，媒体流由手机直连嗅探",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    MockDataProvider.dataSources.forEach { source ->
                        val isSelected = source.id == currentSource.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    currentSource = source
                                    showSourceDialog = false
                                    toastMessage = "已切换至：${source.name}"
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = source.name,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = source.description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// -------------------------------------------------------------------------------------
// 1. 顶部操作栏 (首页极简大标题与搜索按钮)
// -------------------------------------------------------------------------------------
@Composable
fun OptimizedTopBar(
    sourceName: String = "暴风片库",
    onSearchClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sourceName,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

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
// 2. 首页精选点播 (根据选中的数据源动态切换电影列表，全局 StandardPullToRefreshBox 物理阻尼与位移动画)
// -------------------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeGridContent(
    currentSourceId: String = "bf",
    onShowToast: (String) -> Unit = {}
) {
    val baseList = if (currentSourceId == "hn") MockDataProvider.hnVodList else MockDataProvider.homeVodList
    var currentList by remember(currentSourceId) { mutableStateOf(baseList) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    StandardPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                delay(1200)
                val newId = System.currentTimeMillis().toString()
                val newCandidates = listOf(
                    Pair("黑神话：悟空 剧场版", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
                    Pair("异形：夺命舰", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
                    Pair("死侍与金钢狼", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
                    Pair("边水往事 结局篇", "https://image.tmdb.org/t/p/w500/7V0Ebks0GgpKvQ7QbLAIdX5dos4.jpg")
                )
                val chosen = newCandidates.random()
                val newAddedMovie = MockMovie(
                    id = newId,
                    title = chosen.first,
                    remarks = "4K 极清首发",
                    category = "电影",
                    rating = "9.5",
                    posterUrl = chosen.second
                )
                // 在顶部插入新数据，下方旧数据伴随位移动画丝滑推下
                currentList = listOf(newAddedMovie) + currentList
                isRefreshing = false
                onShowToast("已为您刷新并置顶最新影片：《${newAddedMovie.title}》")
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 24.dp, // 顶部预留出指示器的高度空间，避免遮挡第一排海报
                bottom = 96.dp // 底部留白，防 BottomNavigationBar 遮挡
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = currentList,
                key = { movie -> movie.id },
                contentType = { "vod_poster_card" }
            ) { movie ->
                StandardPosterCard(
                    title = movie.title,
                    subtitle = movie.remarks,
                    score = movie.rating,
                    posterUrl = movie.posterUrl,
                    modifier = animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                        placementSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        )
                    ),
                    onClick = {
                        onShowToast("点击播放: ${movie.title}")
                    }
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 4. 电视直播中心
// -------------------------------------------------------------------------------------
@Composable
fun LiveScreenContent(
    onShowToast: (String) -> Unit = {}
) {
    var selectedChannelId by remember { mutableStateOf("1") }
    val currentChannel = MockDataProvider.liveChannels.find { it.id == selectedChannelId } 
        ?: MockDataProvider.liveChannels.first()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                .aspectRatio(1.77f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "正在直播：${currentChannel.name}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前节目: ${currentChannel.currentProgram}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }

            Surface(
                color = Color(0xFFEF4444).copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Text(
            text = "全部频道与节目单",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = MockDataProvider.liveChannels,
                key = { channel -> channel.id }
            ) { channel ->
                val isPlaying = channel.id == selectedChannelId
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedChannelId = channel.id
                            onShowToast("换台至：${channel.name}")
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.Tv,
                                contentDescription = null,
                                tint = if (isPlaying) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "正在播: ${channel.currentProgram} | 稍后: ${channel.nextProgram}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// 5. 设置页面与主题模式切换 (集成数据源订阅、ThemeManager、HSV 色盘与 DataStore 持久化)
// -------------------------------------------------------------------------------------
@Composable
fun ModernSettingsScreen(
    themeManager: ThemeManager,
    currentSource: MockDataSource,
    onOpenSourceDialog: () -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val themeState by themeManager.appThemeStateFlow.collectAsState(initial = AppThemeState())
    val currentTheme = themeState.themePreference
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
                                text = "订阅数据源",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "当前: ${currentSource.name} (${currentSource.description})",
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



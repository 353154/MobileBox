package com.damon.mobilebox.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.damon.mobilebox.ui.components.GlassControlButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.damon.mobilebox.data.tvbox.LiveChannel
import com.damon.mobilebox.data.tvbox.LiveChannelGroup
import com.damon.mobilebox.data.tvbox.TvBoxParser
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun MobileVideoPlayer(
    streamUrl: String,
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {},
    isLive: Boolean = false,
    /** 同一频道的备用线路（不含 [streamUrl] 本身也行，内部会去重），直播起播超时/卡顿超时按顺序自动切换 */
    liveStreamUrls: List<String> = emptyList(),
    channelGroups: List<LiveChannelGroup> = emptyList(),
    currentChannel: LiveChannel? = null,
    onSelectChannel: ((LiveChannel) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = remember(context) { findActivity(context) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 是否展开横屏电视直播换台半透明抽屉
    var isChannelDrawerOpen by remember { mutableStateOf(false) }

    // 初始化并持有具备移动端专有 UA 与跨协议重定向能力的 ExoPlayer
    val exoPlayer = remember(context) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; MobileBox) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(25000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var areControlsVisible by remember { mutableStateOf(true) }

    var activeStreamUrl by remember { mutableStateOf(streamUrl) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    // ---- 同频道多线路自动切换 ----
    // live.txt 里同一个频道通常配了 3 条以上线路，deduplicateGroups 已把它们聚合成 urls。
    // 这里按顺序作为备用源：起播超时、播放中长时间卡在 buffering、或直接报错，都自动跳下一条。
    // 上次播通过的线路会被提到最前面，避免每次进同一个台都要先等一次超时。
    val liveSources = remember(streamUrl, liveStreamUrls) {
        val raw = (listOf(streamUrl) + liveStreamUrls).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val remembered = LiveSourceMemory.preferred(title)
        if (remembered != null && remembered in raw) {
            listOf(remembered) + raw.filter { it != remembered }
        } else {
            raw
        }
    }
    // 这几个状态刻意不带 streamUrl key：错误回调注册在 DisposableEffect(exoPlayer) 里，
    // 若状态对象随换台重建，回调会写到已被丢弃的旧 State 上（表现为切线路后状态错乱）。
    val sourceIndexState = remember { mutableIntStateOf(0) }
    val playbackStateState = remember { mutableIntStateOf(Player.STATE_IDLE) }
    var switchNotice by remember { mutableStateOf<String?>(null) }
    val activeSourceUrl = liveSources.getOrElse(sourceIndexState.intValue) { streamUrl }

    LaunchedEffect(activeSourceUrl) {
        activeStreamUrl = activeSourceUrl
        retryCount = 0
    }
    LaunchedEffect(streamUrl) {
        sourceIndexState.intValue = 0
        playbackStateState.intValue = Player.STATE_IDLE
        switchNotice = null
    }
    // 切换提示 2.6 秒后自动消失
    LaunchedEffect(switchNotice) {
        if (switchNotice != null) {
            delay(2600)
            switchNotice = null
        }
    }

    // 错误回调注册在 DisposableEffect(exoPlayer) 里、只注册一次，必须读"最新"的线路列表
    val currentLiveSources by rememberUpdatedState(liveSources)
    /**
     * 切到下一条线路。所有被引用的状态都是不带 key 的 remember，委托对象跨重组稳定，
     * 因此这个 lambda 即使被只注册一次的错误回调捕获，也不会写到废弃的状态上。
     */
    val switchToNextSource: (String) -> Unit = { reason ->
        val next = sourceIndexState.intValue + 1
        val total = currentLiveSources.size
        if (next < total) {
            playbackError = null
            switchNotice = "已切到线路 ${next + 1}/$total · $reason"
            sourceIndexState.intValue = next
            Log.d("MB-LIVE", "自动切线路 ${next + 1}/$total ($reason) -> ${currentLiveSources[next]}")
        } else {
            playbackError = "所有线路均不可用（共 $total 条）"
            Log.d("MB-LIVE", "全部线路不可用 ($reason) total=$total")
        }
    }

    // 横竖屏切换核心逻辑
    val toggleFullScreen = {
        activity?.let { act ->
            val window = act.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                // 当前是横屏，切回竖屏并恢复系统栏
                isChannelDrawerOpen = false
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                // 当前是竖屏，切换为横屏并隐藏系统栏
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // 拦截物理返回键：若抽屉打开则优先关闭抽屉，否则横屏下退出横屏
    BackHandler(enabled = isLandscape || isChannelDrawerOpen) {
        if (isChannelDrawerOpen) {
            isChannelDrawerOpen = false
        } else if (isLandscape) {
            toggleFullScreen()
        }
    }

    // 监听横竖屏变化，沉浸式隐藏或恢复系统状态栏与虚拟导航栏
    LaunchedEffect(isLandscape) {
        activity?.let { act ->
            val window = act.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                isChannelDrawerOpen = false
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 离开播放器时，确保恢复竖屏与系统栏
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 当视频流发生变化时，更新播放源 (支持嗅探解析与协议降级重试)
    LaunchedEffect(activeSourceUrl, retryCount) {
        isLoading = true
        playbackError = null

        // 1. 自动嗅探/提取网页分享页背后的真实直连 m3u8 流
        var finalUrl = activeSourceUrl.trim()
        if (finalUrl.contains("/share/") || finalUrl.contains(".html") || !finalUrl.contains(".")) {
            finalUrl = TvBoxParser.sniffRealStreamUrl(finalUrl)
        }

        // 2. 如果遇到 SSL 握手问题重试，尝试降级为 HTTP 明文直连
        if (retryCount > 0 && finalUrl.startsWith("https://", ignoreCase = true)) {
            finalUrl = "http://" + finalUrl.removePrefix("https://")
        }

        activeStreamUrl = finalUrl

        val mediaItemBuilder = MediaItem.Builder().setUri(finalUrl)
        if (finalUrl.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (finalUrl.contains(".mp4", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MP4)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // 播放器状态事件监听
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackStateState.intValue = playbackState
                isLoading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    playbackError = null
                    // 记住这条线路可用：下次进同一个频道优先用它，不再先等一次超时
                    if (isLive) LiveSourceMemory.remember(title, activeStreamUrl)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                isLoading = false
                val cause = error.cause
                val isSslOrTlsError = cause is javax.net.ssl.SSLException ||
                        cause?.message?.contains("SSL", ignoreCase = true) == true ||
                        cause?.message?.contains("handshake", ignoreCase = true) == true

                if (isSslOrTlsError && retryCount == 0 && activeStreamUrl.startsWith("https://", ignoreCase = true)) {
                    // 遇到 SSL 握手失败，自动切到 HTTP 协议重试一次
                    retryCount++
                    return
                }
                val message = when {
                    cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException -> "媒体流编码格式无法识别，请切换线路"
                    cause is javax.net.ssl.SSLException -> "安全握手失败 (SSL)，请尝试切换线路"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频资源不存在 (404)"
                    else -> "播放失败 (${error.errorCodeName})"
                }
                // 直播：还有备用线路就直接跳下一条，不打断观看
                if (isLive && currentLiveSources.size > 1) {
                    switchToNextSource("线路报错")
                } else {
                    playbackError = message
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 周期性轮询播放进度
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    // ---- 直播线路守护：起播超时 / 播放中卡死 都自动切下一条 ----
    // 只在直播启用。报错由 onPlayerError 处理，这里专门兜住「不报错但也一直不出画面」的情况
    // —— 免费源最常见的失败形态就是握手成功、分片永远拉不下来，播放器一直转圈。
    LaunchedEffect(isLive, activeSourceUrl, currentLiveSources.size) {
        if (!isLive || currentLiveSources.size <= 1) return@LaunchedEffect
        val enteredAt = SystemClock.elapsedRealtime()
        var everReady = false
        var bufferingSince = 0L
        while (true) {
            delay(400)
            // 已经把所有线路试完并弹了错误面板，就别再重复切了
            if (playbackError != null) return@LaunchedEffect
            val now = SystemClock.elapsedRealtime()
            when (playbackStateState.intValue) {
                Player.STATE_READY -> {
                    everReady = true
                    bufferingSince = 0L
                }
                Player.STATE_ENDED -> return@LaunchedEffect
                else -> {
                    if (!everReady) {
                        if (now - enteredAt >= LiveFirstFrameTimeoutMs) {
                            switchToNextSource("起播超时")
                            return@LaunchedEffect
                        }
                    } else {
                        if (bufferingSince == 0L) bufferingSince = now
                        if (now - bufferingSince >= LiveStallTimeoutMs) {
                            switchToNextSource("卡顿超时")
                            return@LaunchedEffect
                        }
                    }
                }
            }
        }
    }

    // 控制栏自动隐藏定时器 (播放中且未展开换台抽屉时，4 秒无交互自动淡出)
    LaunchedEffect(areControlsVisible, isPlaying, isChannelDrawerOpen) {
        if (areControlsVisible && isPlaying && !isChannelDrawerOpen) {
            delay(4000)
            areControlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(isLandscape, isLive, isChannelDrawerOpen) {
                detectTapGestures(
                    onTap = { offset ->
                        if (isChannelDrawerOpen) {
                            // 再次点击屏幕：关闭换台抽屉与控制栏
                            isChannelDrawerOpen = false
                            areControlsVisible = false
                        } else {
                            // 横屏全屏直播模式下：轻点屏幕左侧 25% 区域直接呼出半透明换台列表
                            if (isLandscape && isLive && offset.x < size.width * 0.25f) {
                                isChannelDrawerOpen = true
                                areControlsVisible = true
                            } else {
                                // 点击其他区域：再次点击切换/隐藏控制栏
                                areControlsVisible = !areControlsVisible
                            }
                        }
                    }
                )
            }
    ) {
        // 底层视频画面渲染视图
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 横屏直播边缘滑动呼出检测区 (手指从屏幕最左侧向右滑入呼出抽屉)
        if (isLandscape && isLive && !isChannelDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount > 15) {
                                isChannelDrawerOpen = true
                                areControlsVisible = true
                            }
                        }
                    }
            )
        }

        // 中间缓冲加载菊花指示器
        if (isLoading && playbackError == null) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
            )
        }

        // 自动切线路提示：顶部一条淡出胶囊，2.6s 后自动消失
        AnimatedVisibility(
            visible = switchNotice != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = switchNotice ?: "",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 播放失败提示与重试面板
        if (playbackError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "错误",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = playbackError ?: "播放异常",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            playbackError = null
                            retryCount++
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("重试播放", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onBackClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("返回详情", fontSize = 13.sp)
                    }
                }
            }
        }

        // 浮层自定义控制器
        AnimatedVisibility(
            visible = areControlsVisible && !isChannelDrawerOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // 1. 顶部栏 (返回键 + 视频标题 + 换台按钮 + 标签)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isLandscape) {
                                toggleFullScreen()
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    // 如果处于横屏全屏电视直播状态，显示显眼的“换台”按钮
                    if (isLandscape && isLive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .clickable {
                                    isChannelDrawerOpen = true
                                }
                                .padding(end = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "换台",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "换台",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Surface(
                        color = if (isLive) Color(0xFFE50914) else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isLive) "LIVE 电视直播" else if (isLandscape) "全屏影院" else "硬解加速",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // 2. 中央大播放/暂停控制行
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isLive) {
                        // 点播模式后退 10 秒
                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition - 10000).coerceAtLeast(0)
                                exoPlayer.seekTo(target)
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "快退10秒",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // 播放 / 暂停大按钮：与预览态共用液态玻璃组件，中心点 / 尺寸 / 材质完全一致
                    GlassControlButton(
                        onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放"
                    )

                    if (!isLive) {
                        // 点播模式前进 10 秒
                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition + 10000).coerceAtMost(duration)
                                exoPlayer.seekTo(target)
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "快进10秒",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // 3. 底部进度条与控制行
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = if (isLandscape) 16.dp else 8.dp)
                ) {
                    if (!isLive) {
                        Slider(
                            value = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChange = { ratio ->
                                val targetMs = (ratio * duration).toLong()
                                exoPlayer.seekTo(targetMs)
                                currentPosition = targetMs
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(22.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            // 核心：全屏/横屏切换按钮
                            IconButton(
                                onClick = { toggleFullScreen() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isLandscape) "退出全屏" else "全屏横屏",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    } else {
                        // 直播专属精简底栏：实时信号指示与全屏切换
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFE50914),
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "实时直播信号 · 广播源",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            IconButton(
                                onClick = { toggleFullScreen() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isLandscape) "退出全屏" else "全屏横屏",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 横屏全屏电视直播：左侧滑动/轻点半透明换台抽屉
        AnimatedVisibility(
            visible = isLandscape && isLive && isChannelDrawerOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .align(Alignment.CenterStart)
        ) {
            Surface(
                color = Color(0xF210141D),
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // 消费内部点击，防止点击列表空白处导致抽屉意外关闭
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp)
                ) {
                    // 抽屉标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "电视台节目单",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { isChannelDrawerOpen = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 分类横向滚动药丸
                    var drawerGroupIndex by remember { mutableIntStateOf(-1) }
                    val drawerCategoryList = remember(channelGroups) {
                        val allChannels = channelGroups.flatMap { it.channels }
                        if (allChannels.isEmpty()) emptyList()
                        else listOf("全部" to allChannels) + channelGroups.map { it.groupName to it.channels }
                    }

                    if (drawerCategoryList.size > 1) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(drawerCategoryList.indices.toList()) { idx ->
                                val (grpName, _) = drawerCategoryList[idx]
                                val isSelected = idx == (drawerGroupIndex + 1)
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier.clickable { drawerGroupIndex = idx - 1 }
                                ) {
                                    Text(
                                        text = grpName,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    val drawerChannels = remember(drawerGroupIndex, drawerCategoryList, channelGroups) {
                        if (drawerGroupIndex == -1 || drawerCategoryList.isEmpty()) {
                            channelGroups.flatMap { it.channels }
                        } else {
                            drawerCategoryList.getOrNull(drawerGroupIndex + 1)?.second ?: emptyList()
                        }
                    }

                    // 频道卡片列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(drawerChannels) { channel ->
                            val isCurrent = channel.url == currentChannel?.url || channel.name == currentChannel?.name
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.04f),
                                border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectChannel?.invoke(channel)
                                        isChannelDrawerOpen = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "正在播放",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.4f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Text(
                                        text = channel.name,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isCurrent) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "播放中",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
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

/**
 * 递归查找宿主 Activity
 */
fun findActivity(context: Context): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 毫秒时间格式化为 mm:ss 或 hh:mm:ss
 */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * 直播起播超时：从调用 prepare 起算，超过这么久还没有 READY 就判定这条线路不通。
 * 免费源大量存在「握手成功但分片永远拉不下来」的形态，播放器不会报错，只会无限转圈。
 */
private const val LiveFirstFrameTimeoutMs = 9_000L

/** 播放中卡顿超时：已经出过画面，但连续这么长时间停在 buffering 就换线 */
private const val LiveStallTimeoutMs = 12_000L

/**
 * 每个频道上次播通的线路（进程内记忆，重启即失效）。
 * 免费源里同一个频道的几条线路质量差异很大，记住可用的那条能省掉每次进台的超时等待。
 */
private object LiveSourceMemory {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(channelName: String, url: String) {
        if (channelName.isBlank() || url.isBlank()) return
        map[channelName] = url
    }

    fun preferred(channelName: String): String? = map[channelName]
}

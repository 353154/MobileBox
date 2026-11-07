package com.damon.mobilebox.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PlayerBackground = Color(0xFF0A0E17)
private val PlayerPrimary = Color(0xFF2F81F7)

@Composable
fun PlayerDetailScreen(
    videoData: VideoDetailData,
    initialEpisode: VideoEpisode? = null,
    initialRouteIndex: Int = 0,
    onBackClick: () -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedRouteIndex by remember(initialRouteIndex) {
        mutableIntStateOf(initialRouteIndex.coerceIn(0, (videoData.routes.size - 1).coerceAtLeast(0)))
    }
    val currentRoute = videoData.routes.getOrNull(selectedRouteIndex) ?: videoData.routes.first()

    // 线路切换时自动对齐集数
    var selectedEpisodeId by remember(selectedRouteIndex) {
        val targetId = if (selectedRouteIndex == initialRouteIndex && initialEpisode != null) {
            initialEpisode.episodeId
        } else {
            currentRoute.episodes.firstOrNull()?.episodeId ?: 1
        }
        mutableIntStateOf(targetId)
    }
    val currentEpisode = currentRoute.episodes.find { it.episodeId == selectedEpisodeId }
        ?: currentRoute.episodes.firstOrNull()
        ?: VideoEpisode(1, "第 1 集", "")

    var isFavorite by remember { mutableStateOf(false) }

    val cleanYear = remember(videoData.year) {
        val digits = videoData.year.filter { it.isDigit() }
        val y = digits.take(4).toIntOrNull()
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (y != null && y in 1900..(currentYear + 1)) y.toString() else ""
    }

    val subInfo = remember(cleanYear, videoData.category, videoData.subtitle) {
        listOfNotNull(
            cleanYear.takeIf { it.isNotEmpty() },
            videoData.category.takeIf { it.isNotBlank() && it != "影视" },
            videoData.subtitle.takeIf { it.isNotBlank() && it != "更新至最新" }
        ).joinToString(" · ")
    }

    val parsedRating = remember(videoData.rating) {
        val score = videoData.rating.trim()
        val num = score.toDoubleOrNull()
        if (num != null && num > 0.0) score else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isLandscape || PipController.isInPip) Color.Black else PlayerBackground)
            // 竖屏播放时整页避开状态栏：否则视频画面从 y=0 开始，时间/电量会直接压在画面上，
            // 顶部返回按钮也会顶进挖孔区。横屏全屏播放会自行隐藏系统栏，不需要这段。
            .then(
                if (isLandscape || PipController.isInPip) Modifier
                else Modifier.statusBarsPadding()
            )
    ) {
        // 核心：单实例播放器，横屏占满全屏，竖屏保持 16:9 视窗 (横竖屏切换无缝平滑，ExoPlayer 实例永不销毁)
        // 画中画：小窗里必须铺满，否则会露出下面的选集面板（窗口只有手掌大，没地方放列表）
        MobileVideoPlayer(
            streamUrl = currentEpisode.streamUrl,
            title = "${videoData.title} · ${currentEpisode.name}",
            modifier = if (isLandscape || PipController.isInPip) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            },
            onBackClick = onBackClick
        )

        // 竖屏状态下显示选集与详情面板，横屏与画中画下自动隐藏
        if (!isLandscape && !PipController.isInPip) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 影片基本信息
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = videoData.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )

                            if (parsedRating != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFFFC107).copy(alpha = 0.16f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFC107),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
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

                        if (subInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = subInfo,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 规格标签药丸 (现代无边框浅底)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            videoData.tags.take(4).forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }

                // 快捷操作工具行 (追剧收藏、缓存离线、投屏、分享) - 现代化微拟物饱满底座
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerActionButton(
                            icon = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            label = if (isFavorite) "已追剧" else "追剧",
                            isActive = isFavorite,
                            onClick = {
                                isFavorite = !isFavorite
                                onShowToast(if (isFavorite) "已加入您的追剧清单" else "已取消追剧")
                            }
                        )
                        PlayerActionButton(
                            icon = Icons.Default.Download,
                            label = "离线缓存",
                            onClick = { onShowToast("后台高速下载任务已添加") }
                        )
                        PlayerActionButton(
                            icon = Icons.Default.Cast,
                            label = "DLNA投屏",
                            onClick = { onShowToast("正在搜索局域网电视设备...") }
                        )
                        PlayerActionButton(
                            icon = Icons.Default.Share,
                            label = "分享",
                            onClick = { onShowToast("分享链接已复制至剪贴板") }
                        )
                    }
                }

                // 播放线路选择
                if (videoData.routes.size > 1) {
                    item {
                        Column {
                            Text(
                                text = "播放源线路",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                videoData.routes.forEachIndexed { index, route ->
                                    val isSelected = selectedRouteIndex == index
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isSelected) PlayerPrimary.copy(alpha = 0.25f)
                                                else Color.White.copy(alpha = 0.06f)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) PlayerPrimary else Color.White.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                selectedRouteIndex = index
                                                onShowToast("已切换至线路：${route.routeName}")
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = route.routeName,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 剧集选集栏
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "选集 (${currentRoute.episodes.size} 集全)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "更新至最新话",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(currentRoute.episodes) { episode ->
                                val isCurrentPlaying = episode.episodeId == selectedEpisodeId
                                Box(
                                    modifier = Modifier
                                        .size(width = 84.dp, height = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCurrentPlaying) PlayerPrimary
                                            else Color.White.copy(alpha = 0.07f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isCurrentPlaying) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            selectedEpisodeId = episode.episodeId
                                            onShowToast("正在切换至：${episode.name}")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = episode.name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrentPlaying) Color.White else Color.White.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // 剧情简介
                item {
                    Column {
                        Text(
                            text = "剧情简介",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = videoData.description,
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    activeColor: Color = PlayerPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.22f)
                    else Color.White.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isActive) activeColor else Color.White.copy(alpha = 0.75f)
        )
    }
}

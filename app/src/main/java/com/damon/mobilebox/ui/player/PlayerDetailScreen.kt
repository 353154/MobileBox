package com.damon.mobilebox.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    if (isLandscape) {
        // 横屏状态：满屏沉浸式影院播放
        MobileVideoPlayer(
            streamUrl = currentEpisode.streamUrl,
            title = "${videoData.title} · ${currentEpisode.name}",
            modifier = Modifier.fillMaxSize(),
            onBackClick = onBackClick
        )
    } else {
        // 竖屏状态：顶部 16:9 播放器 + 下方交互选集面板
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 顶部 16:9 原生播放器视窗 (嵌入 Media3 ExoPlayer)
            MobileVideoPlayer(
                streamUrl = currentEpisode.streamUrl,
                title = "${videoData.title} · ${currentEpisode.name}",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.77f),
                onBackClick = onBackClick
            )

            // 下方交互面板
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
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = videoData.rating,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${videoData.year} · ${videoData.category} · ${videoData.subtitle}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 规格标签药丸
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            videoData.tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 快捷操作工具行 (追剧收藏、缓存离线、投屏、分享)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        PlayerActionButton(
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            label = if (isFavorite) "已追剧" else "追剧",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                isFavorite = !isFavorite
                                onShowToast(if (isFavorite) "已加入您的追剧收藏" else "已取消追剧")
                            }
                        )
                        PlayerActionButton(
                            icon = Icons.Default.Download,
                            label = "离线缓存",
                            onClick = { onShowToast("后台断点下载任务已添加") }
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
                item {
                    Column {
                        Text(
                            text = "播放源线路",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            videoData.routes.forEachIndexed { index, route ->
                                val isSelected = selectedRouteIndex == index
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.clickable {
                                        selectedRouteIndex = index
                                        onShowToast("已切换至线路：${route.routeName}")
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = route.routeName,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "更新至最新话",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(currentRoute.episodes) { episode ->
                                val isCurrentPlaying = episode.episodeId == selectedEpisodeId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .size(width = 80.dp, height = 48.dp)
                                        .clickable {
                                            selectedEpisodeId = episode.episodeId
                                            onShowToast("正在切换至：${episode.name}")
                                        }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = episode.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCurrentPlaying) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
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
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = videoData.description,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    tint: Color = MaterialTheme.colorScheme.onSurface,
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
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

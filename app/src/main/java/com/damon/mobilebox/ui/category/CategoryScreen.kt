package com.damon.mobilebox.ui.category

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.tvbox.MacCmsCategory
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.components.horizontalScrollFadingEdge
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 影视分类聚合矩阵屏 (CategoryScreen)
 * - 核心重构：分类数据全面接入 TvBoxRepository 内存两级缓存持久托管，页面出栈入栈 0ms 瞬间恢复，绝无二次刷新与闪烁
 * - 加载体验升级：彻底抛弃简陋居中 Spinner，引入现代商业流媒体骨架屏 (Shimmer Skeleton Grid)
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CategoryScreen(
    onMovieClick: (VideoDetailData) -> Unit = {},
    onShowToast: (String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    val realCategories by TvBoxRepository.realCategories.collectAsState()

    // 1. 从数据源真实分类树中提取一级分类 (typePid == 0)
    val primaryCategories = remember(realCategories) {
        val topList = realCategories.filter { it.typePid == 0 }
        val effective = if (topList.isNotEmpty()) topList else realCategories
        listOf(MacCmsCategory(typeId = 0, typePid = 0, typeName = "全部")) + effective
    }

    var selectedPrimaryId by remember { mutableIntStateOf(0) }

    // 2. 根据选中的一级分类，提取对应的二级子分类标签 (typePid == selectedPrimaryId)
    val secondaryCategories = remember(realCategories, selectedPrimaryId) {
        if (selectedPrimaryId == 0) {
            emptyList()
        } else {
            val subs = realCategories.filter { it.typePid == selectedPrimaryId }
            if (subs.isNotEmpty()) {
                listOf(MacCmsCategory(typeId = selectedPrimaryId, typePid = selectedPrimaryId, typeName = "全部")) + subs
            } else {
                emptyList()
            }
        }
    }

    var selectedSecondaryId by remember(selectedPrimaryId) {
        mutableIntStateOf(selectedPrimaryId)
    }

    // 实际向采集源发送的网络查询 ID
    val effectiveQueryId = remember(selectedPrimaryId, selectedSecondaryId) {
        if (selectedSecondaryId != 0) selectedSecondaryId else selectedPrimaryId
    }

    // 订阅 TvBoxRepository 全局持久托管的分类数据流 (页面切换绝对不丢状态)
    val categoryMovies by TvBoxRepository.categoryVodList.collectAsState()
    val isCategoryLoading by TvBoxRepository.isCategoryLoading.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    // 当选中的站点或分类标签变化时，向 TVBox 源发起分类过滤请求 (带内存缓存穿透保护)
    LaunchedEffect(selectedSite, effectiveQueryId) {
        val site = selectedSite ?: return@LaunchedEffect
        val catParam = if (effectiveQueryId == 0) null else effectiveQueryId.toString()
        val result = TvBoxRepository.loadCategoryVods(site, categoryId = catParam, forceRefresh = false)
        if (result.isFailure) {
            val ex = result.exceptionOrNull()
            if (ex !is CancellationException && ex?.message?.contains("coroutine scope", ignoreCase = true) != true) {
                onShowToast("获取分类内容失败: ${ex?.message}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ==========================================
        // 第一排：真实一级分类标签横向流
        // ==========================================
        if (primaryCategories.size > 1) {
            val primaryChipState = rememberLazyListState()
            LazyRow(
                state = primaryChipState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 6.dp)
                    .horizontalScrollFadingEdge(primaryChipState, 28.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(primaryCategories) { category ->
                    val isSelected = selectedPrimaryId == category.typeId
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedPrimaryId = category.typeId
                            selectedSecondaryId = category.typeId
                        },
                        label = {
                            Text(
                                text = category.typeName,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        border = null,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        // ==========================================
        // 第二排：真实二级子分类标签横向流
        // ==========================================
        if (secondaryCategories.size > 1) {
            val secondaryChipState = rememberLazyListState()
            LazyRow(
                state = secondaryChipState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .horizontalScrollFadingEdge(secondaryChipState, 28.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(secondaryCategories) { subCategory ->
                    val isSubSelected = selectedSecondaryId == subCategory.typeId
                    AssistChip(
                        onClick = {
                            selectedSecondaryId = subCategory.typeId
                        },
                        label = {
                            Text(
                                text = subCategory.typeName,
                                fontSize = 12.sp,
                                fontWeight = if (isSubSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isSubSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            },
                            labelColor = if (isSubSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        border = null,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        // ==========================================
        // 下方：分类影片双列瀑布流 (带下拉刷新与骨架屏)
        // ==========================================
        StandardPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                val site = selectedSite
                if (site == null) {
                    onShowToast("未激活任何采集源站点")
                    return@StandardPullToRefreshBox
                }
                coroutineScope.launch {
                    isRefreshing = true
                    val catParam = if (effectiveQueryId == 0) null else effectiveQueryId.toString()
                    val result = TvBoxRepository.loadCategoryVods(site, categoryId = catParam, forceRefresh = true)
                    isRefreshing = false
                    if (result.isSuccess) {
                        onShowToast("已为您刷新最新分类结果")
                    } else {
                        val ex = result.exceptionOrNull()
                        if (ex !is CancellationException && ex?.message?.contains("coroutine scope", ignoreCase = true) != true) {
                            onShowToast("刷新失败: ${ex?.message}")
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            if (isCategoryLoading && categoryMovies.isEmpty()) {
                // 现代化骨架屏脉冲扫光替代传统菊花转圈
                CategorySkeletonGrid()
            } else if (categoryMovies.isEmpty()) {
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
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedSite == null) "暂未配置 TVBox 订阅源" else "该分类暂未检索到视频数据",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (selectedSite == null) "请前往首页添加 TVBox 订阅链接" else "可尝试切换至其他分类或下拉刷新",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 96.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = categoryMovies,
                        key = { movie -> movie.id }
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
}

/**
 * 现代化商业流媒体骨架屏 (Shimmer Skeleton Grid)
 * 提前勾勒出 2 列圆角海报轮廓，配合柔和扫光动画，消除等待焦虑感
 */
@Composable
fun CategorySkeletonGrid() {
    val transition = rememberInfiniteTransition(label = "category_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        start = Offset(translateAnim - 250f, translateAnim - 250f),
        end = Offset(translateAnim + 250f, translateAnim + 250f)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false
    ) {
        items(6) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
            }
        }
    }
}

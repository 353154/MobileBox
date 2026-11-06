package com.damon.mobilebox.ui.category

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.tvbox.MacCmsCategory
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.components.animateItem
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlinx.coroutines.launch

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

    // 独立维护分类页根据真实采集源返回的影片列表
    var categoryMovies by remember { mutableStateOf<List<VideoDetailData>>(emptyList()) }
    var isCategoryLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 当选中的站点或分类标签变化时，向 TVBox 源发起真实的分类过滤请求 (t=type_id)
    LaunchedEffect(selectedSite, effectiveQueryId) {
        val site = selectedSite ?: return@LaunchedEffect
        isCategoryLoading = true
        val catParam = if (effectiveQueryId == 0) null else effectiveQueryId.toString()
        val result = TvBoxRepository.fetchCategoryVodList(site, categoryId = catParam, page = 1)
        isCategoryLoading = false
        if (result.isSuccess) {
            categoryMovies = result.getOrNull() ?: emptyList()
        } else {
            onShowToast("获取分类内容失败: ${result.exceptionOrNull()?.message}")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ==========================================
        // 第一排：真实一级分类标签横向流
        // ==========================================
        if (primaryCategories.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 6.dp),
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
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(50),
                        border = null
                    )
                }
            }
        }

        // ==========================================
        // 第二排：真实二级分类子标签横向流 (与一级分类严格联动)
        // ==========================================
        if (secondaryCategories.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(secondaryCategories) { subCategory ->
                    val isSelected = selectedSecondaryId == subCategory.typeId
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedSecondaryId = subCategory.typeId },
                        label = {
                            Text(
                                text = subCategory.typeName,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = if (!isSelected) FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = false,
                            borderColor = MaterialTheme.colorScheme.outlineVariant
                        ) else null
                    )
                }
            }
        }

        // ==========================================
        // 核心内容区：真实分类结果展示与物理阻尼刷新
        // ==========================================
        StandardPullToRefreshBox(
            isRefreshing = isRefreshing || (isCategoryLoading && categoryMovies.isEmpty()),
            onRefresh = {
                val site = selectedSite
                if (site == null) {
                    onShowToast("未配置订阅源，请先添加订阅")
                    return@StandardPullToRefreshBox
                }
                coroutineScope.launch {
                    isRefreshing = true
                    val catParam = if (effectiveQueryId == 0) null else effectiveQueryId.toString()
                    val result = TvBoxRepository.fetchCategoryVodList(site, categoryId = catParam, page = 1)
                    isRefreshing = false
                    if (result.isSuccess) {
                        categoryMovies = result.getOrNull() ?: emptyList()
                        onShowToast("已为您刷新最新分类结果")
                    } else {
                        onShowToast("刷新失败: ${result.exceptionOrNull()?.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            if (isCategoryLoading && categoryMovies.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "正在拉取采集源分类数据...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
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
                            modifier = animateItem(
                                fadeInSpec = tween(durationMillis = 400),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
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

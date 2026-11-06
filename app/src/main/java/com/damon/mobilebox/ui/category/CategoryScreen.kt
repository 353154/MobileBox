package com.damon.mobilebox.ui.category

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.components.animateItem
import com.damon.mobilebox.ui.main.MockDataProvider
import com.damon.mobilebox.ui.main.MockMovie
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CategoryScreen(
    onShowToast: (String) -> Unit = {}
) {
    val primaryCategories = listOf("全部", "电影", "电视剧", "动漫", "综艺", "纪录片")
    val secondaryCategories = listOf("全部", "最新上线", "高分榜单", "热播排行")

    var selectedPrimaryIndex by remember { mutableIntStateOf(0) }
    var selectedSecondaryIndex by remember { mutableIntStateOf(0) }

    val currentPrimary = primaryCategories[selectedPrimaryIndex]
    val currentSecondary = secondaryCategories[selectedSecondaryIndex]

    // 初始分类列表数据
    val baseList = remember(currentPrimary, currentSecondary) {
        val list = if (currentPrimary == "全部") {
            MockDataProvider.allCategoryMovies
        } else {
            MockDataProvider.allCategoryMovies.filter { it.category == currentPrimary }
        }
        when (currentSecondary) {
            "最新上线" -> list.reversed()
            "高分榜单" -> list.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            "热播排行" -> list.shuffled()
            else -> list
        }
    }

    var movies by remember(currentPrimary, currentSecondary) { mutableStateOf(baseList) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ==========================================
        // 第一排：主分类筛选 (视觉权重较高)
        // ==========================================
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(primaryCategories) { index, category ->
                val isSelected = selectedPrimaryIndex == index
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedPrimaryIndex = index },
                    label = {
                        Text(
                            text = category,
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

        // ==========================================
        // 第二排：子分类/排序筛选 (视觉权重较低)
        // ==========================================
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(secondaryCategories) { index, category ->
                val isSelected = selectedSecondaryIndex == index
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedSecondaryIndex = index },
                    label = {
                        Text(
                            text = category,
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

        // ==========================================
        // 核心改造：使用全局通用的 StandardPullToRefreshBox
        // ==========================================
        StandardPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshing = true
                    delay(1200) // 模拟网络嗅探请求
                    val newId = System.currentTimeMillis().toString()
                    val newCandidates = listOf(
                        Pair("黑神话：悟空 剧场版", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
                        Pair("异形：夺命舰", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
                        Pair("死侍与金钢狼", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg")
                    )
                    val chosen = newCandidates.random()
                    val newAdded = MockMovie(
                        id = newId,
                        title = chosen.first,
                        remarks = "4K 极清首发",
                        category = currentPrimary,
                        rating = "9.5",
                        posterUrl = chosen.second
                    )
                    // 在头部插入新数据，触发 animateItem 平滑位移动画
                    movies = listOf(newAdded) + movies
                    isRefreshing = false
                    onShowToast("已为您刷新并置顶：${newAdded.title}")
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            if (movies.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无相关分类内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
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
                    // 核心修复1：必须传入唯一 key
                    items(
                        items = movies,
                        key = { movie -> movie.id }
                    ) { movie ->
                        StandardPosterCard(
                            title = movie.title,
                            subtitle = movie.remarks,
                            score = movie.rating,
                            posterUrl = movie.posterUrl,
                            // 核心修复2：开启淡入淡出 (fadeInSpec) 与位移弹簧动画，解决首个卡片闪现问题
                            modifier = animateItem(
                                fadeInSpec = tween(durationMillis = 400),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
                            onClick = {
                                onShowToast("选中: ${movie.title} (${currentPrimary}·${currentSecondary})")
                            }
                        )
                    }
                }
            }
        }
    }
}

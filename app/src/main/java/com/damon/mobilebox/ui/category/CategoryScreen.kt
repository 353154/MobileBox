package com.damon.mobilebox.ui.category

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.ui.components.StandardPosterCard
import com.damon.mobilebox.ui.main.MockDataProvider

@Composable
fun CategoryScreen() {
    // 1. 定义两层分类的数据源
    val primaryCategories = listOf("全部", "电影", "电视剧", "动漫", "综艺", "纪录片")
    val secondaryCategories = listOf("全部", "最新上线", "高分榜单", "热播排行")

    // 2. 独立管理两层分类的选中状态
    var selectedPrimaryIndex by remember { mutableIntStateOf(0) }
    var selectedSecondaryIndex by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val currentPrimary = primaryCategories[selectedPrimaryIndex]
    val currentSecondary = secondaryCategories[selectedSecondaryIndex]

    // 联动过滤影视库数据
    val filteredMovies = remember(currentPrimary, currentSecondary) {
        val baseList = if (currentPrimary == "全部") {
            MockDataProvider.allCategoryMovies
        } else {
            MockDataProvider.allCategoryMovies.filter { it.category == currentPrimary }
        }
        when (currentSecondary) {
            "最新上线" -> baseList.reversed()
            "高分榜单" -> baseList.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            "热播排行" -> baseList.shuffled()
            else -> baseList
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    // 主分类使用 Primary 强调色系
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
                            fontSize = 13.sp, // 字号略小，降低视觉层级
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    // 子分类使用 Secondary 次级色系或边框模式
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp), // 采用小圆角，与主分类的全圆角形成明显区分
                    border = if (!isSelected) FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = false,
                        borderColor = MaterialTheme.colorScheme.outlineVariant
                    ) else null
                )
            }
        }

        // ==========================================
        // 下方海报瀑布流网格
        // ==========================================
        if (filteredMovies.isEmpty()) {
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
                    top = 4.dp, 
                    bottom = 96.dp 
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = filteredMovies,
                    key = { movie -> "cat_${movie.id}_${selectedPrimaryIndex}_${selectedSecondaryIndex}" }
                ) { movie ->
                    StandardPosterCard(
                        title = movie.title,
                        subtitle = movie.remarks,
                        score = movie.rating,
                        posterUrl = movie.posterUrl,
                        onClick = {
                            Toast.makeText(context, "选中: ${movie.title} (${currentPrimary}·${currentSecondary})", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

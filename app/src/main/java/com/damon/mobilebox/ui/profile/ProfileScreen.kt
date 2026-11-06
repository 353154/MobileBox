package com.damon.mobilebox.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==========================================
// 1. 预留的数据接口层 (供 ViewModel 绑定真实数据)
// ==========================================
data class ViewingStats(
    val last7DaysHours: Float = 12.5f,
    val weekHours: Float = 8.2f,
    val weekMovies: Int = 3,
    val weekEpisodes: Int = 12,
    val todayHours: Float = 2.1f,
    val todayMovies: Int = 1,
    val todayEpisodes: Int = 2,
    val weekDateRange: String = "11月1日 - 7日",
    val todayDate: String = "今天",
    // 用于图表渲染的具体数据列表
    val last7DaysData: List<Float> = listOf(1.5f, 2.0f, 0.5f, 3.2f, 1.0f, 0.0f, 4.3f), // 7天数据
    val weekDaysLabels: List<String> = listOf("日", "一", "二", "三", "四", "五", "六"),
    val today24hData: List<Float> = List(24) { index ->
        when (index) {
            in 19..22 -> (2..5).random().toFloat()
            in 12..13 -> 1.5f
            in 8..9 -> 0.8f
            else -> 0f
        }
    }
)

// ==========================================
// 2. 主页面入口 (已移除 Damon 个人信息头部)
// ==========================================
@Composable
fun ProfileScreen(
    stats: ViewingStats = ViewingStats(),
    onShowToast: (String) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp) // 顶部微距，底部留出悬浮导航栏安全区
    ) {
        // 核心：横向滑动的统计卡片区域 (三页结构：最近7天 / 本周观看 / 今天)
        item { StatsPagerSection(stats) }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 历史观看模块
        item { HistorySection(onShowToast = onShowToast) }

        // 待看与功能网格模块
        item { WatchlistAndGridSection(onShowToast = onShowToast) }
    }
}

// ==========================================
// 3. 核心横向滑动组件 (HorizontalPager)
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsPagerSection(stats: ViewingStats) {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp
        ) { page ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxSize()
                ) {
                    when (page) {
                        0 -> Last7DaysContent(stats)
                        1 -> ThisWeekContent(stats)
                        2 -> TodayContent(stats)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 页面指示器 (Dots)
        Row(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pagerState.pageCount) { iteration ->
                val isSelected = pagerState.currentPage == iteration
                val color = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .clip(CircleShape)
                        .background(color)
                        .size(if (isSelected) 7.dp else 5.dp)
                )
            }
        }
    }
}

// ==========================================
// 4. 卡片具体内容实现 (数据 + Canvas 可视化图表)
// ==========================================
@Composable
fun Last7DaysContent(stats: ViewingStats) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("最近 7 天", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "稳步上升",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text("${stats.last7DaysHours}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("观影总时长 (小时)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 右侧嵌入通用柱状图
            StatBarChart(
                data = stats.last7DaysData,
                labels = stats.weekDaysLabels,
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun ThisWeekContent(stats: ViewingStats) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("本周观看", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(stats.weekDateRange, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text("${stats.weekHours} 小时", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${stats.weekMovies} 部电影\n${stats.weekEpisodes} 集剧集",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatBarChart(
                data = stats.last7DaysData,
                labels = stats.weekDaysLabels,
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun TodayContent(stats: ViewingStats) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("今天", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(stats.todayDate, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text("${stats.todayHours} 小时", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${stats.todayMovies} 部电影\n${stats.todayEpisodes} 集剧集",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 24小时分布细柱状图
            StatBarChart(
                data = stats.today24hData,
                barWidthDp = 4f,
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, bottom = 4.dp)
            )
        }
    }
}

// ==========================================
// 5. 纯 Compose Canvas 数据可视化图表
// ==========================================
@Composable
fun StatBarChart(
    data: List<Float>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    barWidthDp: Float = 9f,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
) {
    val maxVal = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val count = data.size
            if (count == 0) return@Canvas

            val barWidth = barWidthDp.dp.toPx()
            val totalWidth = size.width
            val totalHeight = size.height
            val spacing = (totalWidth - (barWidth * count)) / (count + 1).coerceAtLeast(1)

            data.forEachIndexed { i, value ->
                val x = spacing + i * (barWidth + spacing) + barWidth / 2f
                val fillRatio = (value / maxVal).coerceIn(0.08f, 1f)
                val barHeight = totalHeight * fillRatio

                // 1. 绘制背景槽轨 (全圆角背景条)
                drawLine(
                    color = trackColor,
                    start = Offset(x, totalHeight - barWidth / 2f),
                    end = Offset(x, barWidth / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )

                // 2. 绘制前景填充柱 (高亮全圆角数值条)
                if (value > 0f) {
                    val fillTop = (totalHeight - barHeight).coerceAtLeast(barWidth / 2f)
                    drawLine(
                        color = barColor,
                        start = Offset(x, totalHeight - barWidth / 2f),
                        end = Offset(x, fillTop),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // 标签行
        if (labels.isNotEmpty() && labels.size == data.size) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ==========================================
// 6. 下方功能模块区
// ==========================================
/**
 * 空状态行动号召胶囊 (CTA)
 * 大面积空卡片里只放一句灰色说明会显得空洞、且没有出路；
 * 用一枚主题色浅底胶囊把“下一步该做什么”显性化。
 */
@Composable
private fun EmptyStateCta(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun HistorySection(onShowToast: (String) -> Unit = {}) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("历史观看", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            onClick = { onShowToast("打开完整观看历史") },
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "暂无未播完的记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // 空状态行动号召：把原本纯说明性的灰字收敛为一枚明确的 CTA 胶囊
                EmptyStateCta(label = "去发现")
            }
        }
    }
}

@Composable
fun WatchlistAndGridSection(onShowToast: (String) -> Unit = {}) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowToast("打开待看片单") }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("待看片单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        // 待看卡片 (紧凑瘦身，消除大面积留白与空洞感，统一收敛交互)
        Card(
            onClick = { onShowToast("前往发现影片并加入片单") },
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "暂无待看影片",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                EmptyStateCta(label = "去追剧")
            }
        }
        
        Spacer(modifier = Modifier.height(14.dp))

        // 收藏与在看网格 (双列紧凑堆叠：主题色大字重数据 + 灰色辅助说明)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                onClick = { onShowToast("打开我的收藏 (14部)") },
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("14", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("我的收藏", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                onClick = { onShowToast("正在追更在看 (3部)") },
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("3", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("在看剧集", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.watch.FavoriteItem
import com.damon.mobilebox.data.watch.WatchRecord
import com.damon.mobilebox.data.watch.WatchStore
import java.util.Calendar

// ==========================================
// 1. 数据层：全部由 WatchStore 的真实播放历史推导，默认值一律为 0
// ==========================================
data class ViewingStats(
    // 「全部观看」卡片用：不限时间窗，按电影 / 剧集 / 直播三类分别累计
    val totalMillis: Long = 0L,
    val movieMillis: Long = 0L,
    val seriesMillis: Long = 0L,
    val liveMillis: Long = 0L,
    val totalRecords: Int = 0,
    val last7DaysMillis: Long = 0L,
    val weekMillis: Long = 0L,
    val weekMovies: Int = 0,
    val weekEpisodes: Int = 0,
    val todayMillis: Long = 0L,
    val todayMovies: Int = 0,
    val todayEpisodes: Int = 0,
    val weekDateRange: String = "",
    val todayDate: String = "今天",
    // 曲线数据单位统一为「分钟」，不再 round1：看几分钟也能画出可见的柱子
    val last7DaysData: List<Float> = List(7) { 0f },
    val weekDaysLabels: List<String> = listOf("日", "一", "二", "三", "四", "五", "六"),
    val today24hData: List<Float> = List(24) { 0f }
)

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MINUTE_MS = 60_000L
private val WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")

private fun minutesOf(millis: Long): Float = millis.coerceAtLeast(0L) / MINUTE_MS.toFloat()

/**
 * 精确到分钟的时长文案：不足 1 分钟退化为秒，超过 1 小时拆成「X 小时 Y 分钟」，
 * 避免看两小时显示成「125 分钟」。统计卡片与 [WatchListScreen] 共用这一个口径。
 */
fun formatWatchDuration(millis: Long): String {
    // 0 毫秒必须如实显示「0 秒」：旧实现用 coerceAtLeast(1L) 把空分类写成「1 秒」，
    // 会让「共 1 条剧集记录」看起来凭空多统计了一部电影（用户 17:13 反馈）。
    if (millis <= 0L) return "0 秒"
    val seconds = millis / 1000L
    if (seconds < 1L) return "1 秒"
    if (seconds < 60L) return "$seconds 秒"
    val totalMinutes = seconds / 60L
    if (totalMinutes < 60L) return "$totalMinutes 分钟"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
}

/**
 * 用真实播放记录算统计，界面文案一个字不改。
 * watchedMs 是「本次播放会话的墙上时间」，含暂停 / 后台，是上界近似值；
 * 电影按影片 id 去重，剧集按开播次数计；跨小时的会话整段记在开播的那个小时里。
 * minSdk 24，用 Calendar 而不是 java.time，不引入 desugaring。
 */
private fun buildViewingStats(history: List<WatchRecord>): ViewingStats {
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val dayStarts = (6 downTo 0).map { todayStart - it * DAY_MS }
    val dailyMinutes = FloatArray(7) { 0f }
    val hourlyMinutes = FloatArray(24) { 0f }
    val weekMovies = mutableSetOf<String>()
    val todayMovies = mutableSetOf<String>()
    var weekEpisodes = 0
    var todayEpisodes = 0
    var weekMillis = 0L
    var todayMillis = 0L
    var totalMillis = 0L
    var movieMillis = 0L
    var seriesMillis = 0L
    var liveMillis = 0L

    history.forEach { record ->
        val millis = record.watchedMs.coerceAtLeast(0L)
        val minutes = minutesOf(millis)
        // 全部观看：不看时间窗，也不做 7 天过滤，按内容类型分别累计
        totalMillis += millis
        when {
            record.isLive -> liveMillis += millis
            record.isSeries -> seriesMillis += millis
            else -> movieMillis += millis
        }
        val dayIndex = dayStarts.indexOfFirst { record.startedAt >= it && record.startedAt < it + DAY_MS }
        if (dayIndex >= 0) {
            dailyMinutes[dayIndex] += minutes
            weekMillis += millis
            // 直播是第三类，不再被当成「一部电影」计进部数
            when {
                record.isLive -> Unit
                record.isSeries -> weekEpisodes++
                else -> weekMovies += record.videoId
            }
        }
        if (record.startedAt >= todayStart) {
            todayMillis += millis
            when {
                record.isLive -> Unit
                record.isSeries -> todayEpisodes++
                else -> todayMovies += record.videoId
            }
            val hour = Calendar.getInstance().apply { timeInMillis = record.startedAt }
                .get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            hourlyMinutes[hour] += minutes
        }
    }

    val first = Calendar.getInstance().apply { timeInMillis = dayStarts.first() }
    val last = Calendar.getInstance().apply { timeInMillis = dayStarts.last() }
    val dateRange = "${first.get(Calendar.MONTH) + 1}月${first.get(Calendar.DAY_OF_MONTH)}日" +
        " - ${last.get(Calendar.DAY_OF_MONTH)}日"

    return ViewingStats(
        totalMillis = totalMillis,
        movieMillis = movieMillis,
        seriesMillis = seriesMillis,
        liveMillis = liveMillis,
        totalRecords = history.size,
        last7DaysMillis = weekMillis,
        weekMillis = weekMillis,
        weekMovies = weekMovies.size,
        weekEpisodes = weekEpisodes,
        todayMillis = todayMillis,
        todayMovies = todayMovies.size,
        todayEpisodes = todayEpisodes,
        weekDateRange = dateRange,
        todayDate = "今天",
        last7DaysData = dailyMinutes.toList(),
        weekDaysLabels = dayStarts.map { start ->
            val c = Calendar.getInstance().apply { timeInMillis = start }
            WEEKDAY_LABELS[(c.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
        },
        today24hData = hourlyMinutes.toList()
    )
}

// ==========================================
// 2. 主页面入口 (已移除 Damon 个人信息头部)
// ==========================================
@Composable
fun ProfileScreen(
    stats: ViewingStats? = null,
    onOpenList: (WatchListType) -> Unit = {}
) {
    // 收藏与历史都来自 WatchStore 的共享 StateFlow：详情页点完「追剧」回到本页即刻可见，
    // 重启后也在。stats 保留为可空覆盖入口，正常业务不传，一律由真实历史推导。
    val favorites by WatchStore.favorites.collectAsState()
    val history by WatchStore.history.collectAsState()
    val effectiveStats = remember(stats, history) { stats ?: buildViewingStats(history) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp) // 顶部微距，底部留出悬浮导航栏安全区
    ) {
        // 核心：横向滑动的统计卡片区域 (三页结构：最近7天 / 本周观看 / 今天)
        item { StatsPagerSection(effectiveStats) }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 历史观看模块
        item { HistorySection(history = history, onOpenList = onOpenList) }

        // 待看与功能网格模块
        item {
            WatchlistAndGridSection(
                favorites = favorites,
                history = history,
                onOpenList = onOpenList
            )
        }
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
                    .height(190.dp),
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
                        0 -> AllWatchContent(stats)
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
fun AllWatchContent(stats: ViewingStats) {
    // 用户 16:59 要求：第一页从「最近 7 天 + 柱状图」改成「全部观看」，
    // 去掉 7 天柱图（没数据的日子也曾被画成等高浅蓝柱），改为分列三类时长。
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "全部观看",
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "共 ${stats.totalRecords} 条记录",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Text(
            formatWatchDuration(stats.totalMillis),
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "累计观看时长",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 只列真正有观看时长的分类：一条「剧集」记录不该在下面凭空多出一行「电影」。
        val categories = listOf(
            Triple("电影", stats.movieMillis, MaterialTheme.colorScheme.primary),
            Triple("剧集", stats.seriesMillis, MaterialTheme.colorScheme.tertiary),
            Triple("直播", stats.liveMillis, MaterialTheme.colorScheme.secondary)
        ).filter { it.second > 0L }
        if (categories.isEmpty()) {
            Text(
                "暂无观看记录",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            categories.forEach { (label, millis, color) ->
                CategoryDurationRow(label, millis, color)
            }
        }
    }
}

/** 「全部观看」卡片里的一行分类时长：左侧色点 + 名称，右侧时长。 */
@Composable
private fun CategoryDurationRow(label: String, millis: Long, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatWatchDuration(millis),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
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
                Text(
                    formatWatchDuration(stats.weekMillis),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
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
                Text(
                    formatWatchDuration(stats.todayMillis),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
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
                val bottomY = totalHeight - barWidth / 2f
                val hasValue = value > 0f

                if (hasValue) {
                    val fillRatio = (value / maxVal).coerceIn(0.08f, 1f)
                    val barHeight = totalHeight * fillRatio
                    // 1. 有数据的日子才画满高槽轨
                    drawLine(
                        color = trackColor,
                        start = Offset(x, bottomY),
                        end = Offset(x, barWidth / 2f),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                    // 2. 前景填充柱
                    val fillTop = (totalHeight - barHeight).coerceAtLeast(barWidth / 2f)
                    drawLine(
                        color = barColor,
                        start = Offset(x, bottomY),
                        end = Offset(x, fillTop),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                } else {
                    // 没数据只留一个浅色圆点。原来无条件画满高轨道，
                    // 7 天里 6 天没看也会出现 6 根等高浅蓝柱，看起来像每天都在看。
                    drawCircle(
                        color = trackColor,
                        radius = barWidth / 2f,
                        center = Offset(x, bottomY)
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
fun HistorySection(
    history: List<WatchRecord> = emptyList(),
    onOpenList: (WatchListType) -> Unit = {}
) {
    val latest = history.firstOrNull()
    val latestTitle = latest?.let {
        listOf(it.title, it.episodeName).filter { part -> part.isNotBlank() }.joinToString(" · ")
    }.orEmpty()
    // 原来这一行只有「片名 · 第1集」，没有任何播放进度；补上「看到 30:12 / 45:00」。
    val latestMeta = latest?.let { rec ->
        val progress = if (rec.positionMs > 0L) {
            val position = formatMediaTime(rec.positionMs)
            if (rec.durationMs > 0L) {
                "看到 $position / ${formatMediaTime(rec.durationMs)}"
            } else {
                "看到 $position"
            }
        } else ""
        val watched = if (rec.watchedMs > 0L) "累计 ${formatWatchDuration(rec.watchedMs)}" else ""
        listOf(progress, watched).filter { it.isNotBlank() }.joinToString(" · ")
    }.orEmpty()
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("历史观看", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            onClick = { onOpenList(WatchListType.HISTORY) },
            // 高度自适应：两行文字 + 系统字体放大时，固定 68dp 会把第二行裁掉
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = latestTitle.ifBlank { "暂无未播完的记录" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (latestMeta.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = latestMeta,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 空状态行动号召：把原本纯说明性的灰字收敛为一枚明确的 CTA 胶囊
                EmptyStateCta(label = "去发现")
            }
        }
    }
}

@Composable
fun WatchlistAndGridSection(
    favorites: List<FavoriteItem> = emptyList(),
    history: List<WatchRecord> = emptyList(),
    onOpenList: (WatchListType) -> Unit = {}
) {
    // 待看 = 收藏了但还没有任何播放记录；在看 = 有播放记录的不同影片数。
    val watchedIds = remember(history) { history.filterNot { it.isLive }.map { it.videoId }.toSet() }
    val pendingCount = favorites.count { it.videoId !in watchedIds }
    val watchingCount = watchedIds.size
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenList(WatchListType.PENDING) }
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
            onClick = { onOpenList(WatchListType.PENDING) },
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
                    text = if (pendingCount > 0) "$pendingCount 部待看影片" else "暂无待看影片",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                EmptyStateCta(label = "去追剧")
            }
        }
        
        Spacer(modifier = Modifier.height(14.dp))

        // 收藏与在看网格：改成「图标 + 数字 + 标签」横排。
        // 原来是 72dp 固定高硬塞 26sp 大字 + 12sp 小字，系统字体一放大就被卡片裁掉
        // （用户 16:38 反馈「字体被外围元素挡住、显示很不完整」），现在高度自适应。
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WatchCountCard(
                count = favorites.size,
                label = "我的收藏",
                icon = Icons.Default.Favorite,
                onClick = { onOpenList(WatchListType.FAVORITES) },
                modifier = Modifier.weight(1f)
            )
            WatchCountCard(
                count = watchingCount,
                label = "在看剧集",
                icon = Icons.Default.PlayArrow,
                onClick = { onOpenList(WatchListType.WATCHING) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WatchCountCard(
    count: Int,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 84.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "$count",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

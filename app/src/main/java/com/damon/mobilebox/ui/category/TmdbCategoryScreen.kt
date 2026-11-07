package com.damon.mobilebox.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tmdb.TmdbGenre
import com.damon.mobilebox.data.tmdb.TmdbMeta
import com.damon.mobilebox.data.tmdb.TmdbRepository

/**
 * 分类页（TMDB 版，2026-11-07 新建）。
 *
 * 版式与首页第二屏以下完全一致：**没有大海报**，只有
 * 「类型名 + 一行可横滑的竖版海报」，行间距 / 海报宽高 / 左右边距都取首页同一组数值，
 * 从首页滑到分类页不会觉得换了个 App。
 *
 * 数据：`/genre/{movie|tv}/list` 拿类型表（1 次），每行再用 `/discover` 按 `with_genres`
 * 拉 14 条热门（`vote_count.gte=50` 过滤 0 分新片）。
 *
 * **为什么要一行一个 `LaunchedEffect`**：`LazyColumn` 只组合屏幕内（含预取）的 item，
 * 请求自然只在「这行真的要出现」时发出。一次性并发 12 个 discover 会瞬间打满代理限流，
 * 首屏也会被一堆转圈拖慢。行内还有 30 分钟缓存（`TmdbRepository.rowCache`），
 * 来回切 Tab 不会重复请求。
 *
 * 旧版采集源分类页（`CategoryScreen.kt`）整份保留，由 `AnimatedMainScreen` 里的
 * `UseTmdbCategory` 开关决定用哪个。
 */
@Composable
fun TmdbCategoryScreen(
    onEntryClick: (TmdbMeta) -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {}
) {
    var mediaType by rememberSaveable { mutableStateOf("movie") }
    var genres by remember { mutableStateOf<List<TmdbGenre>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(mediaType, reload) {
        genres = null
        failed = false
        val all = TmdbRepository.genres(mediaType, force = reload > 0)
        // 按人工排好的顺序铺：TMDB 原始顺序是按 id 升序，纪录片会排在第二个。
        val order = if (mediaType == "tv") TV_GENRE_ORDER else MOVIE_GENRE_ORDER
        val ranked = order.mapNotNull { id -> all.firstOrNull { it.id == id } } +
            all.filterNot { it.id in order }
        genres = ranked
        failed = ranked.isEmpty()
    }

    // 切电影 / 剧集回到顶部，否则会停在上一份数据的中段。
    LaunchedEffect(mediaType) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "分类",
                    color = scheme.onBackground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = scheme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediaChip("电影", mediaType == "movie") { mediaType = "movie" }
                MediaChip("剧集", mediaType == "tv") { mediaType = "tv" }
            }

            val loaded = genres
            when {
                failed -> CategoryEmptyState(onRetry = { reload++ })
                loaded == null -> CategorySkeleton()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
                    items(loaded, key = { it.id }) { genre ->
                        GenreRowSection(
                            mediaType = mediaType,
                            genre = genre,
                            onEntryClick = onEntryClick
                        )
                    }
                }
            }
        }

        // ---- 底部渐隐：与首页同一套「化开」----
        // 导航栏没有托底面板，图标直接浮在页面上，内容滚到底部必须淡进底色，
        // 否则每一行海报都会在图标上方被一条硬边切掉。
        // 这一层不带任何指针输入（纯 drawBehind），点击与滚动照常穿透到下面的列表。
        // 终点取本组合的底边 = 导航栏上沿（外层已经 padding(bottom = bottomBarInset)）。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val h = size.height.coerceAtLeast(1f)
                    val from = ((h - FADE_HEIGHT.toPx()) / h).coerceIn(0f, 1f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            from to Color.Transparent,
                            1f to scheme.background
                        )
                    )
                }
        )
    }
}

/**
 * 一个类型行。
 *
 * 状态放在行内部（`remember(mediaType, genre.id)`）：行被回收再滚回来时不会重新请求，
 * 因为 `remember` 的 key 没变、Compose 会复用；真正冷启动或切类型才会重来一次。
 */
@Composable
private fun GenreRowSection(
    mediaType: String,
    genre: TmdbGenre,
    onEntryClick: (TmdbMeta) -> Unit
) {
    var items by remember(mediaType, genre.id) { mutableStateOf<List<TmdbMeta>?>(null) }
    LaunchedEffect(mediaType, genre.id) {
        items = TmdbRepository.discover(mediaType, genre.id)
    }
    val scheme = MaterialTheme.colorScheme
    val row = items

    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = genre.name.orEmpty(),
            color = scheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        )
        when {
            // 空行整块不渲染：宁可少一行，也不要一行「暂无内容」的占位档住滚动。
            row != null && row.isEmpty() -> Unit
            row == null -> RowSkeleton()
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = row.size,
                    key = { index -> "${row[index].mediaType}:${row[index].tmdbId}" }
                ) { index ->
                    CategoryPoster(entry = row[index], onClick = onEntryClick)
                }
            }
        }
    }
}

/** 竖版海报卡：尺寸 / 圆角 / 标题字号与首页 `PosterTile` 逐项对齐。 */
@Composable
private fun CategoryPoster(entry: TmdbMeta, onClick: (TmdbMeta) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick(entry) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.onSurface.copy(alpha = 0.06f))
        ) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.title,
            color = scheme.onBackground.copy(alpha = 0.92f),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 电影 / 剧集切换。选中态用主色实心，未选中只给一层浅色底，不做描边。 */
@Composable
private fun MediaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(
                if (selected) scheme.primary
                else scheme.onSurface.copy(alpha = 0.06f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 类型表还没回来：三行占位，避免白屏一跳。 */
@Composable
private fun CategorySkeleton() {
    Column(Modifier.fillMaxSize()) {
        repeat(3) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Box(
                    Modifier
                        .padding(start = 20.dp)
                        .width(72.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                )
                Spacer(Modifier.height(12.dp))
                RowSkeleton()
            }
        }
    }
}

/** 单行的海报位占位。 */
@Composable
private fun RowSkeleton() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(5) {
            Box(
                Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            )
        }
    }
}

/** 类型表拿不到（网络 / key 问题）时的兜底。 */
@Composable
private fun CategoryEmptyState(onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "分类加载失败",
                color = scheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TMDB 没有返回类型列表，检查网络后重试",
                color = scheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(scheme.onSurface.copy(alpha = 0.08f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text("重试", color = scheme.onBackground, fontSize = 13.sp)
            }
        }
    }
}

/** 底部渐隐的高度。与首页 `TmdbHomeContent` 的 110dp 取齐，两页滚到底的观感才一致。 */
private val FADE_HEIGHT = 110.dp

/** 电影类型的人工顺序（TMDB id）：动作 / 喜剧 / 剧情 / 科幻 … 前两屏最常点的排前面。 */
private val MOVIE_GENRE_ORDER = listOf(
    28, 35, 18, 878, 27, 10749, 16, 80, 53, 9648, 12, 14, 36, 99
)

/** 剧集类型的人工顺序：动作冒险 / 喜剧 / 剧情 / 悬疑 / 犯罪 / 动画 … */
private val TV_GENRE_ORDER = listOf(
    10759, 35, 18, 9648, 80, 16, 10765, 10764, 10751, 99
)

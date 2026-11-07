package com.damon.mobilebox.ui.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.watch.FavoriteItem
import com.damon.mobilebox.data.watch.WatchRecord
import com.damon.mobilebox.data.watch.WatchStore
import com.damon.mobilebox.ui.components.rememberRetryableImageRequest
import com.damon.mobilebox.ui.components.toLoadableImageUrl
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「我的」页四个入口共用一个列表页，靠这个枚举区分数据源与标题。 */
enum class WatchListType(val title: String) {
    HISTORY("观看历史"),
    FAVORITES("我的收藏"),
    PENDING("待看片单"),
    WATCHING("在看剧集")
}

private data class WatchListRow(
    val key: String,
    val videoId: String,
    val title: String,
    val posterUrl: String,
    val detailLine: String,
    val metaLine: String
)

private fun formatStamp(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(millis))

private fun joinNonBlank(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString(" · ")

/** 只做「Store → 展示行」的映射，不写回任何数据。 */
private fun buildRows(
    type: WatchListType,
    favorites: List<FavoriteItem>,
    history: List<WatchRecord>
): List<WatchListRow> {
    val watchedIds = history.map { it.videoId }.toSet()
    return when (type) {
        WatchListType.HISTORY -> history.map { r ->
            WatchListRow(
                key = r.id,
                videoId = r.videoId,
                title = r.title.ifBlank { "未知影片" },
                posterUrl = r.posterUrl,
                detailLine = joinNonBlank(r.episodeName, r.sourceName)
                    .ifBlank { if (r.isSeries) "剧集" else "影片" },
                metaLine = joinNonBlank(
                    formatStamp(r.startedAt),
                    if (r.watchedMs > 0L) "观看 ${formatWatchDuration(r.watchedMs)}" else ""
                ).ifBlank { "刚开播" }
            )
        }

        WatchListType.FAVORITES -> favorites.map { f ->
            WatchListRow(
                key = "fav_${f.videoId}",
                videoId = f.videoId,
                title = f.title.ifBlank { "未知影片" },
                posterUrl = f.posterUrl,
                detailLine = if (f.videoId in watchedIds) "已有观看记录" else "尚未观看",
                metaLine = joinNonBlank("加入于", formatStamp(f.addedAt))
            )
        }

        WatchListType.PENDING -> favorites.filter { it.videoId !in watchedIds }.map { f ->
            WatchListRow(
                key = "pending_${f.videoId}",
                videoId = f.videoId,
                title = f.title.ifBlank { "未知影片" },
                posterUrl = f.posterUrl,
                detailLine = "收藏后尚未播放",
                metaLine = joinNonBlank("加入于", formatStamp(f.addedAt))
            )
        }

        WatchListType.WATCHING -> history.groupBy { it.videoId }.values
            .sortedByDescending { group -> group.maxOf { it.startedAt } }
            .map { records ->
                val latest = records.maxByOrNull { it.startedAt } ?: records.first()
                WatchListRow(
                    key = "watching_${latest.videoId}",
                    videoId = latest.videoId,
                    title = latest.title.ifBlank { "未知影片" },
                    posterUrl = latest.posterUrl,
                    detailLine = joinNonBlank(
                        "最近观看 ${latest.episodeName.ifBlank { "影片" }}",
                        "共 ${records.size} 次"
                    ),
                    metaLine = joinNonBlank(
                        formatStamp(latest.startedAt),
                        "累计 ${formatWatchDuration(records.sumOf { it.watchedMs })}"
                    )
                )
            }
    }
}

/**
 * 历史/收藏只存展示所需的最小字段，详情与线路仍从订阅源现取。
 * 优先命中内存缓存（首页/分类/上次详情），没有再按片名做一次全站精确同名搜索。
 * 绝不用 VideoDetailData.createFromMovie 兜底 —— 那份带的是官方测试流，点开会放错片。
 */
private suspend fun resolveDetail(row: WatchListRow): VideoDetailData? {
    val id = row.videoId.trim()
    TvBoxRepository.selectedDetail
        ?.takeIf { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    TvBoxRepository.realVodList.value
        .firstOrNull { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    TvBoxRepository.categoryVodList.value
        .firstOrNull { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    if (row.title.isBlank()) return null
    return TvBoxRepository.searchAll(row.title)
        .firstOrNull { it.detail.title.trim() == row.title.trim() && it.detail.routes.isNotEmpty() }
        ?.detail
}

@Composable
fun WatchListScreen(
    type: WatchListType,
    onBack: () -> Unit,
    onOpenDetail: (VideoDetailData) -> Unit
) {
    val favorites by WatchStore.favorites.collectAsState()
    val history by WatchStore.history.collectAsState()
    val rows = remember(type, favorites, history) { buildRows(type, favorites, history) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolvingKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = type.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "共 ${rows.size} 条",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        )

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when (type) {
                        WatchListType.HISTORY -> "还没有观看记录"
                        WatchListType.FAVORITES -> "还没有收藏影片"
                        WatchListType.PENDING -> "没有待看影片"
                        WatchListType.WATCHING -> "还没有在看的影片"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = rows, key = { it.key }) { row ->
                    WatchListRowItem(
                        row = row,
                        resolving = resolvingKey == row.key,
                        onClick = {
                            if (resolvingKey == null) {
                                resolvingKey = row.key
                                scope.launch {
                                    val detail = runCatching {
                                        withTimeoutOrNull(8000L) { resolveDetail(row) }
                                    }.getOrNull()
                                    resolvingKey = null
                                    if (detail != null) {
                                        onOpenDetail(detail)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "未找到该影片的可用播放源",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchListRowItem(
    row: WatchListRow,
    resolving: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = rememberRetryableImageRequest(context, row.posterUrl.toLoadableImageUrl()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(54.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.detailLine,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = row.metaLine,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (resolving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
